package com.vibi.bff

import com.vibi.bff.config.AppConfig
import com.vibi.bff.config.AuthConfig
import com.vibi.bff.config.GeminiConfig
import com.vibi.bff.config.PersoConfig
import com.vibi.bff.config.SeparationConfig
import com.vibi.bff.config.StorageConfig

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
    storage = StorageConfig(basePath = storagePath),
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
        jwtSecret = "a".repeat(64),
        jwtExpirySeconds = 3_600,
    ),
)
