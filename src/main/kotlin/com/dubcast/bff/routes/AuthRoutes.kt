package com.dubcast.bff.routes

import com.dubcast.bff.model.GoogleAuthRequest
import com.dubcast.bff.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Google OAuth 로그인 게이트웨이.
 *
 * 모바일 클라이언트(iOS GoogleSignIn / Android Credential Manager)가 native SDK 로
 * 받은 ID Token 을 BFF 에 전달하면, BFF 가 Google `tokeninfo` 로 검증 후 자체 access
 * token (HS256 JWT) 을 발급한다.
 *
 * 사용자 record 는 영속화하지 않음 (JWT claim 에 sub/email/name/picture 보존).
 */
fun Route.authRoutes(authService: AuthService) {
    route("/auth") {
        post("/google") {
            val req = call.receive<GoogleAuthRequest>()
            val user = authService.verifyGoogleIdToken(req.idToken)
            val response = authService.issueAccessToken(user)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
