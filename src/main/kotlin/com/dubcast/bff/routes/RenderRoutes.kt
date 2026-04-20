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
        post {
            val multipart = call.receiveMultipart()

            var legacyVideoFile: File? = null
            val videoFiles = mutableMapOf<String, File>()
            val audioFiles = mutableMapOf<String, File>()
            val imageFiles = mutableMapOf<String, File>()
            val segmentImageFiles = mutableMapOf<String, File>()
            var subtitlesFile: File? = null
            var renderConfig: RenderConfig? = null

            fun saveFile(part: PartData.FileItem, defaultName: String): File {
                @Suppress("DEPRECATION")
                val blobPath = fileStorage.saveUpload(
                    part.originalFileName ?: defaultName,
                    part.streamProvider(),
                    MAX_FILE_SIZE,
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
                            name.startsWith("audio_") ->
                                audioFiles[name] = saveFile(part, "$name.mp3")
                            name.startsWith("segment_image_") ->
                                segmentImageFiles[name] = saveFile(part, "$name.jpg")
                            name.startsWith("image_") ->
                                imageFiles[name] = saveFile(part, "$name.jpg")
                            name == "subtitles" ->
                                subtitlesFile = saveFile(part, "subtitles.ass")
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

            val config = renderConfig ?: throw IllegalArgumentException("config is required")

            // Validate audio keys
            for (clip in config.dubClips) {
                require(audioFiles.containsKey(clip.audioFileKey)) {
                    "Audio file missing for key: ${clip.audioFileKey}"
                }
            }
            // Validate image keys
            for (clip in config.imageClips) {
                require(imageFiles.containsKey(clip.imageFileKey)) {
                    "Image file missing for key: ${clip.imageFileKey}"
                }
            }

            val inputFiles = mutableListOf<File>()
            legacyVideoFile?.let { inputFiles.add(it) }
            inputFiles.addAll(videoFiles.values)
            inputFiles.addAll(audioFiles.values)
            inputFiles.addAll(imageFiles.values)
            inputFiles.addAll(segmentImageFiles.values)
            subtitlesFile?.let { inputFiles.add(it) }

            val jobId = renderService.submitRender(
                legacyVideoFile = legacyVideoFile,
                videoFiles = videoFiles,
                segmentImageFiles = segmentImageFiles,
                audioFiles = audioFiles,
                imageFiles = imageFiles,
                subtitlesFile = subtitlesFile,
                dubClips = config.dubClips,
                imageClips = config.imageClips,
                videoDurationMs = config.videoDurationMs,
                segments = config.segments,
                inputFilesToCleanup = inputFiles,
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
