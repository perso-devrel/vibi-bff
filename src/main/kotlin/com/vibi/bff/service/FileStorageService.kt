package com.vibi.bff.service

import com.vibi.bff.config.StorageConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.UUID

class FileStorageService(private val config: StorageConfig) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val baseDir get() = File(config.basePath)
    private val uploadsDir get() = File(baseDir, "uploads")
    val renderDir get() = File(baseDir, "render")
    val separationDir get() = File(baseDir, "separation")

    /**
     * v3 asset-by-reference 흐름의 로컬 캐시 — R2 에서 다운로드한 segment 영상/BGM 을 보관.
     * 같은 인스턴스가 같은 키를 두 번 다운로드 안 하기 위함. ObjectStore.downloadIfAbsent
     * 이 마지막 access time 을 갱신하지 않으므로, [sweepAssetCacheOlderThan] 가 cron 으로 정리.
     */
    val assetCacheDir get() = File(baseDir, "asset-cache")

    init {
        uploadsDir.mkdirs()
        renderDir.mkdirs()
        separationDir.mkdirs()
        File(separationDir, "mix").mkdirs()
        assetCacheDir.mkdirs()
        log.info("Storage initialized at {}", baseDir.absolutePath)
    }

    /**
     * asset-cache 디렉터리에서 [olderThanMs] ms 이상 미수정 파일 제거. Application.kt 의
     * 백그라운드 sweeper 가 1h 주기로 호출하는 단순 GC.
     */
    fun sweepAssetCacheOlderThan(olderThanMs: Long): Int {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val files = assetCacheDir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.isFile && f.lastModified() < cutoff) {
                if (f.delete()) removed++
            }
        }
        if (removed > 0) log.info("Asset cache sweep removed {} files (older than {}ms)", removed, olderThanMs)
        return removed
    }

    fun saveUpload(fileName: String, inputStream: InputStream, maxSize: Long = Long.MAX_VALUE): String {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val blobName = "${UUID.randomUUID()}_$safeName"
        val target = File(uploadsDir, blobName)

        inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if (totalBytes > maxSize) {
                        output.close()
                        target.delete()
                        throw IllegalArgumentException("File exceeds maximum size of ${maxSize / 1024 / 1024}MB")
                    }
                    output.write(buffer, 0, bytesRead)
                }
            }
        }

        val blobPath = "uploads/$blobName"
        log.info("Saved upload: {} ({} bytes)", blobPath, target.length())
        return blobPath
    }

    fun getUploadFile(blobPath: String): File {
        val file = File(baseDir, blobPath)
        require(file.canonicalPath.startsWith(baseDir.canonicalPath)) {
            "Invalid file path: $blobPath"
        }
        require(file.exists()) { "Upload file not found: $blobPath" }
        return file
    }

}
