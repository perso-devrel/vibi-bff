package com.vibi.bff.service

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
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
import java.net.URLEncoder
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * OAuth ID token 검증 + 자체 access token (HS256 JWT) 발급.
 *
 * 두 provider 를 동일 패턴으로 처리하지만 검증 방식이 다르다:
 * - **Google** — `oauth2.googleapis.com/tokeninfo` 단일 호출. Google 이 검증 후 claims
 *   응답을 돌려준다. JWKS 다운로드 / RSA 검증을 피해 의존성 최소화. `aud` 만 재확인.
 * - **Apple** — JWKS (`appleid.apple.com/auth/keys`) 에서 public key 받아 RS256 직접 검증.
 *   `jwks-rsa` 의 24h cache + rate-limit 으로 외부 호출 부담 완화.
 *
 * 검증 통과 후 [UserRepository] 에 `(provider, providerSub)` 기준 upsert →
 * internal UUID 를 JWT `sub` 로 발급. 모바일 클라이언트는 그 UUID 를 user 식별자로 사용.
 */
class AuthService(
    private val config: AuthConfig,
    private val httpClient: HttpClient,
    private val userRepository: UserRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    // Apple JWKS provider — 24h cache + 1m rate-limit. Apple 의 key rotation 은 매우 드물고
    // 클라가 한 번에 받은 token 의 kid 가 바뀔 일 없으므로 캐시 길게.
    private val appleJwkProvider by lazy {
        JwkProviderBuilder("https://appleid.apple.com/")
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
    }

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

        val displayName = info.name ?: info.email.substringBefore('@')
        val uuid = userRepository.upsert(
            provider = PROVIDER_GOOGLE,
            providerSub = info.sub,
            email = info.email,
            name = displayName,
            picture = info.picture,
        )
        return AuthUser(
            sub = uuid.toString(),
            email = info.email,
            name = displayName,
            picture = info.picture,
        )
    }

    /**
     * Apple Sign In ID Token 검증.
     *
     * @param fullName Apple 의 최초-1회 fullName. 신규 가입 시에만 user.name 으로 사용 —
     *   재로그인 시 null 이 정상이고, 기존 row 의 name 은 보존된다.
     */
    fun verifyAppleIdToken(idToken: String, fullName: String?): AuthUser {
        if (idToken.isBlank()) {
            throw ApiErrorException(HttpStatusCode.BadRequest, "missing_id_token")
        }
        if (config.appleClientIds.isEmpty()) {
            log.warn("Apple sign-in attempted but APPLE_OAUTH_CLIENT_IDS is not configured")
            throw ApiErrorException(HttpStatusCode.ServiceUnavailable, "apple_signin_disabled")
        }

        val decoded: DecodedJWT = try {
            JWT.decode(idToken)
        } catch (e: JWTVerificationException) {
            log.warn("Apple id token decode failed: {}", e.message)
            throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_id_token")
        }

        val publicKey = try {
            appleJwkProvider.get(decoded.keyId).publicKey as RSAPublicKey
        } catch (e: JwkException) {
            log.warn("Apple JWKS lookup failed for kid={}: {}", decoded.keyId, e.message)
            throw ApiErrorException(HttpStatusCode.BadGateway, "apple_jwks_unavailable")
        }

        val verifier = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(APPLE_ISSUER)
            .withAnyOfAudience(*config.appleClientIds.toTypedArray())
            .build()
        val verified: DecodedJWT = try {
            verifier.verify(idToken)
        } catch (e: TokenExpiredException) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_token_expired")
        } catch (e: JWTVerificationException) {
            log.warn("Apple id token verify failed: {}", e.message)
            // aud 불일치 / iss 불일치 / 서명 실패 모두 여기로 — 메시지에서 구분.
            val code = when {
                e.message?.contains("audience", ignoreCase = true) == true -> "apple_aud_mismatch"
                e.message?.contains("issuer", ignoreCase = true) == true -> "apple_iss_mismatch"
                else -> "invalid_id_token"
            }
            throw ApiErrorException(HttpStatusCode.Unauthorized, code)
        }

        val sub = verified.subject ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_id_token")
        val email = verified.getClaim("email").asString()
        if (email.isNullOrBlank()) {
            // Apple 정책상 email 은 사용자가 명시 거부해도 첫 인증 시 제공되어야 함.
            // 누락은 비정상 — 정책 변경 시 fallback (sub@apple.local 등) 도입 검토.
            throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_email_missing")
        }
        val displayName = fullName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')

        val uuid = userRepository.upsert(
            provider = PROVIDER_APPLE,
            providerSub = sub,
            email = email,
            name = displayName,
            picture = null,
        )
        return AuthUser(
            sub = uuid.toString(),
            email = email,
            name = displayName,
            picture = null,
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
        private const val APPLE_ISSUER = "https://appleid.apple.com"
        private const val PROVIDER_GOOGLE = "google"
        private const val PROVIDER_APPLE = "apple"
    }
}
