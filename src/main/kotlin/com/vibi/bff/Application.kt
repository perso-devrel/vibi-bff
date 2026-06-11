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
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.RenderInputCacheService
import com.vibi.bff.service.SeparationDispatcher
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
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
import kotlinx.coroutines.withContext
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
    val separationQueueRepository = SeparationQueueRepository()
    val adminRepository = AdminRepository()
    val creditRepository = CreditRepository()

    // BFF 인스턴스 단위 UUID — SeparationDispatcher 가 "자기 인스턴스가 enqueue 한 QUEUED 만
    // claim" 정책의 키. Cloud Run 이 새 컨테이너 띄울 때마다 새 UUID, 같은 인스턴스 lifetime
    // 안엔 변하지 않음. K_REVISION 같은 GCP 변수보다 process-uuid 가 단순/안전 (한 revision
    // 안에 여러 인스턴스 == 같은 K_REVISION 이라 식별 불가).
    val bffInstanceId = java.util.UUID.randomUUID().toString()

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
        // R2 가 set 이면 렌더 완료 직후 결과 mp4 를 eager upload — Cloud Run idle scale-down
        // 시에도 다운로드 가능. 로컬 dev (R2 미설정) 분기에선 null 그대로 통과.
        objectStore = objectStore,
    )

    // Shared input cache. RENDER_INPUT_CACHE_TTL_HOURS overrides the 24h default
    // when a deployment needs longer reuse windows (long-running mobile session
    // editing N variants over hours).
    val renderInputCacheTtlHours = System.getenv("RENDER_INPUT_CACHE_TTL_HOURS")?.toLongOrNull() ?: 24L
    require(renderInputCacheTtlHours > 0) {
        "RENDER_INPUT_CACHE_TTL_HOURS must be > 0 (got $renderInputCacheTtlHours)"
    }
    val renderInputCache = RenderInputCacheService(
        baseDir = File(fileStorage.renderDir.parentFile, "render-input-cache"),
        ttlMs = TimeUnit.HOURS.toMillis(renderInputCacheTtlHours),
    )
    val cacheCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val assetCacheTtlHours = System.getenv("ASSET_CACHE_TTL_HOURS")?.toLongOrNull() ?: 24L
    require(assetCacheTtlHours > 0) {
        "ASSET_CACHE_TTL_HOURS must be > 0 (got $assetCacheTtlHours)"
    }
    cacheCleanupScope.launch {
        // Sweep once on startup (recover from crash-leftover entries), then
        // hourly. Sleeping 1h between sweeps is fine — TTL is 24h, slop tolerated.
        // 예외는 삼키되(다음 sweep 으로 계속) WARN 으로 남겨 디스크 누적 같은 cleanup 실패가
        // 관측되도록 한다 — silent swallow 면 디스크가 찰 때까지 보이지 않는다.
        val cleanupLog = org.slf4j.LoggerFactory.getLogger("CacheCleanup")
        while (isActive) {
            runCatching { renderInputCache.cleanExpired() }
                .onFailure { cleanupLog.warn("render input cache cleanup failed (will retry in 1h): {}", it.message, it) }
            runCatching { fileStorage.sweepAssetCacheOlderThan(TimeUnit.HOURS.toMillis(assetCacheTtlHours)) }
                .onFailure { cleanupLog.warn("asset cache sweep failed (will retry in 1h): {}", it.message, it) }
            delay(TimeUnit.HOURS.toMillis(1))
        }
    }

    val persoClient = PersoClient(appConfig.perso, httpClient)
    org.slf4j.LoggerFactory.getLogger("BootCheck").info(
        "Perso config: baseUrl={} spaceSeq={} pollIntervalMs={}",
        appConfig.perso.baseUrl, appConfig.perso.spaceSeq, appConfig.perso.pollIntervalMs
    )
    val signedUrlService = SignedUrlService(appConfig.separation.signingSecret)

    // SeparationDispatcher 와 SeparationService 가 양방향 의존 (서비스 → dispatcher.nudge,
    // dispatcher → service.runQueuedJob). 순환 해결: nudge 람다를 lateinit 변수로 우회.
    // Application start 직후 둘 다 살아있다는 보장 안에서만 안전.
    lateinit var separationDispatcher: SeparationDispatcher
    val separationService = SeparationService(
        persoClient = persoClient,
        separationDir = fileStorage.separationDir,
        config = appConfig.separation,
        pollIntervalMs = appConfig.perso.pollIntervalMs,
        maxPollMinutes = appConfig.perso.maxPollMinutes,
        queue = separationQueueRepository,
        externalCalls = externalApiCallsRepository,
        onJobChange = { separationDispatcher.nudge() },
        bffInstanceId = bffInstanceId,
        // R2 가 set 이면 READY 마킹 직전 stems 를 eager upload — 인스턴스 사망에도 데이터
        // 살아남도록. 로컬 dev (R2 미설정) 분기에선 null 그대로 통과.
        objectStore = objectStore,
        // 잡 FAILED 진입 시 라우트가 선차감한 크레딧을 환불. 멱등 (이미 환불됐으면 no-op).
        // refund 자체가 blocking JDBC 라 Dispatchers.IO 로 우회.
        onJobFailed = { jobId ->
            withContext(Dispatchers.IO) { creditRepository.refund(jobId) }
        },
    )
    separationDispatcher = SeparationDispatcher(
        service = separationService,
        queue = separationQueueRepository,
        bffInstanceId = bffInstanceId,
        maxPersoInFlight = appConfig.separation.maxPersoInFlight,
        stuckSubmittingSec = appConfig.separation.stuckSubmittingSec,
    )
    separationDispatcher.start()

    // Boot resumption: 다른 (죽은) 인스턴스가 처리 중이던 PROCESSING 잡들을 self 로 재할당한 뒤
    // polling 재개. Perso 잡은 server-side 에서 계속 돌고 있어 결과만 받아오면 됨 — 사용자한테
    // 인스턴스 재시작이 invisible. fire-and-forget — boot 막지 않음.
    val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    bootScope.launch {
        runCatching {
            val orphans = separationQueueRepository.claimOrphanedProcessing(bffInstanceId)
            orphans.forEach { (jobId, persoProjectSeq, ownerUserId) ->
                separationService.resumePollingForJob(jobId, persoProjectSeq, ownerUserId)
            }
        }.onFailure { e ->
            org.slf4j.LoggerFactory.getLogger("BootResume")
                .error("Failed to resume orphaned PROCESSING jobs: {}", e.message, e)
        }
    }

    val authService = AuthService(appConfig.auth, httpClient, userRepository, creditRepository)

    // IAP receipt verifiers — config 가 null (미설정) 이면 verifier 도 null. 라우트가 null
    // 분기로 `iap_unconfigured` 400 응답하므로 stub 통과 없음. 출시 외 환경 (dev/test) 에서도
    // 영수증 검증을 진짜로 통과시키려면 sandbox env + sandbox tester 영수증 필요.
    val appleReceiptVerifier = appConfig.iap.apple?.let { AppleReceiptVerifier(it, httpClient) }
    val googleReceiptVerifier = appConfig.iap.google?.let { GoogleReceiptVerifier(it, httpClient) }

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
        separationService, separationQueueRepository,
        signedUrlService,
        renderInputCache,
        authService, objectStore,
        adminRepository,
        userRepository, creditRepository,
        appleReceiptVerifier, googleReceiptVerifier,
    )

    val shutdownHooks: List<() -> Unit> = listOf(
        httpClient::close,
        renderService::shutdown,
        // Dispatcher 를 먼저 멈춰서 새 claim 안 함 → in-flight 잡들 정상 종료 후 service shutdown.
        separationDispatcher::shutdown,
        separationService::shutdown,
        { cacheCleanupScope.cancel() },
        { bootScope.cancel() },
        { objectStore?.shutdown() },
        dataSource::close,
    )
    monitor.subscribe(ApplicationStopped) {
        shutdownHooks.forEach { it() }
    }
}
