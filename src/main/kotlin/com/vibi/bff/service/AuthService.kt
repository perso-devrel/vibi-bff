package com.vibi.bff.service

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.vibi.bff.config.AuthConfig
import com.vibi.bff.model.AuthProvider
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
import java.net.URI
import java.net.URLEncoder
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * JDBC blocking + JWKS 동기 HTTP 는 Netty event loop 를 잡지 않도록 `Dispatchers.IO`.
 */
class AuthService(
    private val config: AuthConfig,
    private val httpClient: HttpClient,
    private val userRepository: UserRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    private val appleJwkProvider by lazy {
        // Apple 의 `/.well-known/jwks.json` 은 `http://www.apple.com/filenotfound` 로 302 (HTTPS→HTTP
        // downgrade 라 JDK 가 추적 안 함). 실제 JWKS 는 `/auth/keys`. domain-string 생성자가 .well-known
        // 경로를 자동 부착하므로 URL 명시 오버로드 사용.
        JwkProviderBuilder(URI("https://appleid.apple.com/auth/keys").toURL())
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
        // Apple 과 대칭 — claim 누락 또는 'false' 모두 reject (fail-closed). Google
        // tokeninfo 가 향후 schema 변경으로 일부 계정에서 claim 을 omit 하거나, forged
        // token 이 통과한 경우의 silent 통과를 차단. 정상 Google 토큰은 항상 "true".
        if (info.emailVerified == null || !info.emailVerified.equals("true", ignoreCase = true)) {
            log.warn("Google email_verified claim invalid: value={} sub={}", info.emailVerified, info.sub)
            throw ApiErrorException(HttpStatusCode.Unauthorized, "google_email_unverified")
        }

        return completeSignIn(
            provider = AuthProvider.GOOGLE,
            providerSub = info.sub,
            email = info.email,
            name = info.name ?: info.email.substringBefore('@'),
            picture = info.picture,
        )
    }

    /**
     * Apple Sign In ID Token 검증.
     *
     * @param fullName Apple 의 최초-1회 fullName. 신규 가입 시에만 user.name 으로 사용 —
     *   재로그인 시 null 이 정상이고, 기존 row 의 name 은 보존된다.
     */
    suspend fun verifyAppleIdToken(idToken: String, fullName: String?): AuthUser {
        if (idToken.isBlank()) {
            throw ApiErrorException(HttpStatusCode.BadRequest, "missing_id_token")
        }
        if (config.appleClientIds.isEmpty()) {
            log.warn("Apple sign-in attempted but APPLE_OAUTH_CLIENT_IDS is not configured")
            throw ApiErrorException(HttpStatusCode.ServiceUnavailable, "apple_signin_disabled")
        }

        val verified = verifyAppleSignatureBlocking(idToken)

        // 서명 통과 후 claims 를 직접 검사 — 라이브러리 내부 message 문구 의존하지 않도록.
        if (verified.issuer != APPLE_ISSUER) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_iss_mismatch")
        }
        if (verified.audience.orEmpty().none { it in config.appleClientIds }) {
            throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_aud_mismatch")
        }

        val sub = verified.subject
            ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_id_token")
        val email = verified.getClaim("email").asString()
            ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_email_missing")

        // Google 의 emailVerified 와 동일 패턴 — Apple ID Token 의 email_verified 는 사실상
        // 항상 true 지만 (이메일 공유 / 사설 릴레이 모두 verified), defense-in-depth 차원에서
        // 명시 거부. boolean 외 string ("true"/"false") 으로 오는 케이스도 흡수.
        // claim 자체가 누락된 토큰은 fail-closed — Apple 이 향후 schema 변경으로 누락된
        // 토큰을 보내거나 forged 토큰이 통과한 경우의 silent 통과를 차단.
        // 누락 / unrecognized 케이스는 reject 전에 warn 로그 — production 에서 실제 Apple
        // 토큰에 누락이 발생하는지 모니터링.
        val emailVerifiedClaim = verified.getClaim("email_verified")
        val emailVerifiedBoolean = emailVerifiedClaim.asBoolean()
        val emailVerifiedString = emailVerifiedClaim.asString()
        val emailVerified = emailVerifiedBoolean
            ?: emailVerifiedString?.equals("true", ignoreCase = true)
            ?: false
        if (!emailVerified) {
            if (emailVerifiedBoolean == null && emailVerifiedString == null) {
                log.warn("Apple ID Token missing email_verified claim — fail-closed reject sub={}", sub)
            } else {
                log.info("Apple email_verified claim is false-equivalent: bool={} str={}", emailVerifiedBoolean, emailVerifiedString)
            }
            throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_email_unverified")
        }

        return completeSignIn(
            provider = AuthProvider.APPLE,
            providerSub = sub,
            email = email,
            name = fullName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@'),
            picture = null,
        )
    }

    private suspend fun verifyAppleSignatureBlocking(idToken: String): DecodedJWT =
        withContext(Dispatchers.IO) {
            val decoded = try {
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
            try {
                JWT.require(Algorithm.RSA256(publicKey, null)).build().verify(idToken)
            } catch (e: TokenExpiredException) {
                throw ApiErrorException(HttpStatusCode.Unauthorized, "apple_token_expired")
            } catch (e: JWTVerificationException) {
                log.warn("Apple id token signature verify failed: {}", e.message)
                throw ApiErrorException(HttpStatusCode.Unauthorized, "invalid_id_token")
            }
        }

    private suspend fun completeSignIn(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): AuthUser {
        val upserted = withContext(Dispatchers.IO) {
            userRepository.upsert(provider, providerSub, email, name, picture)
        }
        return AuthUser(
            sub = upserted.id.toString(),
            email = email,
            name = name,
            picture = picture,
            role = upserted.role,
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
            .withClaim("role", user.role)
            .apply { user.picture?.let { withClaim("picture", it) } }
            .withIssuedAt(Date(nowMs))
            .withExpiresAt(Date(expiresAtMs))
            .sign(algorithm)
        return AuthResponse(accessToken = token, expiresAt = expiresAtMs, user = user)
    }

    companion object {
        // plugins/Auth.kt 의 verifier 가 동일 issuer 로 검증 — 변경 시 두 곳 동시 갱신.
        const val ISSUER = "vibi-bff"
        private const val APPLE_ISSUER = "https://appleid.apple.com"
    }
}
