package com.dubcast.bff.config

import io.ktor.server.config.*

data class AppConfig(
    val storage: StorageConfig,
    val baseUrl: String,
    val perso: PersoConfig,
    val gemini: GeminiConfig,
    val separation: SeparationConfig,
    val auth: AuthConfig,
)

data class StorageConfig(
    val basePath: String,
)

data class PersoConfig(
    val apiKey: String,
    val baseUrl: String,
    val spaceSeq: Int,
    val pollIntervalMs: Long,
    val maxPollMinutes: Int,
) {
    init {
        require(apiKey.isNotBlank()) { "PERSO_API_KEY must not be blank" }
        require(spaceSeq > 0) { "PERSO_SPACE_SEQ must be > 0 (got $spaceSeq)" }
        require(pollIntervalMs >= 1000) { "PERSO_POLL_INTERVAL_MS must be >= 1000 (got $pollIntervalMs)" }
        require(maxPollMinutes in 1..120) { "PERSO_MAX_POLL_MINUTES must be in 1..120 (got $maxPollMinutes)" }
    }
}

/**
 * Vertex AI configuration. We authenticate via a GCP service account JSON
 * (path lives in [credentialsPath], typically the same file Google's tooling
 * already expects under `GOOGLE_APPLICATION_CREDENTIALS`). Validation is
 * deferred to [com.dubcast.bff.service.GeminiClient]'s first call so the
 * server can boot even when subtitle translation is disabled in dev.
 */
data class GeminiConfig(
    val projectId: String,
    val location: String,
    val credentialsPath: String,
    val model: String,
) {
    init {
        require(model.isNotBlank()) { "GEMINI_MODEL must not be blank" }
        require(location.isNotBlank()) { "GCP_LOCATION must not be blank" }
    }
}

/**
 * Google OAuth + 자체 JWT 발급 설정.
 *
 * - [googleClientIds] — `tokeninfo` 응답의 `aud` 가 이 중 하나와 일치해야 통과.
 *   콤마 분리 문자열로 env 주입 (iOS / Android / Web client id 모두 허용).
 * - [jwtSecret] — HMAC-SHA256 서명 키. 32+ chars (`openssl rand -hex 32`).
 * - [jwtExpirySeconds] — 발급된 access token 의 만료까지 초.
 */
data class AuthConfig(
    val googleClientIds: List<String>,
    val jwtSecret: String,
    val jwtExpirySeconds: Long,
) {
    init {
        require(googleClientIds.isNotEmpty()) { "GOOGLE_OAUTH_CLIENT_IDS must not be empty (comma-separated)" }
        require(googleClientIds.all { it.isNotBlank() }) { "GOOGLE_OAUTH_CLIENT_IDS contains blank entry" }
        require(jwtSecret.length >= 32) {
            "AUTH_JWT_SECRET must be at least 32 chars (got ${jwtSecret.length}). " +
                "Generate with: openssl rand -hex 32"
        }
        require(jwtExpirySeconds in 60..(90L * 24 * 3600)) {
            "AUTH_JWT_EXPIRY_SECONDS must be in 60..7776000 (got $jwtExpirySeconds)"
        }
    }
}

data class SeparationConfig(
    val abandonTtlMs: Long,
    val mixTtlMs: Long,
    val signingSecret: String,
    val urlTtlSec: Long,
    val mixUrlTtlSec: Long,
) {
    init {
        require(signingSecret.length >= 32) {
            "SEPARATION_SIGNING_SECRET must be at least 32 chars (got ${signingSecret.length}). " +
                "Generate with: openssl rand -hex 32"
        }
        require(abandonTtlMs >= 60_000) { "SEPARATION_ABANDON_TTL_MS must be >= 60000 (got $abandonTtlMs)" }
        require(mixTtlMs >= 60_000) { "SEPARATION_MIX_TTL_MS must be >= 60000 (got $mixTtlMs)" }
        require(urlTtlSec in 60..86_400) { "SEPARATION_URL_TTL_SEC must be in 60..86400 (got $urlTtlSec)" }
        require(mixUrlTtlSec in 60..86_400) { "SEPARATION_MIX_URL_TTL_SEC must be in 60..86400 (got $mixUrlTtlSec)" }
    }
}

fun loadConfig(config: ApplicationConfig): AppConfig {
    val dubcast = config.config("dubcast")
    val storage = dubcast.config("storage")
    val perso = dubcast.config("perso")
    val gemini = dubcast.config("gemini")
    val separation = dubcast.config("separation")
    val auth = dubcast.config("auth")

    return AppConfig(
        storage = StorageConfig(
            basePath = storage.property("basePath").getString(),
        ),
        baseUrl = dubcast.property("baseUrl").getString(),
        perso = PersoConfig(
            apiKey = perso.property("apiKey").getString(),
            baseUrl = perso.property("baseUrl").getString(),
            spaceSeq = perso.property("spaceSeq").getString().toInt(),
            pollIntervalMs = perso.property("pollIntervalMs").getString().toLong(),
            maxPollMinutes = perso.property("maxPollMinutes").getString().toInt(),
        ),
        gemini = GeminiConfig(
            projectId = gemini.property("projectId").getString(),
            location = gemini.property("location").getString(),
            credentialsPath = gemini.property("credentialsPath").getString(),
            model = gemini.property("model").getString(),
        ),
        separation = SeparationConfig(
            abandonTtlMs = separation.property("abandonTtlMs").getString().toLong(),
            mixTtlMs = separation.property("mixTtlMs").getString().toLong(),
            signingSecret = separation.property("signingSecret").getString(),
            urlTtlSec = separation.property("urlTtlSec").getString().toLong(),
            mixUrlTtlSec = separation.property("mixUrlTtlSec").getString().toLong(),
        ),
        auth = AuthConfig(
            googleClientIds = auth.property("googleClientIds").getString()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            jwtSecret = auth.property("jwtSecret").getString(),
            jwtExpirySeconds = auth.property("jwtExpirySeconds").getString().toLong(),
        ),
    )
}
