package com.vibi.bff.routes

import com.vibi.bff.service.ObjectStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 큰 산출물 다운로드 응답 단일 진입점.
 *
 * - [store] != null  → 파일을 R2 에 (idempotent) 업로드 후 SigV4 presigned URL 로 응답:
 *     - [asJsonUrl] == false (기본) → 302 redirect. 네이티브 HTTP 클라(모바일)가 그대로 따라감.
 *     - [asJsonUrl] == true → `{ url }` JSON. UXP(Adobe 패널) fetch 가 302 를 깔끔히 못 따라가므로
 *       capability-token 없이 Bearer 로 인증한 호출엔 JSON 으로 내려 클라가 url 을 직접(헤더 없이) 받게 한다.
 *   Cloud Run 인스턴스가 바이트 전송으로 잠기지 않아 동시 다운로드 처리량 회복. R2 egress 무료.
 * - [store] == null  → 기존 respondFile streaming fallback (로컬 dev / R2 미사용). asJsonUrl 무관(바이트 스트림).
 *
 * 호출 전 caller 가 token/Bearer 인증 / status 체크 / file.exists() 확인 끝낸 상태여야 함.
 */
suspend fun ApplicationCall.respondDownload(
    file: File,
    objectKey: String,
    contentType: ContentType,
    downloadFilename: String?,
    store: ObjectStore?,
    asJsonUrl: Boolean = false,
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
        if (asJsonUrl) {
            respond(HttpStatusCode.OK, SignedDownloadUrl(url))
        } else {
            respondRedirect(url, permanent = false)
        }
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
 * `separationStem` / `renderOutput` 두 prefix 가 R2 lifecycle rule
 * (`deploy/r2-lifecycle.json`) 의 `matchesPrefix` 분기 후보가 되므로 중앙화 가치가 더 큼.
 */
object ObjectKey {
    fun separationStem(jobId: String, stemId: String, ext: String): String =
        "separation/$jobId/$stemId.$ext"

    fun renderOutput(jobId: String, fileName: String): String =
        "render/$jobId/$fileName"

    /**
     * 모바일이 사전 PUT 하는 render asset (segment 영상 / BGM). sha256 기반 글로벌 dedup —
     * 다른 user 가 같은 bytes 를 올려도 한 슬롯. ext 는 [ALLOWED_ASSET_EXTS] 화이트리스트만.
     * R2 lifecycle 은 prefix `assets/` 분기로 별도 TTL 부여 가능.
     */
    fun asset(sha256Hex: String, ext: String): String {
        require(sha256Hex.matches(SHA256_HEX_RE)) { "invalid sha256 hex" }
        val lower = ext.lowercase()
        require(lower in ALLOWED_ASSET_EXTS) { "unsupported asset ext: $ext" }
        return "assets/$sha256Hex.$lower"
    }

    private val SHA256_HEX_RE = Regex("^[0-9a-f]{64}$")
    val ALLOWED_ASSET_EXTS: Set<String> = setOf("mp4", "mov", "m4a", "mp3", "wav", "aac")
}

/**
 * R2 presigned 다운로드 URL 을 redirect 대신 JSON 으로 내려줄 때의 body. UXP(Adobe 패널) fetch 가
 * 302 를 못 따라가므로 capability-token 없이 Bearer 로 인증한 stem 다운로드엔 `{ url }` 로 응답한다.
 * 필드명 `url` 은 plugin 클라(`src/jobs/separationClient.ts`)가 읽는 키와 1:1.
 */
@Serializable
data class SignedDownloadUrl(val url: String)

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
