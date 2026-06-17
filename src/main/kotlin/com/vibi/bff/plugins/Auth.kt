package com.vibi.bff.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 인증된 호출자 정보. [userId] 는 internal UUID (users.id), [role] 은 'user' 또는 'admin'.
 * JWT claim 에서 직접 파싱 — DB lookup 없음 (핫패스 성능 + admin 승격 즉시 반영은 재로그인 후).
 */
data class JwtPrincipal(val userId: UUID, val role: String) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN
}

const val ROLE_ADMIN = "admin"
const val ROLE_USER = "user"

/**
 * `Authorization: Bearer <jwt>` 헤더에서 BFF JWT 를 추출·검증 후 [JwtPrincipal] 반환.
 *
 * 검증 실패 (헤더 누락 / 서명 불일치 / 만료 / sub 누락 / sub UUID 형식 오류) 시 모두
 * `ApiErrorException(Unauthorized, ...)` 로 단일화 — 외부에는 상세 사유 노출 안 함.
 *
 * 검증 통과 후 role 클레임이 없으면 'user' 로 fallback (구버전 JWT 호환).
 */
fun ApplicationCall.requireUser(jwtSecret: String): JwtPrincipal {
    val token = request.header("Authorization")
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "missing_token")

    val decoded = try {
        JWT.require(Algorithm.HMAC256(jwtSecret))
            .withIssuer(AuthService.ISSUER)
            .build()
            .verify(token)
    } catch (e: JWTVerificationException) {
        throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_token")
    }

    val sub = decoded.subject
        ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_token")
    val userId = try {
        UUID.fromString(sub)
    } catch (e: IllegalArgumentException) {
        throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_token")
    }
    val role = decoded.getClaim("role").asString()?.takeIf { it.isNotBlank() } ?: ROLE_USER
    return JwtPrincipal(userId = userId, role = role)
}

/**
 * [requireUser] 검증 후 role == 'admin' 이 아니면 `Forbidden` 으로 거부.
 *
 * 일반 사용자가 admin slug 를 추측해 직접 BFF admin endpoint 를 때려도 여기서 차단된다.
 * URL 숨김 (landing middleware) 과 함께 이중 방어.
 */
fun ApplicationCall.requireAdmin(jwtSecret: String): JwtPrincipal {
    val principal = requireUser(jwtSecret)
    if (!principal.isAdmin) {
        throw ApiErrorException(HttpStatusCode.Forbidden, "admin_required")
    }
    return principal
}

/**
 * 비용/엔타이틀먼트 mutating endpoint(render·separation submit)용 인증 헬퍼.
 *
 * - [jwtSecret] null (테스트/dev 분기) → null 반환 (인증 강제 안 함, 기존 동작 유지).
 * - [userRepository] null (테스트/dev) → [requireUser] 만 수행 (DB 조회 없음).
 * - 둘 다 주입된 운영 경로 → [requireUser] 후 users row 존재까지 확인. 삭제된 계정의
 *   아직-유효한 JWT (만료 전 최대 expiry) 가 ffmpeg/Perso 비용을 유발하는 것을 차단.
 *   존재하지 않으면 `Unauthorized(account_deleted)`.
 *
 * 상태 폴링 GET 등 핫패스에는 쓰지 않는다 — 거긴 [requireUser] 단독(무-DB)으로 충분.
 */
suspend fun ApplicationCall.requireUserActiveIfPossible(
    jwtSecret: String?,
    userRepository: UserRepository?,
): JwtPrincipal? {
    if (jwtSecret == null) return null
    val principal = requireUser(jwtSecret)
    if (userRepository != null) {
        val exists = withContext(Dispatchers.IO) { userRepository.exists(principal.userId) }
        if (!exists) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "account_deleted")
        }
    }
    return principal
}
