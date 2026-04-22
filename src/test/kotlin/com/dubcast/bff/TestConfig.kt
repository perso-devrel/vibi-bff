package com.dubcast.bff

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.config.PersoConfig
import com.dubcast.bff.config.SeparationConfig
import com.dubcast.bff.config.StorageConfig

fun testAppConfig(
    storagePath: String,
    elevenLabsKey: String = "test-key",
    baseUrl: String = "http://localhost:8080",
): AppConfig = AppConfig(
    elevenLabs = ElevenLabsConfig(apiKey = elevenLabsKey, baseUrl = "https://api.elevenlabs.io"),
    storage = StorageConfig(basePath = storagePath),
    baseUrl = baseUrl,
    perso = PersoConfig(
        apiKey = "pk_test_xxxxxxxxxxxxxxxxxxxx",
        baseUrl = "https://api.perso.ai",
        spaceSeq = 1,
        pollIntervalMs = 5000,
        maxPollMinutes = 30,
    ),
    separation = SeparationConfig(
        abandonTtlMs = 1_800_000,
        mixTtlMs = 600_000,
        signingSecret = "a".repeat(64),
        urlTtlSec = 1_800,
        mixUrlTtlSec = 600,
    ),
)
