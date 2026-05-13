package com.vibi.bff.routes

import com.vibi.bff.service.GcsObjectStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 큰 산출물 다운로드 응답 단일 진입점.
 *
 * - [gcs] != null  → 파일을 GCS 에 (idempotent) 업로드 후 V4 signed URL 로 302 redirect.
 *   Cloud Run 인스턴스가 바이트 전송으로 잠기지 않아 동시 다운로드 처리량 회복.
 * - [gcs] == null  → 기존 respondFile streaming fallback (로컬 dev / GCS 미사용).
 *
 * 호출 전 caller 가 token 검증 / status 체크 / file.exists() 확인 끝낸 상태여야 함.
 */
suspend fun ApplicationCall.respondDownload(
    file: File,
    objectKey: String,
    contentType: ContentType,
    downloadFilename: String?,
    gcs: GcsObjectStore?,
) {
    if (gcs != null) {
        val url = withContext(Dispatchers.IO) {
            gcs.uploadIfAbsent(file, objectKey, contentType.toString())
            gcs.signedUrl(
                objectKey = objectKey,
                downloadFilename = downloadFilename,
                contentType = contentType.toString(),
            )
        }
        respondRedirect(url, permanent = false)
        return
    }

    if (downloadFilename != null) {
        response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, downloadFilename,
            ).toString(),
        )
    }
    response.header(HttpHeaders.ContentType, contentType.toString())
    respondFile(file)
}

/** 확장자 → Content-Type. 알 수 없는 확장자는 [fallback] 반환. */
fun contentTypeForExtension(ext: String, fallback: ContentType): ContentType =
    when (ext.lowercase()) {
        "mp4" -> ContentType("video", "mp4")
        "m4a", "mp4a", "aac" -> ContentType("audio", "mp4")
        "mp3" -> ContentType("audio", "mpeg")
        "wav" -> ContentType("audio", "wav")
        "ogg" -> ContentType("audio", "ogg")
        "flac" -> ContentType("audio", "flac")
        else -> fallback
    }
