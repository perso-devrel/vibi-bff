package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.config.AppConfig
import com.vibi.bff.model.AutoDubJobResponse
import com.vibi.bff.model.AutoDubSpec
import com.vibi.bff.model.AutoDubStatusResponse
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.service.AutoDubService
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.SignedUrlService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.autoDubRoutes(
    autoDubService: AutoDubService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
    mediaSourceResolver: MediaSourceResolver,
) {
    route("/autodub") {
        // POST /api/v2/autodub — submit
        // Accepts either multipart `file` (legacy) or `spec.editedRenderJobId`
        // referencing a completed /api/v2/render output. See MediaSourceResolver.
        post {
            val (filePart, specOpt) = parseOptionalUploadAndSpec<AutoDubSpec>(
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE),
                fileStorage,
                MAX_UPLOAD_FILE_SIZE,
            )
            val spec = specOpt ?: run {
                filePart?.delete()
                throw IllegalArgumentException("spec is required")
            }
            withResolvedSource(filePart, spec.editedRenderJobId, mediaSourceResolver) { source ->
                val jobId = autoDubService.submit(source, spec)
                call.respond(HttpStatusCode.Accepted, AutoDubJobResponse(jobId = jobId))
            }
        }

        // GET /api/v2/autodub/{jobId}
        get("/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val job = autoDubService.getJob(jobId)
                ?: throw NotFoundException("AutoDub job not found: $jobId")

            val ttl = appConfig.separation.urlTtlSec
            val dubbedUrl = if (job.status == "READY" && job.dubbedAudioFile != null) {
                val token = signer.sign(jobId, "dubbed", ttl)
                "/api/v2/autodub/$jobId/audio?token=$token"
            } else null
            val dubbedVideoUrl = if (job.status == "READY" && job.dubbedVideoFile != null) {
                val token = signer.sign(jobId, "dubbedvideo", ttl)
                "/api/v2/autodub/$jobId/video?token=$token"
            } else null

            call.respond(HttpStatusCode.OK, AutoDubStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                dubbedAudioUrl = dubbedUrl,
                dubbedVideoUrl = dubbedVideoUrl,
                audioExtractFailed = job.audioExtractFailed,
            ))
        }

        // GET /api/v2/autodub/{jobId}/video?token=...
        get("/{jobId}/video") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")
            if (!signer.verify(jobId, "dubbedvideo", token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val job = autoDubService.getJob(jobId)
                ?: throw NotFoundException("AutoDub job not found: $jobId")
            val file = job.dubbedVideoFile
                ?: throw NotFoundException("Dubbed video not ready")
            if (!file.exists()) throw NotFoundException("Dubbed video file missing on disk")
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$jobId.mp4"
                ).toString()
            )
            call.respondFile(file)
        }

        // GET /api/v2/autodub/{jobId}/audio?token=...
        get("/{jobId}/audio") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            if (!signer.verify(jobId, "dubbed", token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            val job = autoDubService.getJob(jobId)
                ?: throw NotFoundException("AutoDub job not found: $jobId")
            val file = job.dubbedAudioFile
                ?: throw NotFoundException("Dubbed audio not ready")
            if (!file.exists()) {
                throw NotFoundException("Dubbed audio file missing on disk")
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$jobId.mp3"
                ).toString()
            )
            call.respondFile(file)
        }
    }
}
