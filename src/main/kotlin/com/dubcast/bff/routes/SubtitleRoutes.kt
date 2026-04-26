package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.SubtitleJobResponse
import com.dubcast.bff.model.SubtitleSpec
import com.dubcast.bff.model.SubtitleStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.SignedUrlService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val MAX_SUBTITLE_FILE_SIZE = 500L * 1024 * 1024 // 500MB

fun Route.subtitleRoutes(
    subtitleService: AutoSubtitleService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
) {
    route("/subtitles") {
        // POST /api/v2/subtitles — submit
        post {
            val (file, spec) = parseUploadAndSpec<SubtitleSpec>(
                call.receiveMultipart(), fileStorage, MAX_SUBTITLE_FILE_SIZE,
            )
            val jobId = subtitleService.submit(file, spec)
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
            val translatedUrl = if (job.status == "READY" && job.translatedSrtFile != null) {
                val token = signer.sign(jobId, "translated", ttl)
                "/api/v2/subtitles/$jobId/srt?lang=translated&token=$token"
            } else null

            call.respond(HttpStatusCode.OK, SubtitleStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                originalSrtUrl = originalUrl,
                translatedSrtUrl = translatedUrl,
            ))
        }

        // GET /api/v2/subtitles/{jobId}/srt?lang=original|translated&token=...
        get("/{jobId}/srt") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val lang = call.request.queryParameters["lang"]
                ?: throw IllegalArgumentException("lang required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            require(lang == "original" || lang == "translated") {
                "lang must be 'original' or 'translated'"
            }
            if (!signer.verify(jobId, lang, token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = subtitleService.getJob(jobId)
                ?: throw NotFoundException("Subtitle job not found: $jobId")
            val file = when (lang) {
                "original" -> job.originalSrtFile
                else -> job.translatedSrtFile
            } ?: throw NotFoundException("SRT not available: $lang")
            if (!file.exists()) {
                throw NotFoundException("SRT file missing on disk")
            }
            call.response.header(
                HttpHeaders.ContentType,
                ContentType("application", "x-subrip").toString(),
            )
            call.respondFile(file)
        }
    }
}
