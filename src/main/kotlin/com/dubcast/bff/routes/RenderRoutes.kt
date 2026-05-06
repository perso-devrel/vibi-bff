package com.dubcast.bff.routes

import com.dubcast.bff.MAX_UPLOAD_FILE_SIZE
import com.dubcast.bff.model.RenderConfig
import com.dubcast.bff.model.RenderInputCacheResponse
import com.dubcast.bff.model.RenderResponse
import com.dubcast.bff.model.RenderStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.AudioPart
import com.dubcast.bff.service.DirectiveStem
import com.dubcast.bff.service.DirectiveWithStemFiles
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.RenderInputCacheService
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// Hot-path regex — moving from inline-`Regex(...)` (recompiled per directive)
// to top-level vals avoids O(directives) regex compilation per render submit.
// 자체 BFF URL: "/api/v2/separate/{jobId}/stem/{stemId}?token=..."
private val SEP_URL_REGEX = Regex("""(?:.*?)/api/v2/separate/([^/?#]+)/stem/([^/?#]+)\??(.*)$""")
private val MIX_URL_REGEX = Regex("""(?:.*?)/api/v2/separate/mix/([^/?#]+)/download\??(.*)$""")

fun Route.renderRoutes(
    renderService: RenderService,
    fileStorage: FileStorageService,
    stemMixService: StemMixService,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
    httpClient: HttpClient,
    inputCacheService: RenderInputCacheService,
) {
    route("/render") {
        // POST /api/v2/render/inputs — shared input cache. Mobile uploads the
        // source video (and optional segment audios) once; the response's
        // inputId can be reused across N variant renders without re-sending
        // the bytes. inputId is sha256(video)[:16 bytes hex] so the same
        // video resolves to the same slot on retry.
        post("/inputs") {
            val multipart = call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)

            // Buffer audios on disk first (multipart parser is single-pass; we
            // need the video stream to flow into sha256 BEFORE we know the id).
            // Audios are temp-buffered then handed to the cache service as
            // FileInputStreams that get cleaned up after save() completes.
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
                runCatching { vTemp.delete() }

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
                // The cache service consumed the audio streams (and closed them);
                // the underlying temp files are no longer needed.
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

            // separationDirectives: 각 stem 의 audioUrl 을 로컬 파일로 해석.
            // - BFF 자체 HMAC URL (`/api/v2/separate/{jobId}/stem/{stemId}?token=...`) 이면
            //   토큰 검증 후 SeparationService 의 LocalStem.file 직접 매핑 (다운로드 회피).
            // - 외부 URL (또는 검증 실패) 이면 임시 디렉터리에 다운로드.
            // 임시 다운로드 파일은 inputFilesToCleanup 에 포함시켜 잡 종료 시 삭제.
            val tempStemFiles = mutableListOf<File>()
            val resolvedDirectives = mutableListOf<DirectiveWithStemFiles>()
            try {
                for (directive in config.separationDirectives) {
                    val resolvedStems = mutableListOf<DirectiveStem>()
                    for (selection in directive.selections) {
                        val file = resolveStemUrlToFile(
                            audioUrl = selection.audioUrl,
                            separationService = separationService,
                            signedUrlService = signedUrlService,
                            fileStorage = fileStorage,
                            httpClient = httpClient,
                            tempStemFiles = tempStemFiles,
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
            } catch (e: Throwable) {
                // 부분 다운로드 실패 시 이미 받은 임시 파일 즉시 cleanup.
                tempStemFiles.forEach { runCatching { it.delete() } }
                throw e
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
            // 외부에서 다운로드한 stem 임시파일만 cleanup. SeparationService 내부 stem 파일은
            // SeparationService 가 자체 TTL 로 관리하므로 여기서 지우지 않는다.
            inputFiles.addAll(tempStemFiles)

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
 * 1) BFF 자체 separation/mix URL 패턴이면 SignedUrlService 검증 후 SeparationService /
 *    StemMixService 의 LocalStem.file 직접 사용 (디스크에서 다시 안 읽어도 됨).
 * 2) 그 외 URL (외부 호스트 또는 매핑 실패) 은 HttpClient 로 임시 파일에 stream 다운로드.
 *    cleanup 대상은 [tempStemFiles] 에 누적.
 *
 * 실패 시 throw — silent fallback 없음.
 */
private suspend fun resolveStemUrlToFile(
    audioUrl: String,
    separationService: SeparationService,
    signedUrlService: SignedUrlService,
    fileStorage: FileStorageService,
    httpClient: HttpClient,
    tempStemFiles: MutableList<File>,
): File {
    SEP_URL_REGEX.matchEntire(audioUrl)?.let { m ->
        val jobId = m.groupValues[1]
        val stemId = m.groupValues[2]
        val query = m.groupValues[3]
        val token = parseQueryParam(query, "token")
        if (token != null && signedUrlService.verify(jobId, stemId, token)) {
            val job = separationService.getJob(jobId)
            val stem = job?.stems?.firstOrNull { it.stemId == stemId }
            if (stem != null && stem.file.exists()) return stem.file
        }
        // 토큰 검증 실패 또는 stem 누락이면 — fallthrough 해서 외부 다운로드 시도. 같은
        // host 라도 BFF 가 다른 인스턴스라면 받을 수 있게.
    }

    MIX_URL_REGEX.matchEntire(audioUrl)?.let { _ ->
        // mix URL 로컬 매핑은 의도적으로 skip — render 가 자기 BFF 의 mix 를 끌어 쓰는
        // 케이스는 거의 없고, 굳이 끌 거면 외부 다운로드로도 동일 결과.
    }

    // 외부 URL (또는 자체 URL 인데 매핑 실패) — HttpClient 로 streaming download.
    val ext = audioUrl.substringAfterLast('.', "mp3").substringBefore('?').take(8)
        .takeIf { it.matches(Regex("[a-zA-Z0-9]+")) } ?: "mp3"
    val temp = File(fileStorage.renderDir, "stem-${UUID.randomUUID()}.$ext")
    httpClient.prepareGet(audioUrl).execute { response ->
        if (!response.status.isSuccess()) {
            throw IllegalArgumentException(
                "Stem download failed: status=${response.status.value} url=$audioUrl"
            )
        }
        val channel = response.bodyAsChannel()
        temp.outputStream().use { out ->
            val buf = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
        }
    }
    if (!temp.exists() || temp.length() == 0L) {
        runCatching { temp.delete() }
        throw IllegalArgumentException("Stem download produced empty file: url=$audioUrl")
    }
    tempStemFiles.add(temp)
    return temp
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
