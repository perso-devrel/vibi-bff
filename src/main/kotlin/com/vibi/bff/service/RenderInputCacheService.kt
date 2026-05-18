package com.vibi.bff.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Shared input cache for the multi-variant render pipeline.
 *
 * Use case: a single source video is rendered into N variants. Re-uploading
 * the same 100MB+ mp4 N times over multipart wastes bandwidth + disk. Mobile
 * uploads it once via `POST /render/inputs`, receives an [inputId]
 * (sha256-derived → stable across re-uploads), then each variant's
 * `POST /render` references that id instead of re-sending the bytes.
 *
 * Layout under [baseDir]:
 * ```
 * <baseDir>/
 *   <inputId>/
 *     metadata.json           — createdAt, lastAccessAt, video name
 *     video.<ext>             — single video, name preserved (sanitized)
 * ```
 *
 * Concurrency: a single in-process write lock protects the cache directory
 * — sha256 hashing + dedup-or-create is racy otherwise. Cleanup is also
 * `synchronized` against the same lock so an entry can't be deleted
 * mid-resolve in the same process.
 */
class RenderInputCacheService(
    val baseDir: File,
    private val ttlMs: Long = TimeUnit.HOURS.toMillis(24),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeLock = Any()

    init {
        baseDir.mkdirs()
        log.info("Render input cache initialized at {} (ttlMs={})", baseDir.absolutePath, ttlMs)
    }

    /** TTL = ttlMs from lastAccessAt; useful for `expiresAt` reporting. */
    val ttlMillis: Long get() = ttlMs

    /**
     * Save a new entry. The video stream is hashed *while* being persisted
     * to a temp file; on hash completion we atomically move the temp file
     * into `<baseDir>/<inputId>/video.<ext>` (creating that directory). If
     * the inputId already exists (cache hit), the temp file is discarded
     * and only `lastAccessAt` is bumped — no body rewrite.
     */
    fun save(
        videoFileName: String,
        videoStream: InputStream,
        maxVideoBytes: Long,
    ): CachedInput {
        val tmpVideoFile = File(baseDir, "incoming-${java.util.UUID.randomUUID()}.tmp")
        val safeVideoName = sanitizeFilename(videoFileName).ifEmpty { "video.mp4" }

        val sha = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        try {
            videoStream.use { input ->
                tmpVideoFile.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        totalBytes += n
                        if (totalBytes > maxVideoBytes) {
                            throw IllegalArgumentException(
                                "Video exceeds maximum size of ${maxVideoBytes / 1024 / 1024}MB"
                            )
                        }
                        sha.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
            val inputId = sha.digest().take(16).joinToString("") { "%02x".format(it) }

            synchronized(writeLock) {
                val entryDir = File(baseDir, inputId)
                val isHit = entryDir.exists() && File(entryDir, METADATA_NAME).exists()

                if (isHit) {
                    runCatching { tmpVideoFile.delete() }
                    val existing = readMetadataOrNull(entryDir) ?: throw IllegalStateException(
                        "Cache entry $inputId missing metadata after exists() check"
                    )
                    val updated = existing.copy(lastAccessAt = System.currentTimeMillis())
                    writeMetadata(entryDir, updated)
                    log.info("Render input cache HIT inputId={} videoBytes={}", inputId, totalBytes)
                    return CachedInput(
                        inputId = inputId,
                        videoFile = File(entryDir, existing.videoFileName),
                        metadata = updated,
                        videoSizeBytes = totalBytes,
                    )
                }

                // Cache miss — promote temp to final location, write metadata.
                // 부분 상태 (entryDir 만 있고 metadata.json 누락) 는 다음 cleanExpired
                // 에 의해 expired 처리되지만, 같은 inputId 로 retry 시 isHit 분기에서
                // metadata 가 없어 IllegalStateException → 영구 stuck. 따라서 metadata
                // 쓰기 중 throw 시 entryDir 자체를 통째 삭제해 다음 시도가 깨끗한
                // MISS 로 진입하게 한다.
                var entryCreated = false
                try {
                    entryDir.mkdirs()
                    entryCreated = true
                    val finalVideo = File(entryDir, safeVideoName)
                    if (!tmpVideoFile.renameTo(finalVideo)) {
                        // Cross-FS rename can fail; fall back to copy+delete.
                        tmpVideoFile.copyTo(finalVideo, overwrite = true)
                        tmpVideoFile.delete()
                    }
                    val now = System.currentTimeMillis()
                    val meta = CacheMetadata(
                        inputId = inputId,
                        createdAt = now,
                        lastAccessAt = now,
                        videoFileName = safeVideoName,
                    )
                    writeMetadata(entryDir, meta)
                    log.info("Render input cache MISS->write inputId={} videoBytes={}", inputId, totalBytes)
                    return CachedInput(
                        inputId = inputId,
                        videoFile = finalVideo,
                        metadata = meta,
                        videoSizeBytes = totalBytes,
                    )
                } catch (e: Throwable) {
                    if (entryCreated) {
                        runCatching { entryDir.deleteRecursively() }
                    }
                    throw e
                }
            }
        } catch (e: Throwable) {
            runCatching { tmpVideoFile.delete() }
            throw e
        }
    }

    /**
     * Resolve a previously-cached entry. Bumps lastAccessAt on success.
     * Returns null when:
     * - inputId is malformed (path-traversal guard)
     * - directory or metadata.json missing
     * - entry past TTL (cleanup hasn't run yet, but treat as expired)
     *
     * Caller raises 4xx with a clear error so mobile can re-upload.
     */
    fun resolve(inputId: String): CachedInput? {
        if (!isValidInputId(inputId)) return null
        val entryDir = File(baseDir, inputId)
        if (!entryDir.exists() || !entryDir.isDirectory) return null

        synchronized(writeLock) {
            val meta = readMetadataOrNull(entryDir)
            val now = System.currentTimeMillis()
            if (meta == null || (now - meta.lastAccessAt > ttlMs)) {
                return null
            }

            val videoFile = File(entryDir, meta.videoFileName)
            if (!videoFile.exists()) {
                return null
            }

            val updated = meta.copy(lastAccessAt = now)
            writeMetadata(entryDir, updated)
            return CachedInput(
                inputId = inputId,
                videoFile = videoFile,
                metadata = updated,
                videoSizeBytes = videoFile.length(),
            )
        }
    }

    /**
     * Walk every entry directory; delete those where lastAccessAt + ttlMs <= now.
     * Robust against stray files at baseDir root (skip non-directories) and
     * partial entries (treat missing metadata as expired).
     */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        val entries = baseDir.listFiles { f -> f.isDirectory } ?: return
        synchronized(writeLock) {
            var removed = 0
            for (dir in entries) {
                val meta = readMetadataOrNull(dir)
                val expired = meta == null || (now - meta.lastAccessAt > ttlMs)
                if (expired) {
                    if (dir.deleteRecursively()) removed++
                }
            }
            // Stray *.tmp files from interrupted save() runs.
            baseDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach {
                runCatching { it.delete() }
            }
            if (removed > 0) log.info("Render input cache cleanup removed {} expired entries", removed)
        }
    }

    private fun readMetadataOrNull(entryDir: File): CacheMetadata? {
        val f = File(entryDir, METADATA_NAME)
        if (!f.exists()) return null
        return try {
            json.decodeFromString<CacheMetadata>(f.readText())
        } catch (e: Throwable) {
            log.warn("Failed to parse cache metadata at {}: {}", f.absolutePath, e.message)
            null
        }
    }

    private fun writeMetadata(entryDir: File, meta: CacheMetadata) {
        File(entryDir, METADATA_NAME).writeText(json.encodeToString(CacheMetadata.serializer(), meta))
    }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.take(120)
    }

    /** inputIds are 32-char lowercase hex (16-byte sha256 prefix) — strict to block path traversal. */
    private fun isValidInputId(id: String): Boolean = id.matches(Regex("^[0-9a-f]{32}$"))

    companion object {
        private const val METADATA_NAME = "metadata.json"
    }
}

@Serializable
data class CacheMetadata(
    val inputId: String,
    val createdAt: Long,
    val lastAccessAt: Long,
    val videoFileName: String,
)

data class CachedInput(
    val inputId: String,
    val videoFile: File,
    val metadata: CacheMetadata,
    val videoSizeBytes: Long,
)
