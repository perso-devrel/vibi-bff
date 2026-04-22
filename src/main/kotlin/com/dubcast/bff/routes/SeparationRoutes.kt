package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.*
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
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
            val multipart = call.receiveMultipart()
            var sourceFile: File? = null
            var spec: SeparationSpec? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "file") {
                            @Suppress("DEPRECATION")
                            val blobPath = fileStorage.saveUpload(
                                part.originalFileName ?: "source.bin",
                                part.streamProvider(),
                                MAX_SEPARATION_FILE_SIZE,
                            )
                            sourceFile = fileStorage.getUploadFile(blobPath)
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "spec") {
                            spec = Json.decodeFromString<SeparationSpec>(part.value)
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val file = sourceFile ?: throw IllegalArgumentException("file is required")
            val s = spec ?: throw IllegalArgumentException("spec is required")
            val jobId = separationService.submit(file, s)
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
