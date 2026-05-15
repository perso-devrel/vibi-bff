package com.vibi.bff.routes

import com.vibi.bff.model.AppleAuthRequest
import com.vibi.bff.model.GoogleAuthRequest
import com.vibi.bff.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * 소셜 로그인 게이트웨이.
 *
 * 모바일 클라이언트(iOS GoogleSignIn / Apple AuthenticationServices / Android Credential
 * Manager)가 native SDK 로 받은 ID Token 을 BFF 에 전달하면, BFF 가 provider 별 검증
 * (Google `tokeninfo` / Apple JWKS) 후 user 테이블 upsert → 자체 access token (HS256 JWT)
 * 을 발급한다. JWT 의 `sub` 는 BFF internal UUID (provider sub 가 아님) — IAP
 * `appAccountToken` 으로도 그대로 재사용된다.
 */
fun Route.authRoutes(authService: AuthService) {
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
    }
}
