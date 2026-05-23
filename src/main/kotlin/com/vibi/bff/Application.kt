package com.vibi.bff

import com.vibi.bff.config.loadConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.plugins.*
import io.sentry.Sentry
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.ExternalApiCallsRepository
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.JobAnalyticsRepository
import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.RenderInputCacheService
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.StemMixService
import com.vibi.bff.service.UserRepository
import com.vibi.bff.service.iap.AppleReceiptVerifier
import com.vibi.bff.service.iap.GoogleReceiptVerifier
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

private val MASKED_QUERY_PARAMS = Regex("(token|id_token|access_token|code)=[^&]+")

/**
 * Sentry 부트스트랩. `SENTRY_DSN_BFF` env 가 비면 no-op — dev/test 에서 운영 Sentry 프로젝트
 * 오염 안 함. `SENTRY_ENV` (e.g. "prod", "staging") 와 `SENTRY_TRACES_SAMPLE_RATE` (기본 0.1)
 * 도 env 만.
 */
private fun initSentry() {
    val dsn = System.getenv("SENTRY_DSN_BFF")?.takeIf { it.isNotBlank() } ?: return
    val env = System.getenv("SENTRY_ENV")?.takeIf { it.isNotBlank() } ?: "dev"
    val tracesRate = System.getenv("SENTRY_TRACES_SAMPLE_RATE")?.toDoubleOrNull() ?: 0.1
    Sentry.init { options ->
        options.dsn = dsn
        options.environment = env
        options.tracesSampleRate = tracesRate
        options.isAttachStacktrace = true
        // SDK release 식별자. CI 가 SENTRY_RELEASE 로 git SHA 주입 권장.
        System.getenv("SENTRY_RELEASE")?.takeIf { it.isNotBlank() }?.let { options.release = it }
    }
}

// real env > existing system property > .env
private fun loadDotenv() {
    val dotenv = io.github.cdimascio.dotenv.dotenv {
        ignoreIfMissing = true
    }
    dotenv.entries().forEach { entry ->
        if (System.getenv(entry.key) == null && System.getProperty(entry.key) == null) {
            System.setProperty(entry.key, entry.value)
        }
    }
}

