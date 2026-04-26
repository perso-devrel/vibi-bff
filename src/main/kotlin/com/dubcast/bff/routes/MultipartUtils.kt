package com.dubcast.bff.routes

import com.dubcast.bff.service.FileStorageService
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Standard "single file + JSON spec" multipart shape used by the
 * autodub / subtitle / separation submit endpoints. Saves the file via
 * [fileStorage] (enforcing [maxFileSize]) and decodes the spec form-item.
 *
 * Throws [IllegalArgumentException] when either part is missing — the
 * StatusPages handler maps that to 400 with the same shape these routes
 * used inline.
 */
internal suspend inline fun <reified T> parseUploadAndSpec(
    multipart: MultiPartData,
    fileStorage: FileStorageService,
    maxFileSize: Long,
    fileFieldName: String = "file",
    specFieldName: String = "spec",
    defaultFileName: String = "source.bin",
): Pair<File, T> {
    var sourceFile: File? = null
    var spec: T? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FileItem -> {
                if (part.name == fileFieldName) {
                    @Suppress("DEPRECATION")
                    val blobPath = fileStorage.saveUpload(
                        part.originalFileName ?: defaultFileName,
                        part.streamProvider(),
                        maxFileSize,
                    )
                    sourceFile = fileStorage.getUploadFile(blobPath)
                }
            }
            is PartData.FormItem -> {
                if (part.name == specFieldName) {
                    spec = Json.decodeFromString<T>(part.value)
                }
            }
            else -> {}
        }
        part.dispose()
    }

    val file = sourceFile ?: throw IllegalArgumentException("$fileFieldName is required")
    val s = spec ?: throw IllegalArgumentException("$specFieldName is required")
    return file to s
}
