package com.vibi.bff.routes

import com.vibi.bff.model.AppleAuthRequest
import com.vibi.bff.model.GoogleAuthRequest
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.AccountContentEraser
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.routes.AuthRoutes")

/**
 * 소셜 로그인 게이트웨이 + 회원탈퇴.
 *
 * 모바일 클라이언트(iOS GoogleSignIn / Apple AuthenticationServices / Android Credential
 * Manager)가 native SDK 로 받은 ID Token 을 BFF 에 전달하면, BFF 가 provider 별 검증
 * (Google `tokeninfo` / Apple JWKS) 후 user 테이블 upsert → 자체 access token (HS256 JWT)
 * 을 발급한다. JWT 의 `sub` 는 BFF internal UUID (provider sub 가 아님) — IAP
 * `appAccountToken` 으로도 그대로 재사용된다.
 *
 * `DELETE /auth/account` 는 인증된 사용자 본인을 영구 삭제 (FK cascade — V5 마이그레이션).
 * Apple 의 App Store 가이드라인 5.1.1(v) 가 요구하는 in-app account deletion 충족.
 */
fun Route.authRoutes(
    authService: AuthService,
    userRepository: UserRepository,
    accountContentEraser: AccountContentEraser,
    jwtSecret: String,
) {
    route("/auth") {
        post("/google") {
            val req = call.receive<GoogleAuthRequest>()
            val user = authService.verifyGoogleIdToken(req.idToken)
            val response = authService.issueAccessToken(user)
            call.respond(HttpStatusCode.OK, response)
        }

        post("/apple") {
            val req = call.receive<AppleAuthRequest>()
            val user = authService.verifyAppleIdToken(req.idToken, req.fullName)
            val response = authService.issueAccessToken(user)
            call.respond(HttpStatusCode.OK, response)
        }

        delete("/account") {
            val principal = call.requireUser(jwtSecret)
            // GDPR 17조 / CCPA right-to-erasure: users row 삭제 BEFORE 에 사용자 콘텐츠(분리 스템·
            // 렌더 산출물)를 로컬 디스크 + R2 에서 제거한다. user_id 가 SET NULL 되기 전에 잡을
            // enumerate 해야 하므로 순서가 load-bearing. best-effort — 콘텐츠 삭제 실패가 계정
            // 삭제 자체를 막지 않도록 runCatching (잔존분은 R2 lifecycle + uploads GC 가 안전망).
            runCatching { accountContentEraser.erase(principal.userId) }
                .onFailure { e -> log.warn("account content erase failed (proceeding with row delete) user={}: {}", principal.userId, e.message) }
            // 이후 users row 삭제 → FK cascade 가 user_credits(CASCADE) / render_jobs ·
            // separation_jobs · credit_transactions(SET NULL — 익명 분석/감사 보존) 정리.
            //
            // TODO(apple): Apple Sign In revocation API 호출 추가 — `POST https://appleid.apple.com/auth/revoke`
            //   로 refresh token 폐기 필요. App Store 가이드라인 5.1.1(v) 권장.
            val deleted = withContext(Dispatchers.IO) { userRepository.delete(principal.userId) }
            log.info("account deleted: user={} rows={}", principal.userId, deleted)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
