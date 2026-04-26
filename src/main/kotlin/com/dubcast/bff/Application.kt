package com.dubcast.bff

import com.dubcast.bff.config.loadConfig
import com.dubcast.bff.plugins.*
import com.dubcast.bff.service.AutoDubService
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.GeminiClient
import com.dubcast.bff.service.PersoClient
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import java.io.File
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import org.slf4j.event.Level

fun main(args: Array<String>) {
    loadDotenv()
    EngineMain.main(args)
}

private fun loadDotenv() {
    val dotenv = io.github.cdimascio.dotenv.dotenv {
        ignoreIfMissing = true
    }
    dotenv.entries().forEach { entry ->
        if (System.getenv(entry.key) == null) {
            System.setProperty(entry.key, entry.value)
        }
    }
}

fun Application.module() {
    val appConfig = loadConfig(environment.config)

    require(appConfig.elevenLabs.apiKey.isNotBlank()) {
        "ELEVENLABS_API_KEY environment variable must be set"
    }
    require(appConfig.perso.apiKey.isNotBlank()) {
        "PERSO_API_KEY environment variable must be set"
    }
    // Vertex AI / Gemini credentials are validated lazily on the first
    // translation call, so the server can boot without them in dev when
    // subtitle translation isn't being exercised.

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(AppJson)
        }
        install(Logging) {
            // NONE in INFO would still dump XP-API-KEY / xi-api-key headers
            // and SAS URLs (which are credentials themselves) at higher
            // levels. Keep HTTP-level logging off; services log their own
            // sanitized lines.
            level = LogLevel.NONE
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 60_000
            requestTimeoutMillis = 300_000
        }
    }

    val fileStorage = FileStorageService(appConfig.storage)
    val elevenLabsClient = ElevenLabsClient(appConfig.elevenLabs, httpClient)
    val renderService = RenderService(fileStorage.renderDir)

    val persoClient = PersoClient(appConfig.perso, httpClient)
    val signedUrlService = SignedUrlService(appConfig.separation.signingSecret)
    val separationService = SeparationService(
        persoClient = persoClient,
        separationDir = fileStorage.separationDir,
        config = appConfig.separation,
        pollIntervalMs = appConfig.perso.pollIntervalMs,
        maxPollMinutes = appConfig.perso.maxPollMinutes,
    )
    val stemMixService = StemMixService(
        mixDir = File(fileStorage.separationDir, "mix"),
        mixTtlMs = appConfig.separation.mixTtlMs,
    )

    val geminiClient = GeminiClient(appConfig.gemini, httpClient)
    val autoSubtitleService = AutoSubtitleService(
        persoClient = persoClient,
        geminiClient = geminiClient,
        outputDir = File(fileStorage.separationDir.parentFile, "subtitles"),
        pollIntervalMs = appConfig.perso.pollIntervalMs,
        maxPollMinutes = appConfig.perso.maxPollMinutes,
    )
    val autoDubService = AutoDubService(
        persoClient = persoClient,
        outputDir = File(fileStorage.separationDir.parentFile, "autodub"),
        pollIntervalMs = appConfig.perso.pollIntervalMs,
        maxPollMinutes = appConfig.perso.maxPollMinutes,
    )

    install(CallLogging) {
        level = Level.INFO
        // Never log signed-URL tokens in plain text — they're short-lived but
        // log aggregators retain longer than the TTL.
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val rawPath = call.request.path()
            val rawQuery = call.request.queryString()
            val maskedQuery = rawQuery.replace(Regex("(token=)[^&]+"), "$1***")
            val suffix = if (maskedQuery.isBlank()) "" else "?$maskedQuery"
            "$status: $method $rawPath$suffix"
        }
    }

    configureSerialization()
    configureCors()
    configureErrorHandling()
    configureRouting(
        fileStorage, elevenLabsClient, appConfig, renderService,
        separationService, stemMixService, signedUrlService,
        autoSubtitleService, autoDubService,
    )

    val shutdownHooks: List<() -> Unit> = listOf(
        httpClient::close,
        renderService::shutdown,
        separationService::shutdown,
        stemMixService::shutdown,
        autoSubtitleService::shutdown,
        autoDubService::shutdown,
    )
    monitor.subscribe(ApplicationStopped) {
        shutdownHooks.forEach { it() }
    }
}
