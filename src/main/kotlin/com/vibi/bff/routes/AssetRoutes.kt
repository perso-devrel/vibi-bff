package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.model.AssetUploadUrlRequest
import com.vibi.bff.model.AssetUploadUrlResponse
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.ObjectStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asset-by-reference 흐름 — 모바일이 render segment 영상/BGM 을 BFF 가 아닌 R2 에 직접 PUT
 * 하기 위한 presigned URL 발급. v3 render endpoint (`POST /render/v3`) 가 `assets/<sha>.<ext>`
 * 키만 받아 R2 에서 다운로드해서 ffmpeg 한다.
 *
 * 흐름:
 *   1) 모바일이 로컬 파일 sha256 + size + ext + contentType 전송
 *   2) BFF 가 `objectExists` 로 dedup 체크
 *   3) 있으면 `alreadyExists=true` + `uploadUrl=null` 응답 (모바일은 PUT skip)
 *   4) 없으면 `presignedPutUrl` 발급 — 모바일이 그 URL 로 직접 PUT
 *
 * 보안:
 *   - JWT 인증 필수 — 토큰 누수 시에도 spam 업로드 제한 (별도 rate limit 권장)
 *   - presigned PUT URL TTL 300s — leak 윈도우 최소화
 *   - contentType / contentLength 가 sign 시점에 고정되어 PUT 시 동일 값 필수
 *   - sha256 hex / size / ext 화이트리스트는 [com.vibi.bff.model.AssetUploadUrlRequest.init]
 *     과 [ObjectKey.asset] 양쪽에서 검증
 */
fun Route.assetRoutes(
    objectStore: ObjectStore?,
    /** JWT 검증용 — null 이면 인증 강제 안 함 (테스트 호환). 운영에선 항상 주입. */
    jwtSecret: String? = null,
) {
    route("/assets") {
        post("/upload-url") {
            jwtSecret?.let { call.requireUser(it) }
            if (objectStore == null) {
                call.respond(HttpStatusCode.ServiceUnavailable,
                    com.vibi.bff.model.ErrorResponse("r2_disabled",
                        "R2 not configured; v3 render path unavailable"))
                return@post
            }
            val req = call.receive<AssetUploadUrlRequest>()
            require(req.sizeBytes <= MAX_UPLOAD_FILE_SIZE) {
                "sizeBytes ${req.sizeBytes} exceeds limit $MAX_UPLOAD_FILE_SIZE"
            }
            val key = ObjectKey.asset(req.sha256Hex, req.ext)
            val exists = withContext(Dispatchers.IO) { objectStore.objectExists(key) }
            val (uploadUrl, ttl) = if (exists) {
                null to 0L
            } else {
                val ttlSec = 300L
                val url = withContext(Dispatchers.IO) {
                    objectStore.presignedPutUrl(
                        objectKey = key,
                        contentType = req.contentType,
                        contentLengthBytes = req.sizeBytes,
                        ttlSec = ttlSec,
                    )
                }
                url to ttlSec
            }
            call.respond(
                AssetUploadUrlResponse(
                    assetKey = key,
                    alreadyExists = exists,
                    uploadUrl = uploadUrl,
                    expiresInSec = ttl,
                )
            )
        }
    }
}