fun Application.module() {
    // Sentry init — module() 초반에 두어 이후 모든 boot 단계 예외도 캡처. DSN 미설정이면
    // init no-op 라 dev/test 무영향. 본 호출 자체는 Sentry-internal global state 만 만지므로
    // application 전체 lifecycle 에 영향 없음.
    initSentry()

    val appConfig = loadConfig(environment.config)

    require(appConfig.perso.apiKey.isNotBlank()) {
        "PERSO_API_KEY environment variable must be set"
    }
    // AuthConfig 자체 init { } 가 GOOGLE_OAUTH_CLIENT_IDS / AUTH_JWT_SECRET 길이를
    // 검증하므로 여기 추가 require 불필요. 단 boot 시 명확히 fail-fast 되는지 확인 OK.

    // AuthService 의 user upsert 가 의존 — HTTP client 초기화보다 먼저.
    val dataSource = DbBootstrap.init(appConfig.db)
    val userRepository = UserRepository()
    val jobAnalyticsRepository = JobAnalyticsRepository()
    val externalApiCallsRepository = ExternalApiCallsRepository()
    val adminRepository = AdminRepository()
    val creditRepository = CreditRepository()

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
        // 큰 영상 (수십~수백 MB) Perso 업로드는 socket-level 으로 1+분 걸릴 수 있어 longer.
        // Cloud Run cold-start / 느린 backbone 디버깅 시 env 로 임시 늘릴 수 있게 외부화.
        val httpConnectTimeoutMs = System.getenv("HTTP_CONNECT_TIMEOUT_MS")?.toLongOrNull() ?: 120_000L
        val httpRequestTimeoutMs = System.getenv("HTTP_REQUEST_TIMEOUT_MS")?.toLongOrNull() ?: 600_000L
        val httpSocketTimeoutMs = System.getenv("HTTP_SOCKET_TIMEOUT_MS")?.toLongOrNull() ?: 600_000L
        install(HttpTimeout) {
            connectTimeoutMillis = httpConnectTimeoutMs
            requestTimeoutMillis = httpRequestTimeoutMs
            socketTimeoutMillis = httpSocketTimeoutMs
        }
        engine {
            requestTimeout = httpRequestTimeoutMs
            endpoint {
                connectAttempts = 3
                connectTimeout = httpConnectTimeoutMs
            }
        }
    }

    val fileStorage = FileStorageService(appConfig.storage)

    // R2_BUCKET 설정 시 download 엔드포인트가 SigV4 presigned URL redirect 로 Cloud Run
    // egress 와 인스턴스 점유 분리. blank 면 null → respondFile streaming fallback (로컬 dev).
    val objectStore = ObjectStore.fromConfig(appConfig.storage)

    // Concurrency cap for ffmpeg fan-out. RENDER_MAX_CONCURRENT can be set
    // explicitly in deployments where the autoreckoned (CPU/2) value is wrong
    // (containerized hosts often misreport availableProcessors).
    val renderMaxConcurrent = System.getenv("RENDER_MAX_CONCURRENT")?.toIntOrNull()
        ?: RenderService.defaultMaxConcurrent()
    val renderService = RenderService(
        fileStorage.renderDir,
        maxConcurrentRenders = renderMaxConcurrent,
        analytics = jobAnalyticsRepository,
    )

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
        analytics = jobAnalyticsRepository,
        externalCalls = externalApiCallsRepository,
    )
    val stemMixService = StemMixService(
        mixDir = File(fileStorage.separationDir, "mix"),
        mixTtlMs = appConfig.separation.mixTtlMs,
    )

    val authService = AuthService(appConfig.auth, httpClient, userRepository)

    // IAP receipt verifiers — config 가 null (미설정) 이면 verifier 도 null. 라우트가 null
    // 분기로 `iap_unconfigured` 400 응답하므로 stub 통과 없음. 출시 외 환경 (dev/test) 에서도
    // 영수증 검증을 진짜로 통과시키려면 sandbox env + sandbox tester 영수증 필요.
    val appleReceiptVerifier = appConfig.iap.apple?.let { AppleReceiptVerifier(it, httpClient) }
    val googleReceiptVerifier = appConfig.iap.google?.let { GoogleReceiptVerifier(it, httpClient) }

    // Phase 1: separation 의 source 결정자.
    // multipart `file` 또는 spec.editedRenderJobId 둘 중 하나로 source 해석.
    // editedRenderJobId 경유 시 RenderService 가 owner — resolver 가 별도 디렉터리에
    // 복사한 owned-copy 를 반환해 downstream 의 delete/rename 으로부터 원본 보호.
    val mediaSourceResolver = MediaSourceResolver(renderService, fileStorage.editedSourceDir)

    install(CallLogging) {
        level = Level.INFO
        // Never log signed-URL tokens / OAuth secrets in plain text — log
        // aggregators retain far longer than the TTL of these credentials.
        // 마스킹 대상: token (BFF 자체 HMAC), id_token / access_token (Google OAuth
        // 흐름의 query param 으로 절대 들어와선 안 되지만 misconfig 방어), code
        // (OAuth code).
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val rawPath = call.request.path()
            val rawQuery = call.request.queryString()
            val maskedQuery = rawQuery.replace(MASKED_QUERY_PARAMS) { "${it.groupValues[1]}=***" }
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
        renderInputCache,
        mediaSourceResolver, authService, objectStore,
        adminRepository,
        userRepository, creditRepository,
        appleReceiptVerifier, googleReceiptVerifier,
    )

    val shutdownHooks: List<() -> Unit> = listOf(
        httpClient::close,
        renderService::shutdown,
        separationService::shutdown,
        stemMixService::shutdown,
        { cacheCleanupScope.cancel() },
        { objectStore?.shutdown() },
        dataSource::close,
    )
    monitor.subscribe(ApplicationStopped) {
        shutdownHooks.forEach { it() }
    }
}
