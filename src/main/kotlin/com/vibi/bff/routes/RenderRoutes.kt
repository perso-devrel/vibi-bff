package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.model.RenderConfig
import com.vibi.bff.model.RenderInputCacheResponse
import com.vibi.bff.model.RenderResponse
import com.vibi.bff.model.RenderStatusResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.service.AudioPart
import com.vibi.bff.service.DirectiveStem
import com.vibi.bff.service.DirectiveWithStemFiles
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.RenderInputCacheService
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.StemMixService
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// Hot-path regex — moving from inline-`Regex(...)` (recompiled per directive)
// to top-level vals avoids O(directives) regex compilation per render submit.
// 자체 BFF URL: "/api/v2/separate/{jobId}/stem/{stemId}?token=..."
private val SEP_URL_REGEX = Regex("""(?:.*?)/api/v2/separate/([^/?#]+)/stem/([^/?#]+)\??(.*)$""")

fun Route.renderRoutes(
    renderService: RenderService,
    fileStorage: FileStorageService,
    @Suppress("UNUSED_PARAMETER") stemMixService: StemMixService,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
    @Suppress("UNUSED_PARAMETER") httpClient: HttpClient,
    inputCacheService: RenderInputCacheService,
) {
    route("/render") {
        // POST /api/v2/render/inputs — shared input cache. Mobile uploads the
        // source video (and optional segment audios) once; the response's
        // inputId can be reused across N variant renders without re-sending
        // the bytes. inputId is sha256(video)[:16 bytes hex] so the same
        // video resolves to the same slot on retry.
        //
        // multipart 는 single-pass stream 이라 video 가 먼저/뒤 어디 오든 모든 part 가
        // 디스크에 떨어진 후에야 cache.save 가 호출 가능. video/audio 둘 다 임시 파일로
        // buffer → forEachPart 종료 후 cache.save 한 번 호출 (하나라도 throw 시 finally
        // 에서 모든 temp 정리). audio temp 는 cache.save 가 stream 으로 consume.
        post("/inputs") {
            val multipart = call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)

            val tempAudioFiles = mutableListOf<File>()
            val audioParts = mutableListOf<AudioPart>()
            var videoFileName: String? = null
            var videoTempFile: File? = null

            try {
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val name = part.name ?: ""
                            when {
                                name == "video" -> {
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
                                name.startsWith("audio") || name.startsWith("audio_") -> {
                                    val tmp = File(
                                        inputCacheService.baseDir,
                                        "incoming-audio-${UUID.randomUUID()}.tmp"
                                    )
                                    @Suppress("DEPRECATION")
                                    part.streamProvider().use { input ->
                                        tmp.outputStream().use { input.copyTo(it) }
                                    }
                                    tempAudioFiles.add(tmp)
                                    audioParts.add(
                                        AudioPart(
                                            formFieldName = name,
                                            originalFileName = part.originalFileName ?: "$name.mp3",
                                            stream = tmp.inputStream(),
                                        )
                                    )
                                }
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
                    audios = audioParts,
                    maxVideoBytes = MAX_UPLOAD_FILE_SIZE,
                )

                call.respond(
                    HttpStatusCode.OK,
                    RenderInputCacheResponse(
                        inputId = cached.inputId,
                        expiresAt = cached.metadata.lastAccessAt + inputCacheService.ttlMillis,
                        videoSizeBytes = cached.videoFile.length(),
                        audioCount = cached.audioFilesByFormField.size,
                    ),
                )
            } finally {
                // cache.save 가 audio stream 을 consume 후 close. video temp 도 더 이상
                // 필요 없음 (cache.save 안에서 별도 final 위치로 이동/복사). throw 발생
                // 시에도 동일하게 정리해야 누수 없음.
                tempAudioFiles.forEach { runCatching { it.delete() } }
                runCatching { videoTempFile?.delete() }
            }
        }

        post {
            // Ktor 3.x default formFieldLimit 50MB → 70MB+ 영상 multipart 거부됨. 500MB 까지 허용.
            val multipart = call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)

            var legacyVideoFile: File? = null
            val videoFiles = mutableMapOf<String, File>()
            val audioFiles = mutableMapOf<String, File>()
            val bgmAudioFiles = mutableMapOf<String, File>()
            val imageFiles = mutableMapOf<String, File>()
            val segmentImageFiles = mutableMapOf<String, File>()
            val overrideAudioFiles = mutableMapOf<String, File>()
            var subtitlesFile: File? = null
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
                            name.startsWith("audio_override") ->
                                overrideAudioFiles[name] = saveFile(part, "$name.mp3")
                            name.startsWith("audio_") ->
                                audioFiles[name] = saveFile(part, "$name.mp3")
                            name.startsWith("bgm_") ->
                                bgmAudioFiles[name] = saveFile(part, "$name.mp3")
                            name.startsWith("segment_image_") ->
                                segmentImageFiles[name] = saveFile(part, "$name.jpg")
                            name.startsWith("image_") ->
                                imageFiles[name] = saveFile(part, "$name.jpg")
                            name == "subtitles" ->
                                subtitlesFile = saveFile(part, "subtitles.ass")
                        }
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            "config" -> renderConfig = Json.decodeFromString<RenderConfig>(part.value)
                            "inputId" -> inputId = part.value.trim().ifBlank { null }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val config = renderConfig ?: throw IllegalArgumentException("config is required")

            // Resolve inputId BEFORE validating audio keys — when present, the
            // cache contributes the video and any segment audios. Per spec,
            // inputId + multipart video together = inputId wins (multipart
            // video bytes were already consumed but we discard the resolved
            // upload by not adding it to the legacy slot).
            //
            // Cache miss / expired must throw — silent fallback would let the
            // mobile think it's saving bytes when really the server can't
            // find anything.
            if (inputId != null) {
                val cached = inputCacheService.resolve(inputId!!)
                    ?: throw IllegalArgumentException("inputId expired or not found")
                // Cache video → legacy slot (single-video render path).
                legacyVideoFile = cached.videoFile
                // Merge audios — cache audios fill any audio_* keys NOT already
                // sent inline (multipart inline takes priority for per-variant
                // overrides). audioFiles map uses form-field name as key.
                for ((field, file) in cached.audioFilesByFormField) {
                    if (field !in audioFiles) {
                        audioFiles[field] = file
                    }
                }
            }

            // Validate audio keys
            for (clip in config.dubClips) {
                require(audioFiles.containsKey(clip.audioFileKey)) {
                    "Audio file missing for key: ${clip.audioFileKey}"
                }
            }
            for (clip in config.bgmClips) {
                require(bgmAudioFiles.containsKey(clip.audioFileKey)) {
                    "BGM audio file missing for key: ${clip.audioFileKey}"
                }
            }
            // Validate image keys
            for (clip in config.imageClips) {
                require(imageFiles.containsKey(clip.imageFileKey)) {
                    "Image file missing for key: ${clip.imageFileKey}"
                }
            }

            val audioOverrideFile = config.audioOverrideKey?.let { key ->
                overrideAudioFiles[key]
                    ?: throw IllegalArgumentException("audio_override file missing for key: $key")
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
                        )
                    )
                }
            }

            // inputFilesToCleanup tracks PER-REQUEST temp uploads. Cache-resident
            // files (videoFile + cached.audioFilesByFormField) live under the
            // shared cache directory and MUST NOT be deleted on job completion
            // — other variants are still using them.
            val cacheResidentFiles: Set<File> = if (inputId != null) {
                buildSet {
                    legacyVideoFile?.let { add(it) }
                    addAll(audioFiles.values.filter { it.parentFile?.name == "audios" })
                }
            } else emptySet()

            val inputFiles = mutableListOf<File>()
            legacyVideoFile?.let { if (it !in cacheResidentFiles) inputFiles.add(it) }
            inputFiles.addAll(videoFiles.values)
            inputFiles.addAll(audioFiles.values.filter { it !in cacheResidentFiles })
            inputFiles.addAll(bgmAudioFiles.values)
            inputFiles.addAll(imageFiles.values)
            inputFiles.addAll(segmentImageFiles.values)
            inputFiles.addAll(overrideAudioFiles.values)
            subtitlesFile?.let { inputFiles.add(it) }
            // SeparationService 가 stem 파일의 owner — 자체 TTL 로 관리하므로 여기 cleanup
            // 대상에 포함하지 않음 (다른 동시 render job 이 같은 stem 사용 가능).

            val jobId = renderService.submitRender(
                legacyVideoFile = legacyVideoFile,
                videoFiles = videoFiles,
                segmentImageFiles = segmentImageFiles,
                audioFiles = audioFiles,
                bgmAudioFiles = bgmAudioFiles,
                imageFiles = imageFiles,
                subtitlesFile = subtitlesFile,
                dubClips = config.dubClips,
                bgmClips = config.bgmClips,
                imageClips = config.imageClips,
                videoDurationMs = config.videoDurationMs,
                segments = config.segments,
                frame = config.frame,
                audioOverrideFile = audioOverrideFile,
                separationDirectives = resolvedDirectives,
                inputFilesToCleanup = inputFiles,
                outputKind = config.outputKind,
            )

            call.respond(HttpStatusCode.OK, RenderResponse(jobId = jobId))
        }

        get("/{jobId}/status") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Render jobId required")
            val job = renderService.getJob(jobId)
                ?: throw NotFoundException("Render job not found: $jobId")

            call.respond(HttpStatusCode.OK, RenderStatusResponse(
                jobId = job.jobId,
                status = job.status,
                progress = job.progress,
                error = job.error,
                progressReason = job.progressReason,
            ))
        }

        get("/{jobId}/download") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Render jobId required")
            val job = renderService.getJob(jobId)
                ?: throw NotFoundException("Render job not found: $jobId")

            if (job.status != "COMPLETED" || !job.outputFile.exists()) {
                throw NotFoundException("Render not ready: status=${job.status}")
            }

            // outputFile 의 실제 확장자에 따라 Content-Disposition 의 filename
            // 과 Content-Type 결정. audio 모드 (.m4a) / video 모드 (.mp4) 모두 커버.
            // respondFile 호출 직후엔 이미 응답이 시작되므로 모든 헤더는 그 전에.
            val fileName = "$jobId.${job.outputFile.extension.ifBlank { "mp4" }}"
            val contentType = when (job.outputFile.extension.lowercase()) {
                "m4a", "mp4a" -> ContentType("audio", "mp4")
                "mp3" -> ContentType("audio", "mpeg")
                "wav" -> ContentType("audio", "wav")
                "mp4" -> ContentType("video", "mp4")
                else -> ContentType.Application.OctetStream
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, fileName
                ).toString()
            )
            call.response.header(HttpHeaders.ContentType, contentType.toString())
            call.respondFile(job.outputFile)
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
private fun resolveStemUrlToFile(
    audioUrl: String,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
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
