package com.vibi.bff.routes

import com.vibi.bff.plugins.AppJson
import com.vibi.bff.service.FileStorageService
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import java.io.File

/**
 * Parse a "single file + JSON spec" multipart that tolerates a missing `file`
 * part — used by the separation submit endpoint, which accepts either a
 * fresh upload **or** a reference to an already-rendered output via the
 * spec (`editedRenderJobId`). Caller resolves the final source file via
 * [com.vibi.bff.service.MediaSourceResolver].
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
                        // 필드를 보내도 400 대신 무시.
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
