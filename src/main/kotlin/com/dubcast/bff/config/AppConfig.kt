package com.dubcast.bff.config

import io.ktor.server.config.*

data class AppConfig(
    val elevenLabs: ElevenLabsConfig,
    val storage: StorageConfig,
    val baseUrl: String,
    val perso: PersoConfig,
    val separation: SeparationConfig,
)

data class ElevenLabsConfig(
    val apiKey: String,
    val baseUrl: String,
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
    val el = dubcast.config("elevenlabs")
    val storage = dubcast.config("storage")
    val perso = dubcast.config("perso")
    val separation = dubcast.config("separation")

    return AppConfig(
        elevenLabs = ElevenLabsConfig(
            apiKey = el.property("apiKey").getString(),
            baseUrl = el.property("baseUrl").getString(),
        ),
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
        separation = SeparationConfig(
            abandonTtlMs = separation.property("abandonTtlMs").getString().toLong(),
            mixTtlMs = separation.property("mixTtlMs").getString().toLong(),
            signingSecret = separation.property("signingSecret").getString(),
            urlTtlSec = separation.property("urlTtlSec").getString().toLong(),
            mixUrlTtlSec = separation.property("mixUrlTtlSec").getString().toLong(),
        ),
    )
}
