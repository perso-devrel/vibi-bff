package com.vibi.bff.routes

import com.vibi.bff.plugins.AppJson
import com.vibi.bff.service.FileStorageService
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import org.slf4j.LoggerFactory
import java.io.File

private val multipartLog = LoggerFactory.getLogger("com.vibi.bff.routes.MultipartUtils")

/**
 * Parse a "single file + JSON spec" multipart that tolerates a missing `file`
 * part. The caller decides whether the missing file is an error (it is for
 * the separation submit endpoint today) — this helper just hands back the
 * pair, plus disk cleanup if spec parsing throws.
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
    // 관측된 part 이름들 — spec 누락/오타 진단용 (silent 4xx 라 client 가 무얼 보냈는지
    // 확인하려면 server-side 에 흔적이 필요).
    val seenFileParts = mutableListOf<String?>()
    val seenFormParts = mutableListOf<String?>()

    // spec parse 실패 (`Json.decodeFromString` throws) 시 이미 디스크에 떨어진 source
    // file 이 누수됨 — try-catch 로 누적된 file 을 정리 후 rethrow. multipart parser
    // 자체가 throw 하는 경우도 동일하게 cleanup.
    try {
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FileItem -> {
                    seenFileParts += part.name
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
                    seenFormParts += part.name
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
        multipartLog.warn(
            "multipart parse failed (file={}, spec={}): fileParts={} formParts={} ex={}",
            fileFieldName, specFieldName, seenFileParts, seenFormParts, e.message,
        )
        throw e
    }

    if (spec == null) {
        multipartLog.warn(
            "multipart missing spec='{}' field: fileParts={} formParts={}",
            specFieldName, seenFileParts, seenFormParts,
        )
    }
    return sourceFile to spec
}

/**
 * spec 없이 단일 파일 업로드만 받는 멀티파트 파서(예: /peaks 의 "audio"). 업로드를 디스크로
 * 스트리밍해 RAM 에 전체 바디를 들지 않는다. 파싱 도중 throw 시 누적 파일 정리 후 rethrow.
 */
internal suspend fun parseSingleFileUpload(
    multipart: MultiPartData,
    fileStorage: FileStorageService,
    maxFileSize: Long,
    fileFieldName: String,
    defaultFileName: String = "audio.bin",
): File? {
    var file: File? = null
    try {
        multipart.forEachPart { part ->
            if (part is PartData.FileItem && part.name == fileFieldName) {
                @Suppress("DEPRECATION")
                val blobPath = fileStorage.saveUpload(
                    part.originalFileName ?: defaultFileName,
                    part.streamProvider(),
                    maxFileSize,
                )
                file = fileStorage.getUploadFile(blobPath)
            }
            part.dispose()
        }
    } catch (e: Throwable) {
        file?.let { runCatching { it.delete() } }
        throw e
    }
    return file
}
