package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.*
import com.dubcast.bff.plugins.ApiErrorException
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaTrimmer
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SEPARATION_FILE_SIZE = 500L * 1024 * 1024 // 500MB

fun Route.separationRoutes(
    separationService: SeparationService,
    stemMixService: StemMixService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
) {
    route("/separate") {
        // POST /api/v2/separate — submit job
        post {
            val (file, spec) = parseUploadAndSpec<SeparationSpec>(
                call.receiveMultipart(), fileStorage, MAX_SEPARATION_FILE_SIZE,
            )
            val pipelineInput = maybeTrim(file, spec)
            val jobId = separationService.submit(pipelineInput, spec)
            call.respond(HttpStatusCode.Accepted, SeparationResponse(jobId = jobId))
        }

        // GET /api/v2/separate/{jobId} — status + stem URLs (signed)
        get("/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")

            val ttl = appConfig.separation.urlTtlSec
            val stems = if (job.status == "READY") {
                job.stems.map { s ->
                    val token = signer.sign(jobId, s.stemId, ttl)
                    StemInfo(
                        stemId = s.stemId,
                        label = s.label,
                        url = "/api/v2/separate/$jobId/stem/${s.stemId}?token=$token",
                    )
                }
            } else emptyList()

            call.respond(HttpStatusCode.OK, SeparationStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                stems = stems,
                mixJobId = job.consumedByMixJobId,
            ))
        }

        // GET /api/v2/separate/{jobId}/stem/{stemId}?token=... — streamed audio
        get("/{jobId}/stem/{stemId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val stemId = call.parameters["stemId"]
                ?: throw NotFoundException("stemId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            if (!signer.verify(jobId, stemId, token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")
            val stem = job.stems.firstOrNull { it.stemId == stemId }
                ?: throw NotFoundException("Stem not found: $stemId")
            if (!stem.file.exists()) {
                throw NotFoundException("Stem file missing on disk")
            }
            call.respondFile(stem.file)
        }

        // POST /api/v2/separate/{jobId}/mix — kick off mixing; on success, stems are disposed
        post("/{jobId}/mix") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val req = call.receive<StemMixRequest>()

            val mixJobId = stemMixService.newJobId()
            val job = separationService.reserveForMix(jobId, mixJobId)
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        error = "Separation job not ready or already consumed",
                    ),
                )

            val stemByIdLocal = job.stems.associateBy { it.stemId }
            val unknownStemId = req.stems.firstOrNull { it.stemId !in stemByIdLocal }?.stemId
            if (unknownStemId != null) {
                separationService.releaseReservation(jobId)
                throw IllegalArgumentException("Unknown stemId: $unknownStemId")
            }
            val selected = req.stems.map { sel ->
                sel to stemByIdLocal.getValue(sel.stemId).file
            }

            stemMixService.submit(mixJobId, selected) { completed ->
                if (completed.status == "COMPLETED") {
                    separationService.dispose(jobId)
                }
            }

            call.respond(HttpStatusCode.Accepted, StemMixResponse(mixJobId = mixJobId))
        }

        // GET /api/v2/separate/mix/{mixJobId} — mix status
        get("/mix/{mixJobId}") {
            val mixJobId = call.parameters["mixJobId"]
                ?: throw NotFoundException("mixJobId required")
            val job = stemMixService.getJob(mixJobId)
                ?: throw NotFoundException("Mix job not found: $mixJobId")

            val downloadUrl = if (job.status == "COMPLETED") {
                val token = signer.sign(mixJobId, "download", appConfig.separation.mixUrlTtlSec)
                "/api/v2/separate/mix/$mixJobId/download?token=$token"
            } else null

            call.respond(HttpStatusCode.OK, StemMixStatusResponse(
                mixJobId = mixJobId,
                status = job.status,
                progress = job.progress,
                error = job.error,
                downloadUrl = downloadUrl,
            ))
        }

        // GET /api/v2/separate/mix/{mixJobId}/download?token=... — streamed mix
        get("/mix/{mixJobId}/download") {
            val mixJobId = call.parameters["mixJobId"]
                ?: throw NotFoundException("mixJobId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            if (!signer.verify(mixJobId, "download", token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = stemMixService.getJob(mixJobId)
                ?: throw NotFoundException("Mix job not found: $mixJobId")
            if (job.status != "COMPLETED" || !job.outputFile.exists()) {
                throw NotFoundException("Mix not ready: status=${job.status}")
            }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$mixJobId.mp3"
                ).toString()
            )
            call.respondFile(job.outputFile)
        }
    }
}

/**
 * If [spec] carries a trim range, probe the file, validate against its
 * actual duration, then stream-copy cut the window with ffmpeg. The
 * trimmed file replaces the original upload (here we delete the original
 * to free disk before the upstream upload begins). Blocking ffprobe /
 * ffmpeg calls run on [Dispatchers.IO] so the Netty worker isn't held
 * while a 500 MB file is being cut. Throws [ApiErrorException] with
 * error codes the client can branch on.
 */
internal suspend fun maybeTrim(file: File, spec: SeparationSpec): File = withContext(Dispatchers.IO) {
    val start = spec.trimStartMs
    val end = spec.trimEndMs
    if (start == null || end == null) return@withContext file

    val durationMs = MediaTrimmer.probeDurationMs(file)
        ?: run {
            file.delete()
            throw ApiErrorException(
                HttpStatusCode.InternalServerError,
                "ffmpeg_error",
                "Could not probe source duration",
            )
        }
    if (end > durationMs) {
        file.delete()
        throw ApiErrorException(
            HttpStatusCode.BadRequest,
            "trim_end_exceeds_duration",
            "trimEndMs=$end but file duration=$durationMs",
        )
    }

    val ext = file.extension.ifEmpty { "bin" }
    val trimmed = File(file.parentFile, "${file.nameWithoutExtension}.trimmed.$ext")
    val ok = MediaTrimmer.trim(file, start, end, trimmed)
    if (!ok) {
        file.delete()
        throw ApiErrorException(
            HttpStatusCode.InternalServerError,
            "ffmpeg_error",
            "Trim extraction failed",
        )
    }
    file.delete()
    trimmed
}
