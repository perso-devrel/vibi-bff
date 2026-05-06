package com.dubcast.bff.routes

import com.dubcast.bff.MAX_UPLOAD_FILE_SIZE
import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.SubtitleJobResponse
import com.dubcast.bff.model.SubtitleSpec
import com.dubcast.bff.model.SubtitleStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaSourceResolver
import com.dubcast.bff.service.SignedUrlService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.subtitleRoutes(
    subtitleService: AutoSubtitleService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
    mediaSourceResolver: MediaSourceResolver,
) {
    route("/subtitles") {
        // POST /api/v2/subtitles — submit
        // Accepts either multipart `file` (legacy) or `spec.editedRenderJobId`
        // referencing a completed /api/v2/render output. See MediaSourceResolver.
        post {
            val (filePart, specOpt) = parseOptionalUploadAndSpec<SubtitleSpec>(
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE),
                fileStorage,
                MAX_UPLOAD_FILE_SIZE,
            )
            val spec = specOpt ?: run {
                filePart?.delete()
                throw IllegalArgumentException("spec is required")
            }
            val source = mediaSourceResolver.resolve(filePart, spec.editedRenderJobId)
            val jobId = subtitleService.submit(source, spec)
            call.respond(HttpStatusCode.Accepted, SubtitleJobResponse(jobId = jobId))
        }

        // POST /api/v2/subtitles/regenerate — 사용자가 수정한 SRT 를 source 로 다른 언어 자막 재생성.
        // multipart: file=<edited_srt_bytes>, spec=<SubtitleSpec JSON {sourceLanguageCode, targetLanguageCodes, mediaType}>.
        // mediaType 은 무시됨 (Perso STT 안 씀). 응답 폴링/다운로드는 기존 /subtitles/{jobId} 라우트 그대로.
        post("/regenerate") {
            val (file, spec) = parseUploadAndSpec<SubtitleSpec>(
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE), fileStorage, MAX_UPLOAD_FILE_SIZE,
                defaultFileName = "edited.srt",
            )
            val jobId = subtitleService.submitRegenerate(file, spec)
            call.respond(HttpStatusCode.Accepted, SubtitleJobResponse(jobId = jobId))
        }

        // GET /api/v2/subtitles/{jobId} — status (+ signed URLs when READY)
        get("/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val job = subtitleService.getJob(jobId)
                ?: throw NotFoundException("Subtitle job not found: $jobId")

            val ttl = appConfig.separation.urlTtlSec
            val originalUrl = if (job.status == "READY" && job.originalSrtFile != null) {
                val token = signer.sign(jobId, "original", ttl)
                "/api/v2/subtitles/$jobId/srt?lang=original&token=$token"
            } else null
            // 언어별 번역 URL 맵 — N langs 모두 별도 signed URL.
            val translatedUrlsByLang: Map<String, String> = if (job.status == "READY") {
                job.translatedSrtFiles.mapValues { (lang, _) ->
                    val token = signer.sign(jobId, "t_$lang", ttl)
                    "/api/v2/subtitles/$jobId/srt?lang=$lang&token=$token"
                }
            } else emptyMap()
            // legacy 단일 필드 — 첫 번역만 (구 클라이언트 호환).
            val firstTranslatedUrl = translatedUrlsByLang.values.firstOrNull()
            call.respond(HttpStatusCode.OK, SubtitleStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                originalSrtUrl = originalUrl,
                translatedSrtUrlsByLang = translatedUrlsByLang,
                translatedSrtUrl = firstTranslatedUrl,
            ))
        }

        // GET /api/v2/subtitles/{jobId}/srt?lang=original|<langCode>&token=...
        get("/{jobId}/srt") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val lang = call.request.queryParameters["lang"]
                ?: throw IllegalArgumentException("lang required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            val tokenSubject = if (lang == "original") "original" else "t_$lang"
            if (!signer.verify(jobId, tokenSubject, token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = subtitleService.getJob(jobId)
                ?: throw NotFoundException("Subtitle job not found: $jobId")
            val file = if (lang == "original") job.originalSrtFile
                else job.translatedSrtFiles[lang]
            if (file == null) throw NotFoundException("SRT not available: $lang")
            if (!file.exists()) throw NotFoundException("SRT file missing on disk")
            call.response.header(
                HttpHeaders.ContentType,
                ContentType("application", "x-subrip").toString(),
            )
            call.respondFile(file)
        }
    }
}
