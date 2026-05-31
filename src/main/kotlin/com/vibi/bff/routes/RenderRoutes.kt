package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.model.BgmClip
import com.vibi.bff.model.RenderConfig
import com.vibi.bff.model.RenderConfigV3
import com.vibi.bff.model.RenderInputCacheResponse
import com.vibi.bff.model.RenderResponse
import com.vibi.bff.model.RenderStatusResponse
import com.vibi.bff.model.Segment
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.DirectiveStem
import com.vibi.bff.service.DirectiveWithStemFiles
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.RenderInputCacheService
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Hot-path regex — moving from inline-`Regex(...)` (recompiled per directive)
// to top-level vals avoids O(directives) regex compilation per render submit.
// 자체 BFF URL: "/api/v2/separate/{jobId}/stem/{stemId}?token=..."
private val SEP_URL_REGEX = Regex("""(?:.*?)/api/v2/separate/([^/?#]+)/stem/([^/?#]+)\??(.*)$""")

// v3 렌더의 R2 asset 병렬 다운로드 동시 한도. 다운로드는 디스크 스트리밍이라 메모리 부담은 없으나
// R2 커넥션/소켓 폭주를 막기 위해 상한을 둔다. 대부분 렌더는 에셋 1~3개라 4면 충분.
private const val MAX_PARALLEL_ASSET_DOWNLOADS = 4

