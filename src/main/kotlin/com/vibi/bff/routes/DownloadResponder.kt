package com.vibi.bff.routes

import com.vibi.bff.service.ObjectStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 큰 산출물 다운로드 응답 단일 진입점.
 *
 * - [store] != null  → 파일을 R2 에 (idempotent) 업로드 후 SigV4 presigned URL 로 302 redirect.
 *   Cloud Run 인스턴스가 바이트 전송으로 잠기지 않아 동시 다운로드 처리량 회복. R2 egress 무료.
 * - [store] == null  → 기존 respondFile streaming fallback (로컬 dev / R2 미사용).
 *
 * 호출 전 caller 가 token 검증 / status 체크 / file.exists() 확인 끝낸 상태여야 함.
 */
suspend fun ApplicationCall.respondDownload(
    file: File,
    objectKey: String,
    contentType: ContentType,
    downloadFilename: String?,
    store: ObjectStore?,
) {
    if (store != null) {
        val url = withContext(Dispatchers.IO) {
            store.uploadIfAbsent(file, objectKey, contentType.toString())
            store.signedUrl(
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

/**
 * R2 object key 단일 소스. 경로 규칙 (`<prefix>/<jobId>/...`) 이 callsite 마다 흩어지면
 * prefix 리네임/충돌 감사 시 다중 grep 필요. 여기 모아두면 단일 grep 으로 끝남.
 *
 * `separationStem` / `separationMix` / `renderOutput` 세 prefix 가 R2 lifecycle rule
 * (`deploy/r2-lifecycle.json`) 의 `matchesPrefix` 분기 후보가 되므로 중앙화 가치가 더 큼.
 */
object ObjectKey {
    fun separationStem(jobId: String, stemId: String, ext: String): String =
        "separation/$jobId/$stemId.$ext"

    fun separationMix(mixJobId: String): String =
        "separation/mix/$mixJobId.mp3"

    fun renderOutput(jobId: String, fileName: String): String =
        "render/$jobId/$fileName"
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
