package com.dubcast.bff

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.config.GeminiConfig
import com.dubcast.bff.config.PersoConfig
import com.dubcast.bff.config.SeparationConfig
import com.dubcast.bff.config.StorageConfig

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
)