fun Route.renderRoutes(
    renderService: RenderService,
    fileStorage: FileStorageService,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
    inputCacheService: RenderInputCacheService,
    objectStore: ObjectStore?,
    /** JWT 검증용 — null 이면 인증 강제 안 함 (테스트 호환). 운영에선 항상 주입. */
    jwtSecret: String? = null,
) {
    route("/render") {
        // POST /api/v2/render/inputs — shared input cache. Mobile uploads the
        // source video once; the response's inputId can be reused across N
        // variant renders without re-sending the bytes. inputId is
        // sha256(video)[:16 bytes hex] so the same video resolves to the same
        // slot on retry.
        post("/inputs") {
            // 인증 강제 + cache entry 의 ownerUserId 바인딩 — 같은 sha256 의 슬롯을
            // 다른 user 가 resolve 해 cross-account content IDOR 으로 이어지는 회귀 차단.
            // legacy (ownerUserId null) entry 는 다음 인증 caller hit 시 박힘 — 점진 마이그레이션.
            val principal = jwtSecret?.let { call.requireUser(it) }
            val multipart = call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)

            var videoFileName: String? = null
            var videoTempFile: File? = null

            try {
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            if (part.name == "video") {
                                if (videoTempFile != null) {
                                    throw IllegalArgumentException(
                                        "Multiple 'video' parts in /render/inputs"
                                    )
                                }
                                videoFileName = part.originalFileName ?: "video.mp4"
                                val tmp = File(
                                    inputCacheService.baseDir,
                                    "incoming-video-${UUID.randomUUID()}.tmp"
                                )
                                @Suppress("DEPRECATION")
                                part.streamProvider().use { input ->
                                    tmp.outputStream().use { input.copyTo(it) }
                                }
                                videoTempFile = tmp
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                val vTemp = videoTempFile
                    ?: throw IllegalArgumentException("video part is required for /render/inputs")
                val vName = videoFileName ?: "video.mp4"

                val cached = inputCacheService.save(
                    videoFileName = vName,
                    videoStream = vTemp.inputStream(),
                    maxVideoBytes = MAX_UPLOAD_FILE_SIZE,
                    ownerUserId = principal?.userId,
                )

                call.respond(
                    HttpStatusCode.OK,
                    RenderInputCacheResponse(
                        inputId = cached.inputId,
                        expiresAt = cached.metadata.lastAccessAt + inputCacheService.ttlMillis,
                        videoSizeBytes = cached.videoFile.length(),
                    ),
                )
            } finally {
                // cache.save 가 이미 video temp 를 final 위치로 이동/복사하지만,
                // throw 시 leftover 방지 위해 finally 에서도 한 번 정리.
                runCatching { videoTempFile?.delete() }
            }
        }

        post {
            // 사용자 귀속 — admin 대시보드의 "사용자별 사용량" 집계 source. jwtSecret null 이면 분석 skip.
            val principal = jwtSecret?.let { call.requireUser(it) }
            // Ktor 3.x default formFieldLimit 50MB → 70MB+ 영상 multipart 거부됨. 500MB 까지 허용.
            val multipart = call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)

            var legacyVideoFile: File? = null
            val videoFiles = mutableMapOf<String, File>()
            val bgmAudioFiles = mutableMapOf<String, File>()
            var renderConfig: RenderConfig? = null
            var inputId: String? = null

            fun saveFile(part: PartData.FileItem, defaultName: String): File {
                @Suppress("DEPRECATION")
                val blobPath = fileStorage.saveUpload(
                    part.originalFileName ?: defaultName,
                    part.streamProvider(),
                    MAX_UPLOAD_FILE_SIZE,
                )
                return fileStorage.getUploadFile(blobPath)
            }

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val name = part.name ?: ""
                        when {
                            name == "video" ->
                                legacyVideoFile = saveFile(part, "video.mp4")
                            name.startsWith("video_") ->
                                videoFiles[name] = saveFile(part, "$name.mp4")
                            name.startsWith("bgm_") ->
                                bgmAudioFiles[name] = saveFile(part, "$name.mp3")
                        }
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            // AppJson — ignoreUnknownKeys=true. 모바일이 절단된 옛 필드
                            // (dubClips/imageClips/audioOverrideKey/outputLanguageCode 등) 를
                            // 계속 보내도 strict Json 처럼 SerializationException 으로 폭사하지 않게.
                            "config" -> renderConfig = AppJson.decodeFromString<RenderConfig>(part.value)
                            "inputId" -> inputId = part.value.trim().ifBlank { null }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val config = renderConfig ?: throw IllegalArgumentException("config is required")

            // Resolve inputId BEFORE other validation — when present, the cache
            // contributes the video. Per spec, inputId + multipart video together
            // = inputId wins (multipart video bytes were already consumed but we
            // discard the resolved upload by not adding it to the legacy slot).
            //
            // Cache miss / expired must throw — silent fallback would let the
            // mobile think it's saving bytes when really the server can't
            // find anything.
            if (inputId != null) {
                // resolve 가 principal.userId 와 entry.ownerUserId 매칭 — 다른 user 의
                // cache 슬롯에 대한 hit 은 null 반환으로 not-found (existence oracle 차단).
                val cached = inputCacheService.resolve(inputId!!, principal?.userId)
                    ?: throw IllegalArgumentException("inputId expired or not found")
                // Cache video → legacy slot (single-video render path).
                legacyVideoFile = cached.videoFile
            }

            for (clip in config.bgmClips) {
                require(bgmAudioFiles.containsKey(clip.audioFileKey)) {
                    "BGM audio file missing for key: ${clip.audioFileKey}"
                }
            }

            // separationDirectives: 각 stem 의 audioUrl 은 BFF 자체 HMAC URL 만 허용.
            // SignedUrlService 검증 후 SeparationService 의 LocalStem.file 직접 매핑.
            // 외부 URL fallback 다운로드는 SSRF 위험으로 폐기 (resolveStemUrlToFile 참조).
            val resolvedDirectives = mutableListOf<DirectiveWithStemFiles>()
            for (directive in config.separationDirectives) {
                val resolvedStems = mutableListOf<DirectiveStem>()
                for (selection in directive.selections) {
                    val file = resolveStemUrlToFile(
                        audioUrl = selection.audioUrl,
                        separationService = separationService,
                        signedUrlService = signedUrlService,
                        callerUserId = principal?.userId,
                    )
                    resolvedStems.add(DirectiveStem(file = file, volume = selection.volume))
                }
                if (resolvedStems.isNotEmpty() || directive.muteOriginalSegmentAudio) {
                    resolvedDirectives.add(
                        DirectiveWithStemFiles(
                            rangeStartMs = directive.rangeStartMs,
                            rangeEndMs = directive.rangeEndMs,
                            muteOriginalSegmentAudio = directive.muteOriginalSegmentAudio,
                            stems = resolvedStems,
                            sourceOffsetMs = directive.sourceOffsetMs,
                        )
                    )
                }
            }

            // inputFilesToCleanup tracks PER-REQUEST temp uploads. Cache-resident
            // files (videoFile from inputCache) live under the shared cache
            // directory and MUST NOT be deleted on job completion — other
            // variants are still using them.
            val cacheResidentFiles: Set<File> = if (inputId != null) {
                buildSet { legacyVideoFile?.let { add(it) } }
            } else emptySet()

            val inputFiles = mutableListOf<File>()
            legacyVideoFile?.let { if (it !in cacheResidentFiles) inputFiles.add(it) }
            inputFiles.addAll(videoFiles.values)
            inputFiles.addAll(bgmAudioFiles.values)
            // SeparationService 가 stem 파일의 owner — 자체 TTL 로 관리하므로 여기 cleanup
            // 대상에 포함하지 않음 (다른 동시 render job 이 같은 stem 사용 가능).

            val jobId = renderService.submitRender(
                legacyVideoFile = legacyVideoFile,
                videoFiles = videoFiles,
                bgmAudioFiles = bgmAudioFiles,
                bgmClips = config.bgmClips,
                videoDurationMs = config.videoDurationMs,
                segments = config.segments,
                separationDirectives = resolvedDirectives,
                inputFilesToCleanup = inputFiles,
                outputKind = config.outputKind,
                quality = config.quality,
                userId = principal?.userId,
                sourceDurationMs = computeRenderSourceDurationMs(config),
            )

            call.respond(HttpStatusCode.OK, RenderResponse(jobId = jobId))
        }

        // v3 — asset-by-reference. 모바일이 R2 에 사전 PUT 한 segment 영상/BGM 의 키만 JSON
        // 으로 전송. BFF 가 R2 에서 다운로드 후 ffmpeg. Cloud Run body 한도 회피 + 재편집 시
        // 재업로드 제거 + BGM dedup 자동 해결.
        post("/v3") {
            val principal = jwtSecret?.let { call.requireUser(it) }
            if (objectStore == null) {
                throw ApiErrorException(
                    HttpStatusCode.ServiceUnavailable,
                    errorCode = "r2_disabled",
                    detail = "v3 render path requires R2",
                )
            }
            val config = call.receive<RenderConfigV3>()

            // R2 → 로컬 asset 캐시 다운로드 (멱등). 같은 인스턴스에서 같은 키는 다운로드 skip.
            // 세그먼트 영상 + BGM 오디오를 병렬 다운로드 — downloadIfAbsent 는 디스크 스트리밍이라
            // RAM 부담 없고, 순차 루프 대비 멀티에셋 렌더의 다운로드 대기를 단축. 동시 커넥션 폭주를
            // 막기 위해 Semaphore 로 제한. 결과는 awaitAll 후 단일 스레드에서 맵에 채워 동시쓰기 회피.
            val videoFiles = mutableMapOf<String, File>()
            val bgmAudioFiles = mutableMapOf<String, File>()
            withContext(Dispatchers.IO) {
                val gate = Semaphore(MAX_PARALLEL_ASSET_DOWNLOADS)
                suspend fun fetch(key: String): Pair<String, File> {
                    val local = File(fileStorage.assetCacheDir, key.substringAfterLast('/'))
                    gate.withPermit { objectStore.downloadIfAbsent(key, local) }
                    return key to local
                }
                coroutineScope {
                    val videoJobs = config.segments.map { it.sourceAssetKey }.distinct()
                        .map { key -> async { fetch(key) } }
                    val bgmJobs = config.bgmClips.map { it.audioAssetKey }.distinct()
                        .map { key -> async { fetch(key) } }
                    videoJobs.awaitAll().forEach { (k, f) -> videoFiles[k] = f }
                    bgmJobs.awaitAll().forEach { (k, f) -> bgmAudioFiles[k] = f }
                }
            }

            // v3 → v2 매핑. sourceFileKey 에 assetKey 그대로 사용해 videoFiles/bgmAudioFiles
            // 맵 key 와 정합. 기존 RenderService.submitRender 시그니처 재사용.
            val segments = config.segments.map { s ->
                Segment(
                    sourceFileKey = s.sourceAssetKey,
                    order = s.order,
                    durationMs = s.durationMs,
                    trimStartMs = s.trimStartMs,
                    trimEndMs = s.trimEndMs,
                    volumeScale = s.volumeScale,
                    speedScale = s.speedScale,
                )
            }
            val bgmClips = config.bgmClips.map { c ->
                BgmClip(
                    audioFileKey = c.audioAssetKey,
                    startMs = c.startMs,
                    volume = c.volume,
                    sourceTrimStartMs = c.sourceTrimStartMs,
                    sourceTrimEndMs = c.sourceTrimEndMs,
                )
            }

            val resolvedDirectives = mutableListOf<DirectiveWithStemFiles>()
            for (directive in config.separationDirectives) {
                val resolvedStems = mutableListOf<DirectiveStem>()
                for (selection in directive.selections) {
                    val file = resolveStemUrlToFile(
                        audioUrl = selection.audioUrl,
                        separationService = separationService,
                        signedUrlService = signedUrlService,
                        callerUserId = principal?.userId,
                    )
                    resolvedStems.add(DirectiveStem(file = file, volume = selection.volume))
                }
                if (resolvedStems.isNotEmpty() || directive.muteOriginalSegmentAudio) {
                    resolvedDirectives.add(
                        DirectiveWithStemFiles(
                            rangeStartMs = directive.rangeStartMs,
                            rangeEndMs = directive.rangeEndMs,
                            muteOriginalSegmentAudio = directive.muteOriginalSegmentAudio,
                            stems = resolvedStems,
                            sourceOffsetMs = directive.sourceOffsetMs,
                        )
                    )
                }
            }

            // asset cache 의 파일은 모든 job 의 공유 소유물 — 다른 동시 render 가 같은 키
            // 재사용 가능하므로 job 완료 시 cleanup 대상에서 제외. assetCacheDir TTL sweep
            // 가 별도로 회수.
            val jobId = renderService.submitRender(
                legacyVideoFile = null,
                videoFiles = videoFiles,
                bgmAudioFiles = bgmAudioFiles,
                bgmClips = bgmClips,
                videoDurationMs = null,
                segments = segments,
                separationDirectives = resolvedDirectives,
                inputFilesToCleanup = emptyList(),
                outputKind = config.outputKind,
                quality = config.quality,
                userId = principal?.userId,
                sourceDurationMs = computeRenderSourceDurationMsV3(config),
            )
            call.respond(HttpStatusCode.OK, RenderResponse(jobId = jobId))
        }

        get("/{jobId}/status") {
            val principal = jwtSecret?.let { call.requireUser(it) }
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Render jobId required")
            val job = renderService.getJob(jobId)
                ?: throw NotFoundException("Render job not found: $jobId")

            // owner 가 set 된 잡은 caller 도 반드시 매칭. caller null 이어도 reject —
            // jwtSecret 미주입 분기에서 owned-job 으로 fall-through 되는 회귀 차단.
            // owner null (boot/테스트 시드) 인 잡만 caller 무관 통과.
            if (job.ownerUserId != null && job.ownerUserId != principal?.userId) {
                throw NotFoundException("Render job not found: $jobId")
            }

            call.respond(HttpStatusCode.OK, RenderStatusResponse(
                jobId = job.jobId,
                status = job.status,
                progress = job.progress,
                error = job.error,
                progressReason = job.progressReason,
            ))
        }

        get("/{jobId}/download") {
            val principal = jwtSecret?.let { call.requireUser(it) }
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Render jobId required")
            val job = renderService.getJob(jobId)
                ?: throw NotFoundException("Render job not found: $jobId")

            // 자원 소유권 — owner 가 set 된 잡은 caller 도 반드시 매칭. caller null 이어도
            // reject 해 jwtSecret 미주입 분기에서 owned-job 으로 fall-through 되는 회귀 차단.
            // owner null (boot/테스트 시드) 인 잡만 caller 무관 통과. mismatch 는 not-found
            // 와 동일 메시지로 응답해 IDOR existence oracle 차단.
            if (job.ownerUserId != null && job.ownerUserId != principal?.userId) {
                throw NotFoundException("Render job not found: $jobId")
            }

            if (job.status != "COMPLETED") {
                throw NotFoundException("Render not ready: status=${job.status}")
            }
            // R2 backend 가 있으면 outputFile 부재도 통과 — uploadIfAbsent 가 R2 hit 확인 후
            // presigned URL 로 redirect. Cloud Run idle scale-down 후 또는 TTL cleanup 후
            // 로컬 file 만 사라진 케이스에서도 다운로드 유지. R2 도 없고 file 도 없으면
            // uploadIfAbsent 가 IllegalStateException → 500 (운영상 R2 lifecycle 만료된 옛 잡).
            if (objectStore == null && !job.outputFile.exists()) {
                throw NotFoundException("Render output missing: $jobId")
            }

            val ext = job.outputFile.extension.ifBlank { "mp4" }
            val fileName = "$jobId.$ext"
            call.respondDownload(
                file = job.outputFile,
                objectKey = ObjectKey.renderOutput(jobId, fileName),
                contentType = contentTypeForExtension(ext, ContentType.Application.OctetStream),
                downloadFilename = fileName,
                store = objectStore,
            )
        }
    }
}

