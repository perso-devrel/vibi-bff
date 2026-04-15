package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.LipSyncStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

private const val MAX_FILE_SIZE = 500L * 1024 * 1024 // 500MB

fun Route.lipSyncV2Routes(
    elevenLabsClient: ElevenLabsClient,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
) {
    route("/lipsync") {
        // POST /api/v2/lipsync (multipart)
        post {
            val multipart = call.receiveMultipart()

            var videoFile: File? = null
            var audioFile: File? = null
            var targetLang: String = "en"
            var startMs: Long? = null
            var durationMs: Long? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        when (part.name) {
                            "video" -> {
                                @Suppress("DEPRECATION")
                                val blobPath = fileStorage.saveUpload(
                                    part.originalFileName ?: "video.mp4",
                                    part.streamProvider(),
                                    MAX_FILE_SIZE,
                                )
                                videoFile = fileStorage.getUploadFile(blobPath)
                            }
                            "audio" -> {
                                @Suppress("DEPRECATION")
                                val blobPath = fileStorage.saveUpload(
                                    part.originalFileName ?: "audio.mp3",
                                    part.streamProvider(),
                                    MAX_FILE_SIZE,
                                )
                                audioFile = fileStorage.getUploadFile(blobPath)
                            }
                        }
                    }
                    is PartData.FormItem -> {
                        when (part.name) {
                            "targetLang" -> targetLang = part.value
                            "startMs" -> startMs = part.value.toLongOrNull()
                            "durationMs" -> durationMs = part.value.toLongOrNull()
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val video = videoFile ?: throw IllegalArgumentException("video file is required")
            val audio = audioFile ?: throw IllegalArgumentException("audio file is required")

            val elResponse = elevenLabsClient.createLipSync(
                videoFile = video,
                audioFile = audio,
                targetLang = targetLang,
                startMs = startMs,
                durationMs = durationMs,
            )

            call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                id = elResponse.id,
                status = "processing",
            ))
        }

        // GET /api/v2/lipsync/{jobId}/status
        get("/{jobId}/status") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Lip-sync jobId required")

            val elStatus = elevenLabsClient.getLipSyncStatus(jobId)

            when (elStatus.status) {
                "dubbed" -> {
                    val langCode = elStatus.targetLanguages.firstOrNull() ?: "en"
                    val (targetFile, path) = fileStorage.getLipSyncResultFile(jobId)
                    if (!targetFile.exists()) {
                        elevenLabsClient.downloadLipSyncResult(jobId, langCode, targetFile)
                    }
                    val videoUrl = fileStorage.resolveDownloadUrl(appConfig.baseUrl, path)
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = jobId,
                        status = "completed",
                        outputVideoUrl = videoUrl,
                    ))
                }
                "failed" -> {
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = jobId,
                        status = "failed",
                        error = elStatus.error,
                    ))
                }
                else -> {
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = jobId,
                        status = "processing",
                    ))
                }
            }
        }

        // GET /api/v2/lipsync/{jobId}/download
        get("/{jobId}/download") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Lip-sync jobId required")

            val (file, _) = fileStorage.getLipSyncResultFile(jobId)

            if (!file.exists()) {
                val elStatus = elevenLabsClient.getLipSyncStatus(jobId)
                if (elStatus.status == "dubbed") {
                    val langCode = elStatus.targetLanguages.firstOrNull() ?: "en"
                    elevenLabsClient.downloadLipSyncResult(jobId, langCode, file)
                } else {
                    throw NotFoundException("Lip-sync result not ready: status=${elStatus.status}")
                }
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$jobId.mp4"
                ).toString()
            )
            call.respondFile(file)
        }
    }
}
