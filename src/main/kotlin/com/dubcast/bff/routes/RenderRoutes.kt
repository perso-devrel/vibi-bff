package com.dubcast.bff.routes

import com.dubcast.bff.model.RenderConfig
import com.dubcast.bff.model.RenderResponse
import com.dubcast.bff.model.RenderStatusResponse
import com.dubcast.bff.plugins.NotFoundException
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.RenderService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.io.File

private const val MAX_FILE_SIZE = 500L * 1024 * 1024 // 500MB

fun Route.renderRoutes(
    renderService: RenderService,
    fileStorage: FileStorageService,
) {
    route("/render") {
        // POST /api/v2/render (multipart)
        post {
            val multipart = call.receiveMultipart()

            var videoFile: File? = null
            val audioFiles = mutableMapOf<String, File>()
            var subtitlesFile: File? = null
            var renderConfig: RenderConfig? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        when {
                            part.name == "video" -> {
                                @Suppress("DEPRECATION")
                                val blobPath = fileStorage.saveUpload(
                                    part.originalFileName ?: "video.mp4",
                                    part.streamProvider(),
                                    MAX_FILE_SIZE,
                                )
                                videoFile = fileStorage.getUploadFile(blobPath)
                            }
                            part.name?.startsWith("audio_") == true -> {
                                @Suppress("DEPRECATION")
                                val blobPath = fileStorage.saveUpload(
                                    part.originalFileName ?: "${part.name}.mp3",
                                    part.streamProvider(),
                                    MAX_FILE_SIZE,
                                )
                                audioFiles[part.name!!] = fileStorage.getUploadFile(blobPath)
                            }
                            part.name == "subtitles" -> {
                                @Suppress("DEPRECATION")
                                val blobPath = fileStorage.saveUpload(
                                    part.originalFileName ?: "subtitles.ass",
                                    part.streamProvider(),
                                )
                                subtitlesFile = fileStorage.getUploadFile(blobPath)
                            }
                        }
                    }
                    is PartData.FormItem -> {
                        if (part.name == "config") {
                            renderConfig = Json.decodeFromString<RenderConfig>(part.value)
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            val video = videoFile ?: throw IllegalArgumentException("video file is required")
            val config = renderConfig ?: throw IllegalArgumentException("config is required")

            // Validate all audio keys exist
            for (clip in config.dubClips) {
                require(audioFiles.containsKey(clip.audioFileKey)) {
                    "Audio file missing for key: ${clip.audioFileKey}"
                }
            }

            val inputFiles = mutableListOf(video)
            inputFiles.addAll(audioFiles.values)
            if (subtitlesFile != null) inputFiles.add(subtitlesFile!!)

            val jobId = renderService.submitRender(
                videoFile = video,
                audioFiles = audioFiles,
                subtitlesFile = subtitlesFile,
                dubClips = config.dubClips,
                videoDurationMs = config.videoDurationMs,
                inputFilesToCleanup = inputFiles,
            )

            call.respond(HttpStatusCode.OK, RenderResponse(jobId = jobId))
        }

        // GET /api/v2/render/{jobId}/status
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
            ))
        }

        // GET /api/v2/render/{jobId}/download
        get("/{jobId}/download") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("Render jobId required")

            val job = renderService.getJob(jobId)
                ?: throw NotFoundException("Render job not found: $jobId")

            if (job.status != "COMPLETED" || !job.outputFile.exists()) {
                throw NotFoundException("Render not ready: status=${job.status}")
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "$jobId.mp4"
                ).toString()
            )
            call.respondFile(job.outputFile)
        }
    }
}