/**
 * stem audioUrl 을 로컬 파일로 해석.
 *
 * **BFF 자체 HMAC URL 만 허용** — `/api/v2/separate/{jobId}/stem/{stemId}?token=...` 패턴 외에는
 * 무조건 reject. 외부 다운로드 분기는 SSRF 위험 (attacker 가 임의 URL 을 stem 으로 보내 BFF
 * 가 internal 네트워크 / metadata 서비스 / 사설 IP 로 GET 하게 만들 수 있음) 으로 폐기.
 *
 * 멀티 인스턴스 BFF 에서 다른 인스턴스의 stem 을 가져와야 하는 시나리오는 현재 없음
 * (separation/mix 산출물은 origin 인스턴스의 로컬 디스크에만 존재). 추후 필요해지면
 * 별도 internal API + 화이트리스트로 재설계.
 */
private suspend fun resolveStemUrlToFile(
    audioUrl: String,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
    callerUserId: UUID? = null,
): File {
    val match = SEP_URL_REGEX.matchEntire(audioUrl)
        ?: throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "stem audioUrl must match /api/v2/separate/{jobId}/stem/{stemId}",
        )
    val jobId = match.groupValues[1]
    val stemId = match.groupValues[2]
    val query = match.groupValues[3]
    val token = parseQueryParam(query, "token")
        ?: throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "missing token query param",
        )
    if (!signedUrlService.verify(jobId, stemId, token)) {
        throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "token verification failed",
        )
    }
    val job = separationService.getJob(jobId)
        ?: throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "separation job not found: $jobId",
        )
    // capability(HMAC token) + identity(render caller) 이중. owner 가 set 된 stem
    // 잡은 caller 도 반드시 매칭 — caller null (jwtSecret 미주입 분기) 도 reject 해
    // sibling status/download 와 동일 패턴 (`owner != null && owner != caller`). leaked
    // HMAC URL 이 다른 user 의 render 에 끼워지는 IDOR 차단.
    if (job.ownerUserId != null && job.ownerUserId != callerUserId) {
        throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "stem job ownership mismatch",
        )
    }
    val stem = job.stems.firstOrNull { it.stemId == stemId }
        ?: throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "stem not found: $stemId",
        )
    if (!stem.file.exists()) {
        throw ApiErrorException(
            HttpStatusCode.BadRequest,
            errorCode = "invalid_stem_url",
            detail = "stem file expired or missing: $stemId",
        )
    }
    return stem.file
}

