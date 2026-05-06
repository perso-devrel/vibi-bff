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
    val (file, spec) = parseOptionalUploadAndSpec<T>(
        multipart, fileStorage, maxFileSize, fileFieldName, specFieldName, defaultFileName,
    )
    val resolvedFile = file ?: run {
        spec ?: throw IllegalArgumentException("$specFieldName is required")
        throw IllegalArgumentException("$fileFieldName is required")
    }
    val s = spec ?: run {
        // Don't strand the upload on disk when the caller forgot the spec —
        // the request is rejected, so the bytes are dead weight.
        resolvedFile.delete()
        throw IllegalArgumentException("$specFieldName is required")
    }
    return resolvedFile to s
}

/**
 * Phase 1 variant — same shape as [parseUploadAndSpec] but tolerates a
 * missing `file` part. Used by routes that accept either a fresh upload
 * **or** a reference to an already-rendered output via the spec
 * (`editedRenderJobId`). Caller is responsible for resolving the final
 * source file (typically via [com.dubcast.bff.service.MediaSourceResolver]).
 *
 * Still requires the spec — without it we can't know which path the
 * caller intended.
 */
internal suspend inline fun <reified T> parseOptionalUploadAndSpec(
    multipart: MultiPartData,
    fileStorage: FileStorageService,
    maxFileSize: Long,
    fileFieldName: String = "file",
    specFieldName: String = "spec",
    defaultFileName: String = "source.bin",
): Pair<File?, T?> {
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

    return sourceFile to spec
}
