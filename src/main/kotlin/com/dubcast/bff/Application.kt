package com.dubcast.bff

import com.dubcast.bff.config.loadConfig
import com.dubcast.bff.plugins.*
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.RenderService
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

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(AppJson)
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 60_000
            requestTimeoutMillis = 300_000
        }
    }

    val fileStorage = FileStorageService(appConfig.storage).also { it.init() }
    val elevenLabsClient = ElevenLabsClient(appConfig.elevenLabs, httpClient)
    val renderService = RenderService(fileStorage.renderDir).also { it.init() }

    install(CallLogging) {
        level = Level.INFO
    }

    configureSerialization()
    configureCors()
    configureErrorHandling()
    configureRouting(fileStorage, elevenLabsClient, appConfig, renderService)

    monitor.subscribe(ApplicationStopped) {
        httpClient.close()
        renderService.shutdown()
    }
}
