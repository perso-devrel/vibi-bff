package com.vibi.bff.routes

import com.vibi.bff.plugins.AppJson
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.MediaSourceResolver
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import java.io.File

/**
 * Resolve a caller-owned source file via [mediaSourceResolver] and run [block] on it.
 * If [block] throws, the source file is best-effort deleted so the upload doesn't
 * strand on disk. Used by submit routes that don't need any pre-pipeline transform
 * (subtitles / autodub). SeparationRoutes uses its own try-catch because [maybeTrim]
 * may produce a separate `pipelineInput` file that also needs cleanup.
 */
internal inline fun <T> withResolvedSource(
    filePart: File?,
    editedRenderJobId: String?,
    mediaSourceResolver: MediaSourceResolver,
    block: (File) -> T,
): T {
    val source = mediaSourceResolver.resolve(filePart, editedRenderJobId)
    try {
        return block(source)
    } catch (e: Throwable) {
        runCatching { source.delete() }
        throw e
    }
}

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
 * source file (typically via [com.vibi.bff.service.MediaSourceResolver]).
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

    // spec parse 실패 (`Json.decodeFromString` throws) 시 이미 디스크에 떨어진 source
    // file 이 누수됨 — try-catch 로 누적된 file 을 정리 후 rethrow. multipart parser
    // 자체가 throw 하는 경우도 동일하게 cleanup.
    try {
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
                        // AppJson 사용 — ignoreUnknownKeys=true. 구 클라이언트가 새 DTO 에 없는
                        // 필드 (예: SubtitleSpec.mediaType/numberOfSpeakers 를 /regenerate 의
                        // SubtitleRegenerateSpec 에 보내는 케이스) 를 보내도 400 대신 무시.
                        spec = AppJson.decodeFromString<T>(part.value)
                    }
                }
                else -> {}
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        sourceFile?.let { runCatching { it.delete() } }
        throw e
    }

    return sourceFile to spec
}
