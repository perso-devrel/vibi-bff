package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.LipSyncRequest
import com.dubcast.bff.model.LipSyncStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.lipSyncRoutes(
    elevenLabsClient: ElevenLabsClient,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
) {
    route("/lipsync") {
        post {
            val request = call.receive<LipSyncRequest>()

            val videoFile = fileStorage.getUploadFile(request.videoBlobPath)
            val audioFile = fileStorage.getUploadFile(request.audioBlobPath)

            val elResponse = elevenLabsClient.createLipSync(videoFile, audioFile)

            call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                id = elResponse.id,
                status = "processing",
            ))
        }

        get("/{id}/status") {
            val id = call.parameters["id"]
                ?: throw NotFoundException("Lip-sync ID required")

            val elStatus = elevenLabsClient.getLipSyncStatus(id)

            when (elStatus.status) {
                "dubbed" -> {
                    val (targetFile, path) = fileStorage.getLipSyncResultFile(id)
                    if (!targetFile.exists()) {
                        elevenLabsClient.downloadLipSyncResult(id, "en", targetFile)
                    }
                    val videoUrl = fileStorage.resolveDownloadUrl(appConfig.baseUrl, path)
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = id,
                        status = "completed",
                        outputVideoUrl = videoUrl,
                    ))
                }
                "failed" -> {
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = id,
                        status = "failed",
                        error = elStatus.error,
                    ))
                }
                else -> {
                    call.respond(HttpStatusCode.OK, LipSyncStatusResponse(
                        id = id,
                        status = "processing",
                    ))
                }
            }
        }
    }
}
