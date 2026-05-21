package com.vibi.bff

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.config.AdminConfig
import com.vibi.bff.config.AppConfig
import com.vibi.bff.config.AuthConfig
import com.vibi.bff.config.DbConfig
import com.vibi.bff.config.GeminiConfig
import com.vibi.bff.config.IapConfig
import com.vibi.bff.config.PersoConfig
import com.vibi.bff.config.SeparationConfig
import com.vibi.bff.config.StorageConfig
import com.vibi.bff.service.AuthService
import java.util.Date
import java.util.UUID

/**
 * ffmpeg/ffprobe 가 PATH 에 있는지 best-effort 체크. CI/local 에서 둘 다 없을 때
 * 관련 테스트를 fail 대신 skip 하기 위함. `Assumptions.assumeTrue(ffmpegAvailable())`
 * 로 사용.
 */
fun ffmpegAvailable(): Boolean = runCatching {
    val proc = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
    proc.waitFor()
    proc.exitValue() == 0
}.getOrDefault(false)

fun testAppConfig(
    storagePath: String,
    baseUrl: String = "http://localhost:8080",
): AppConfig = AppConfig(
    storage = StorageConfig(
        basePath = storagePath,
        r2Bucket = "",
        r2 = null,
        signedUrlTtlSec = 900,
    ),
    baseUrl = baseUrl,
    perso = PersoConfig(
        apiKey = "pk_test_xxxxxxxxxxxxxxxxxxxx",
        baseUrl = "https://api.perso.ai",
        storageBaseUrl = "https://portal-media.perso.ai",
        spaceSeq = 1,
        pollIntervalMs = 5000,
        maxPollMinutes = 30,
        downloadAllowedHosts = setOf("portal-media.perso.ai"),
    ),
    gemini = GeminiConfig(
        projectId = "",
        location = "us-central1",
        credentialsPath = "",
        model = "gemini-2.5-flash",
    ),
    separation = SeparationConfig(
        abandonTtlMs = 1_800_000,
        mixTtlMs = 600_000,
        signingSecret = "a".repeat(64),
        urlTtlSec = 1_800,
        mixUrlTtlSec = 600,
    ),
    auth = AuthConfig(
        googleClientIds = listOf("test-client-id.apps.googleusercontent.com"),
        appleClientIds = emptyList(),
        jwtSecret = "a".repeat(64),
        jwtExpirySeconds = 3_600,
    ),
    db = DbConfig(
        jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
        maxPoolSize = 2,
    ),
    admin = AdminConfig(slug = ""),
    iap = IapConfig(apple = null, google = null),
)

/**
 * 라우트 owner 검증 회귀 테스트용 헬퍼. `plugins/Auth.kt#requireUser` 가 검증하는 형태
 * (issuer = AuthService.ISSUER, sub = UUID, role 클레임) 와 동일하게 발급.
 * jwtSecret 기본값은 testAppConfig 와 동일.
 */
fun issueTestJwt(
    userId: UUID,
    jwtSecret: String = "a".repeat(64),
    role: String = "user",
    expirySeconds: Long = 3_600,
): String {
    val now = System.currentTimeMillis()
    return JWT.create()
        .withIssuer(AuthService.ISSUER)
        .withSubject(userId.toString())
        .withClaim("role", role)
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(now + expirySeconds * 1000L))
        .sign(Algorithm.HMAC256(jwtSecret))
}
