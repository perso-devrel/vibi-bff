package com.dubcast.bff

import com.dubcast.bff.config.loadConfig
import com.dubcast.bff.plugins.*
import com.dubcast.bff.service.AutoDubService
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.GeminiClient
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaSourceResolver
import com.dubcast.bff.service.PersoClient
import com.dubcast.bff.service.RenderInputCacheService
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
            // NONE in INFO would still dump XP-API-KEY headers and SAS URLs
            // (which are credentials themselves) at higher levels. Keep
            // HTTP-level logging off; services log their own sanitized lines.
            level = LogLevel.NONE
        }
        install(HttpTimeout) {
            // 큰 영상 (수십~수백 MB) Perso 업로드는 socket-level 으로 1+분 걸릴 수 있어 longer.
            connectTimeoutMillis = 120_000  // 2분 — TLS handshake + SAS upload init 여유
            requestTimeoutMillis = 600_000  // 10분 — 큰 영상 stream upload 여유
            socketTimeoutMillis = 600_000   // 10분 — read/write idle 허용
        }
        // CIO engine 의 endpoint connect attempts — timeout 만 늘리지 말고 재시도도 enable.
        engine {
            requestTimeout = 600_000
            endpoint {
                connectAttempts = 3
                connectTimeout = 120_000
            }
        }
    }

    val fileStorage = FileStorageService(appConfig.storage)

    // Concurrency cap for ffmpeg fan-out. RENDER_MAX_CONCURRENT can be set
    // explicitly in deployments where the autoreckoned (CPU/2) value is wrong
    // (containerized hosts often misreport availableProcessors).
    val renderMaxConcurrent = System.getenv("RENDER_MAX_CONCURRENT")?.toIntOrNull()
        ?: RenderService.defaultMaxConcurrent()
    val renderService = RenderService(fileStorage.renderDir, maxConcurrentRenders = renderMaxConcurrent)

    // Shared input cache. RENDER_INPUT_CACHE_TTL_HOURS overrides the 24h default
    // when a deployment needs longer reuse windows (long-running mobile session
    // editing N variants over hours).
    val renderInputCacheTtlHours = System.getenv("RENDER_INPUT_CACHE_TTL_HOURS")?.toLongOrNull() ?: 24L
    val renderInputCache = RenderInputCacheService(
        baseDir = File(fileStorage.renderDir.parentFile, "render-input-cache"),
        ttlMs = TimeUnit.HOURS.toMillis(renderInputCacheTtlHours),
    )
    val cacheCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    cacheCleanupScope.launch {
        // Sweep once on startup (recover from crash-leftover entries), then
        // hourly. Sleeping 1h between sweeps is fine — TTL is 24h, slop tolerated.
        while (isActive) {
            runCatching { renderInputCache.cleanExpired() }
            delay(TimeUnit.HOURS.toMillis(1))
        }
    }

    val persoClient = PersoClient(appConfig.perso, httpClient)
    org.slf4j.LoggerFactory.getLogger("BootCheck").info(
        "Perso config: baseUrl={} spaceSeq={} pollIntervalMs={}",
        appConfig.perso.baseUrl, appConfig.perso.spaceSeq, appConfig.perso.pollIntervalMs
    )
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

    // Phase 1: subtitles / autodub / separation 의 source 결정자.
    // multipart `file` 또는 spec.editedRenderJobId 둘 중 하나로 source 해석.
    // editedRenderJobId 경유 시 RenderService 가 owner — resolver 가 별도 디렉터리에
    // 복사한 owned-copy 를 반환해 downstream 의 delete/rename 으로부터 원본 보호.
    val mediaSourceResolver = MediaSourceResolver(renderService, fileStorage.editedSourceDir)

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
        fileStorage, persoClient, appConfig, renderService,
        separationService, stemMixService, signedUrlService,
        autoSubtitleService, autoDubService, geminiClient, httpClient, renderInputCache,
        mediaSourceResolver,
    )

    val shutdownHooks: List<() -> Unit> = listOf(
        httpClient::close,
        renderService::shutdown,
        separationService::shutdown,
        stemMixService::shutdown,
        autoSubtitleService::shutdown,
        autoDubService::shutdown,
        { cacheCleanupScope.cancel() },
    )
    monitor.subscribe(ApplicationStopped) {
        shutdownHooks.forEach { it() }
    }
}