private fun parseQueryParam(query: String, key: String): String? {
    if (query.isBlank()) return null
    val q = query.removePrefix("?")
    for (pair in q.split("&")) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == key) return pair.substring(eq + 1)
    }
    return null
}

/**
 * admin 대시보드의 "영상 길이" KPI 원본. 사용자가 올린 입력 영상의 총 길이 ms.
 * segments 있으면 각 segment 의 trim 윈도우 합산, 없으면 legacy videoDurationMs.
 * speedScale 은 입력 길이와 무관하므로 적용 안 함 (사용자가 올린 raw 분량 기준).
 */
internal fun computeRenderSourceDurationMs(config: com.vibi.bff.model.RenderConfig): Long {
    val segs = config.segments
    if (!segs.isNullOrEmpty()) {
        return segs.sumOf { seg ->
            val ts = seg.trimStartMs ?: 0L
            val te = seg.trimEndMs?.takeIf { it > 0L } ?: seg.durationMs
            (te - ts).coerceAtLeast(0L)
        }
    }
    return (config.videoDurationMs ?: 0L).coerceAtLeast(0L)
}

internal fun computeRenderSourceDurationMsV3(config: RenderConfigV3): Long =
    config.segments.sumOf { seg ->
        val ts = seg.trimStartMs ?: 0L
        val te = seg.trimEndMs?.takeIf { it > 0L } ?: seg.durationMs
        (te - ts).coerceAtLeast(0L)
    }
