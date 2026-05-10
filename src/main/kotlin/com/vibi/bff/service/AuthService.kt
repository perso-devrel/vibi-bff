package com.vibi.bff.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.config.AuthConfig
import com.vibi.bff.model.AuthResponse
import com.vibi.bff.model.AuthUser
import com.vibi.bff.model.GoogleTokenInfo
import com.vibi.bff.plugins.ApiErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.Date

/**
 * Google OAuth ID token 검증 + 자체 access token (HS256 JWT) 발급.
 *
 * 검증은 Google `tokeninfo` endpoint (`https://oauth2.googleapis.com/tokeninfo`)
 * 한 호출로 처리 — JWKS 다운로드 / RSA 검증을 피해 의존성 최소화. Google 이 직접 검증해
 * 응답을 돌려주므로 결과를 신뢰. 단 `aud` 가 우리가 신뢰하는 client id 인지 다시 확인 필수
 * (다른 앱의 token 으로 우리 사용자를 사칭하는 것 방지).
 */
class AuthService(
    private val config: AuthConfig,
    private val httpClient: HttpClient,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    suspend fun verifyGoogleIdToken(idToken: String): AuthUser {
        if (idToken.isBlank()) {
            throw ApiErrorException(HttpStatusCode.BadRequest, "missing_id_token")
        }
        val encoded = URLEncoder.encode(idToken, Charsets.UTF_8)
        val response = httpClient.get("https://oauth2.googleapis.com/tokeninfo?id_token=$encoded")
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(200)
            log.warn("Google tokeninfo returned {}: {}", response.status.value, body)
            throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_id_token")
        }
        val info: GoogleTokenInfo = response.body()

        if (info.aud !in config.googleClientIds) {
            log.warn("Google id token aud mismatch: got={} allowed={}", info.aud, config.googleClientIds)
            throw ApiErrorException(HttpStatusCode.Unauthorized, "google_aud_mismatch")
        }
        val expMs = (info.exp.toLongOrNull() ?: 0L) * 1000L
        if (expMs <= clock()) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "google_token_expired")
        }
        if (info.emailVerified != null && info.emailVerified.equals("false", ignoreCase = true)) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "google_email_unverified")
        }

        return AuthUser(
            sub = info.sub,
            email = info.email,
            name = info.name ?: info.email.substringBefore('@'),
            picture = info.picture,
        )
    }

    fun issueAccessToken(user: AuthUser): AuthResponse {
        val nowMs = clock()
        val expiresAtMs = nowMs + config.jwtExpirySeconds * 1000L
        val token = JWT.create()
            .withIssuer(ISSUER)
            .withSubject(user.sub)
            .withClaim("email", user.email)
            .withClaim("name", user.name)
            .apply { user.picture?.let { withClaim("picture", it) } }
            .withIssuedAt(Date(nowMs))
            .withExpiresAt(Date(expiresAtMs))
            .sign(algorithm)
        return AuthResponse(accessToken = token, expiresAt = expiresAtMs, user = user)
    }

    companion object {
        private const val ISSUER = "vibi-bff"
    }
}
