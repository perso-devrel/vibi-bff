package com.dubcast.bff.routes

import com.dubcast.bff.model.UploadResponse
import com.dubcast.bff.service.FileStorageService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val MAX_FILE_SIZE = 500L * 1024 * 1024 // 500MB
private val ALLOWED_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "mp3", "wav", "flac")

fun Route.uploadRoutes(fileStorage: FileStorageService) {
    post("/upload") {
        val multipart = call.receiveMultipart()
        var blobPath: String? = null

        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    val fileName = part.originalFileName ?: "upload"
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (ext !in ALLOWED_EXTENSIONS) {
                        part.dispose()
                        return@forEachPart call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Unsupported file type: $ext")
                        )
                    }
                    @Suppress("DEPRECATION")
                    blobPath = fileStorage.saveUpload(fileName, part.streamProvider(), MAX_FILE_SIZE)
                }
                else -> {}
            }
            part.dispose()
        }

        val path = blobPath
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))

        call.respond(HttpStatusCode.OK, UploadResponse(blobPath = path))
    }
}
