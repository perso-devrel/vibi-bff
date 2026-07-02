package com.vibi.bff.config

import io.ktor.server.config.*

data class AppConfig(
    val storage: StorageConfig,
    val baseUrl: String,
    val perso: PersoConfig,
    val separation: SeparationConfig,
    val auth: AuthConfig,
    val db: DbConfig,
    val admin: AdminConfig,
    val iap: IapConfig,
)

/**
 * IAP receipt 검증 설정. Apple / Google 각각 nullable — 미설정 (모든 자격증명 blank) 이면
 * 해당 platform 의 `POST /credits/purchase` 요청은 [com.vibi.bff.routes.creditRoutes] 에서
 * `iap_unconfigured` 400 으로 명시 거부된다. dev 빌드도 stub 통과시키지 않는다 — 출시 코드와
 * 동일 경로를 타게 해 "stub 으로 통과 → 잔액 가산" 보안 구멍 차단.
 *
 * - [apple] — App Store Server API 자격증명. 셋 중 하나라도 blank 면 통째 null.
 * - [google] — Android Publisher API service account JSON. blank 면 null.
 */
data class IapConfig(
    val apple: AppleIapConfig?,
    val google: GoogleIapConfig?,
    val admob: AdMobConfig?,
)

/**
 * AdMob 보상형 광고 Server-Side Verification (SSV) 설정. 광고 시청 완료 시 Google 이
 * `GET /api/v2/credits/admob-ssv?...&signature=...&key_id=...` 콜백을 보내고, BFF 가
 * [com.vibi.bff.service.iap.AdMobSsvVerifier] 로 서명을 검증한 뒤 +1 크레딧을 지급한다.
 *
 * apple/google 영수증 설정과 달리 **비밀값이 필요 없다** — 검증은 Google 의 공개 검증키(무인증
 * 공개 JSON, URL 은 [com.vibi.bff.service.iap.AdMobSsvVerifier] 의 고정 상수) 로만 수행. 따라서
 * [enabled] 기본 true. 끄려면 `ADMOB_SSV_ENABLED=false` → null 이 되어 라우트가 503 으로 거부.
 *
 * - [dailyCap] — 사용자당 24h 광고 보상 크레딧 상한 (서버 분리 비용 과지출 방지). 기본 [DEFAULT_DAILY_CAP].
 */
data class AdMobConfig(
    val dailyCap: Int,
) {
    init {
        require(dailyCap in 1..100) { "ADMOB_DAILY_CAP must be in 1..100 (got $dailyCap)" }
    }

    companion object {
        const val DEFAULT_DAILY_CAP = 3
    }
}

/**
 * Apple App Store Server API (`/inApps/v1/transactions/{transactionId}`) 호출용. ES256 JWT
 * 로 인증 ([com.vibi.bff.service.iap.AppleReceiptVerifier]). 본 config 가 null 이면 Apple
 * 영수증 검증 자체가 비활성 — 라우트가 명시적으로 400 거부.
 *
 * - [issuerId] — App Store Connect → Users and Access → Keys 페이지 상단 Issuer ID (UUID).
 * - [keyId] — 발급한 In-App Purchase 키의 Key ID (10자 alphanumeric).
 * - [privateKeyPem] — `.p8` 파일 본문. env var 로 주입할 때 줄바꿈은 `\n` literal 로 박은 뒤
 *   본 클래스가 실제 newline 으로 복원. PEM header/footer 포함.
 * - [bundleId] — iOS 앱 bundle id (e.g. `com.vibi.ios`). 검증 시 응답 payload 의 bundleId 가
 *   이 값과 일치해야 통과.
 * - [environment] — `production` (앱스토어 정식) 또는 `sandbox` (TestFlight / sandbox tester).
 *   환경별로 host 가 갈리므로 명시 필요. 기본 `production`.
 */
data class AppleIapConfig(
    val issuerId: String,
    val keyId: String,
    val privateKeyPem: String,
    val bundleId: String,
    val environment: String,
) {
    init {
        require(issuerId.isNotBlank()) { "IAP_APPLE_ISSUER_ID must not be blank" }
        require(keyId.isNotBlank()) { "IAP_APPLE_KEY_ID must not be blank" }
        require(privateKeyPem.contains("BEGIN PRIVATE KEY")) {
            "IAP_APPLE_PRIVATE_KEY must contain PEM '-----BEGIN PRIVATE KEY-----' header"
        }
        require(bundleId.isNotBlank()) { "IAP_APPLE_BUNDLE_ID must not be blank" }
        require(environment in setOf("production", "sandbox")) {
            "IAP_APPLE_ENV must be 'production' or 'sandbox' (got '$environment')"
        }
    }

    /** Apple 의 host 결정. App Store Server API 는 prod/sandbox 가 다른 호스트. */
    val apiHost: String get() = when (environment) {
        "sandbox" -> "https://api.storekit-sandbox.itunes.apple.com"
        else -> "https://api.storekit.itunes.apple.com"
    }
}

/**
 * Google Android Publisher API 의 `purchases.products.get` 호출용. Service account JSON 으로
 * OAuth2 access token 발급 → `androidpublisher` scope 로 호출
 * ([com.vibi.bff.service.iap.GoogleReceiptVerifier]).
 *
 * - [packageName] — 안드로이드 앱 패키지명 (e.g. `com.vibi.android`).
 * - [serviceAccountJson] — Google Cloud Console 에서 다운로드한 service account JSON **본문**.
 *   Play Console 의 "재무 데이터 보기 + 주문 및 구독 관리" 권한 부여 필요. env var 로 본문을
 *   직접 주입하거나 파일 경로 운영도 고려 가능 — 본 v1 은 본문만 받음.
 */
data class GoogleIapConfig(
    val packageName: String,
    val serviceAccountJson: String,
) {
    init {
        require(packageName.isNotBlank()) { "IAP_GOOGLE_PACKAGE_NAME must not be blank" }
        require(serviceAccountJson.contains("\"client_email\"")) {
            "IAP_GOOGLE_SERVICE_ACCOUNT_JSON must look like a service account JSON " +
                "(missing 'client_email' field)"
        }
    }
}

/**
 * 운영자 admin SPA 마운트 경로 + 빌드 산출물 경로.
 *
 * - [slug] — 추측 불가능한 prefix (env ADMIN_SLUG). blank 면 admin UI 자체를 마운트 안 함
 *   (= staticResources 호출 자체 skip). 운영자만 알아야 하는 값이라 코드 상수 0.
 * - [resourcePath] — classpath 안 SPA 빌드 산출물 디렉터리. Vite build outDir 와 1:1
 *   (`src/main/resources/admin/`). 별도 path 로 바꿀 일 거의 없음.
 */
data class AdminConfig(
    val slug: String,
    val resourcePath: String = "admin",
) {
    init {
        if (slug.isNotBlank()) {
            require(slug.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                "ADMIN_SLUG must be alphanumeric / dash / underscore only (got '$slug')"
            }
            require(slug.length in 6..64) {
                "ADMIN_SLUG length must be 6..64 (got ${slug.length})"
            }
        }
    }
}

data class StorageConfig(
    val basePath: String,
    /**
     * Cloudflare R2 bucket — 설정 시 download 엔드포인트가 산출물을 R2 에 업로드 후
     * SigV4 presigned URL 로 302 redirect. blank 면 respondFile streaming 으로 fallback
     * (로컬 dev / R2 미사용 환경). R2 egress 무료라 Cloud Run egress 비용 0.
     */
    val r2Bucket: String,
    /** R2 credentials. r2Bucket blank 면 null — 백엔드 미사용. */
    val r2: R2Credentials?,
    /**
     * Presigned URL TTL. 60..86400 범위. 모바일이 status 응답 받자마자 다운로드한다는 가정.
     */
    val signedUrlTtlSec: Long,
) {
    init {
        if (r2Bucket.isNotBlank()) {
            requireNotNull(r2) { "R2 credentials required when R2_BUCKET set" }
            require(signedUrlTtlSec in 60..86_400) {
                "SIGNED_URL_TTL_SEC must be in 60..86400 (got $signedUrlTtlSec)"
            }
        }
    }
}

/**
 * R2 backend 가 활성일 때만 의미 있는 자격증명 묶음. StorageConfig 의 R2-specific 필드를
 * 분리해 backend 비활성 경로 (`r2 == null`) 와 단일 if 으로 분기 가능.
 */
data class R2Credentials(
    /** Cloudflare 계정 ID (dashboard URL 의 32자 hex). endpoint host 결정. */
    val accountId: String,
    /** R2 API token access key (Object Read & Write 권한). */
    val accessKeyId: String,
    /** R2 API token secret. */
    val secretAccessKey: String,
) {
    init {
        require(accountId.isNotBlank()) { "R2_ACCOUNT_ID must not be blank" }
        require(accessKeyId.isNotBlank()) { "R2_ACCESS_KEY_ID must not be blank" }
        require(secretAccessKey.isNotBlank()) { "R2_SECRET_ACCESS_KEY must not be blank" }
    }
}

data class PersoConfig(
    val apiKey: String,
    val baseUrl: String,
    /**
     * Perso 의 storage host (Azure Blob 기반 public CDN). `/perso-storage/...` path 응답을
     * 다운로드할 때는 [baseUrl] (api host) 가 아니라 이쪽으로 가야 한다 — 다른 host 라
     * 인증 헤더 없이 public read.
     */
    val storageBaseUrl: String,
    val spaceSeq: Int,
    val pollIntervalMs: Long,
    val maxPollMinutes: Int,
    /**
     * SSRF 방지용 download host 화이트리스트. Perso 응답으로 받은 absolute URL 의 host 가
     * 이 셋 안에 있을 때만 [com.vibi.bff.service.PersoClient.streamDownloadAuthorized]
     * 가 다운로드 진행. baseUrl/storageBaseUrl 의 host 는 자동 포함되고, 그 외에 흔한
     * Azure SAS host (`portal-media.perso.ai`) 등을 콤마 분리로 추가.
     */
    val downloadAllowedHosts: Set<String>,
) {
    init {
        require(apiKey.isNotBlank()) { "PERSO_API_KEY must not be blank" }
        require(storageBaseUrl.isNotBlank()) { "PERSO_STORAGE_BASE_URL must not be blank" }
        require(spaceSeq > 0) { "PERSO_SPACE_SEQ must be > 0 (got $spaceSeq)" }
        require(pollIntervalMs >= 1000) { "PERSO_POLL_INTERVAL_MS must be >= 1000 (got $pollIntervalMs)" }
        require(maxPollMinutes in 1..120) { "PERSO_MAX_POLL_MINUTES must be in 1..120 (got $maxPollMinutes)" }
    }

    /**
     * baseUrl + storageBaseUrl + 명시적 [downloadAllowedHosts] 의 union — 다운로드 호출
     * 직전마다 host 검증 시 사용. host 만 비교 (port/scheme 별도).
     */
    val allDownloadAllowedHosts: Set<String> by lazy {
        val parsed = mutableSetOf<String>()
        runCatching { java.net.URI.create(baseUrl).host?.let { parsed += it.lowercase() } }
        runCatching { java.net.URI.create(storageBaseUrl).host?.let { parsed += it.lowercase() } }
        downloadAllowedHosts.forEach { parsed += it.lowercase() }
        parsed.toSet()
    }
}

/**
 * Google OAuth + Apple Sign In + 자체 JWT 발급 설정.
 *
 * - [googleClientIds] — `tokeninfo` 응답의 `aud` 가 이 중 하나와 일치해야 통과.
 *   콤마 분리 문자열로 env 주입 (iOS / Android / Web client id 모두 허용).
 * - [appleClientIds] — Apple JWKS 검증된 ID Token 의 `aud` 가 이 중 하나와 일치해야 통과.
 *   보통 iOS bundle id (`com.vibi.ios`). blank list 면 Apple 로그인 비활성 — 라우트
 *   진입 시 명시적으로 거부.
 * - [jwtSecret] — HMAC-SHA256 서명 키. 32+ chars (`openssl rand -hex 32`).
 * - [jwtExpirySeconds] — 발급된 access token 의 만료까지 초.
 * - [googleOauthClientId] / [googleOauthClientSecret] — UXP 패널 device-flow 의 server-side
 *   OAuth code 교환용 web 클라이언트. 둘 다 set 일 때만 Google device 로그인 활성(아니면
 *   `/auth/google/start` 가 500 "not configured"). [googleOauthClientId] 는 교환된 id_token 의
 *   aud 이므로 loadConfig 가 [googleClientIds] 에 자동 병합해 verifyGoogleIdToken 을 통과시킨다.
 */
data class AuthConfig(
    val googleClientIds: List<String>,
    val appleClientIds: List<String>,
    val jwtSecret: String,
    val jwtExpirySeconds: Long,
    val googleOauthClientId: String? = null,
    val googleOauthClientSecret: String? = null,
) {
    init {
        require(googleClientIds.isNotEmpty()) { "GOOGLE_OAUTH_CLIENT_IDS must not be empty (comma-separated)" }
        require(googleClientIds.all { it.isNotBlank() }) { "GOOGLE_OAUTH_CLIENT_IDS contains blank entry" }
        require(appleClientIds.all { it.isNotBlank() }) { "APPLE_OAUTH_CLIENT_IDS contains blank entry" }
        require(jwtSecret.length >= 32) {
            "AUTH_JWT_SECRET must be at least 32 chars (got ${jwtSecret.length}). " +
                "Generate with: openssl rand -hex 32"
        }
        // 상한선 30일 — refresh token 미구현 상태에서 access token 만으로 90일 살리면
        // 탈취 시 노출 창이 과대. 운영 default 는 application.conf 에서 7일 (604800).
        // refresh token 도입 시 access token 은 1h 이하로 더 짧게.
        require(jwtExpirySeconds in 60..(30L * 24 * 3600)) {
            "AUTH_JWT_EXPIRY_SECONDS must be in 60..2592000 (got $jwtExpirySeconds)"
        }
    }
}

/**
 * User 영속화 + IAP 도입 대비용 Postgres 설정. Cloud Run / Cloudflare Containers 어디서든
 * Neon (managed Postgres) JDBC URL 로 동일 동작 — vendor 종속성 없음.
 *
 * - [jdbcUrl] — `jdbc:postgresql://<host>/<db>?sslmode=require` 형식. blank 면 부팅 시 fail.
 * - [maxPoolSize] — Neon free tier 100 connection 한도. 인스턴스당 5 가 default.
 */
data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    init {
        require(jdbcUrl.isNotBlank()) { "DATABASE_URL must not be blank" }
        require(jdbcUrl.startsWith("jdbc:postgresql://") || jdbcUrl.startsWith("jdbc:h2:")) {
            "DATABASE_URL must be a Postgres or H2 JDBC URL (got: ${jdbcUrl.take(20)}...)"
        }
        // Neon 은 공용 인터넷 경유 — 평문 연결 차단. Postgres URL 은 TLS(sslmode) 필수,
        // sslmode=disable 은 명시 거부. (H2 in-memory 테스트는 면제.) verify-ca/verify-full
        // 같은 더 강한 모드도 허용하도록 'require' 고정 대신 'disable 아님' 으로 검증.
        if (jdbcUrl.startsWith("jdbc:postgresql://")) {
            require(jdbcUrl.contains("sslmode=") && !jdbcUrl.contains("sslmode=disable")) {
                "DATABASE_URL must enforce TLS for Postgres — add sslmode=require " +
                    "(or verify-ca/verify-full). sslmode=disable is rejected."
            }
        }
        require(maxPoolSize in 1..50) { "DB_MAX_POOL must be in 1..50 (got $maxPoolSize)" }
    }
}

data class SeparationConfig(
    val abandonTtlMs: Long,
    val signingSecret: String,
    val urlTtlSec: Long,
    /** Perso 가 동시에 받는 audio-separation 잡 수의 BFF-side cap. Perso 측 실제 한계는
     *  "실행 1 + 큐 2-3". 우리 Perso space 가 전용이라 정확히 추적 가능. 기본 2 (Perso 가 하나
     *  처리하는 동안 다음 1개를 Perso 큐에 대기) — Perso 큐가 끊김 없이 다음 잡을 시작.
     *  보수적으로 1로 두면 BFF 큐가 모든 대기 흡수 (UX 깔끔), 적극적으로 3으로 두면 throughput
     *  최대화. 사고 시 즉시 1로 떨어뜨려 Perso 호출 차단 가능 (env override). */
    val maxPersoInFlight: Int,
    /** SUBMITTING 상태가 이 초 이상 지속되면 stuck (인스턴스 사망) 으로 간주, reaper 가 QUEUED
     *  로 복귀시킨다. SUBMITTING 구간엔 ≤100MB 블롭 업로드 + 재시도가 통째로 들어가므로 너무
     *  짧으면 업로드 도중 멀쩡한 잡을 재큐잉 → 동시 2회 실행 사고. SeparationDispatcher 로 주입. */
    val stuckSubmittingSec: Long,
) {
    init {
        require(signingSecret.length >= 32) {
            "SEPARATION_SIGNING_SECRET must be at least 32 chars (got ${signingSecret.length}). " +
                "Generate with: openssl rand -hex 32"
        }
        require(abandonTtlMs >= 60_000) { "SEPARATION_ABANDON_TTL_MS must be >= 60000 (got $abandonTtlMs)" }
        require(urlTtlSec in 60..604_800) { "SEPARATION_URL_TTL_SEC must be in 60..604800 (got $urlTtlSec)" }
        require(maxPersoInFlight in 1..5) {
            "SEPARATION_MAX_PERSO_IN_FLIGHT must be in 1..5 (got $maxPersoInFlight). " +
                "Perso 측 제약은 '실행 1 + 큐 2-3' — 5 이상은 거절 위험."
        }
        require(stuckSubmittingSec in 60..1_800) {
            "SEPARATION_STUCK_SUBMITTING_SEC must be in 60..1800 (got $stuckSubmittingSec). " +
                "업로드 worst-case 보다 길고 staleQueued(30분) 보다 짧아야 함."
        }
    }
}

fun loadConfig(config: ApplicationConfig): AppConfig {
    val vibi = config.config("vibi")
    val storage = vibi.config("storage")
    val perso = vibi.config("perso")
    val separation = vibi.config("separation")
    val auth = vibi.config("auth")
    val db = vibi.config("db")
    val admin = vibi.config("admin")
    val iap = vibi.config("iap")

    return AppConfig(
        storage = run {
            val r2Bucket = storage.propertyOrNull("r2Bucket")?.getString()?.trim().orEmpty()
            StorageConfig(
                basePath = storage.property("basePath").getString(),
                r2Bucket = r2Bucket,
                r2 = if (r2Bucket.isNotBlank()) R2Credentials(
                    accountId = storage.propertyOrNull("r2AccountId")?.getString()?.trim().orEmpty(),
                    accessKeyId = storage.propertyOrNull("r2AccessKeyId")?.getString()?.trim().orEmpty(),
                    secretAccessKey = storage.propertyOrNull("r2SecretAccessKey")?.getString()?.trim().orEmpty(),
                ) else null,
                signedUrlTtlSec = storage.propertyOrNull("signedUrlTtlSec")?.getString()?.toLong() ?: 900L,
            )
        },
        baseUrl = vibi.property("baseUrl").getString(),
        perso = PersoConfig(
            apiKey = perso.property("apiKey").getString(),
            baseUrl = perso.property("baseUrl").getString(),
            storageBaseUrl = perso.property("storageBaseUrl").getString(),
            spaceSeq = perso.property("spaceSeq").getString().toInt(),
            pollIntervalMs = perso.property("pollIntervalMs").getString().toLong(),
            maxPollMinutes = perso.property("maxPollMinutes").getString().toInt(),
            downloadAllowedHosts = perso.propertyOrNull("downloadAllowedHosts")?.getString()
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: setOf("portal-media.perso.ai"),
        ),
        separation = SeparationConfig(
            abandonTtlMs = separation.property("abandonTtlMs").getString().toLong(),
            signingSecret = separation.property("signingSecret").getString(),
            urlTtlSec = separation.property("urlTtlSec").getString().toLong(),
            maxPersoInFlight = separation.property("maxPersoInFlight").getString().toInt(),
            stuckSubmittingSec = separation.property("stuckSubmittingSec").getString().toLong(),
        ),
        auth = run {
            val oauthClientId = auth.propertyOrNull("googleOauthClientId")?.getString()?.trim()
                ?.takeIf { it.isNotBlank() }
            val oauthClientSecret = auth.propertyOrNull("googleOauthClientSecret")?.getString()?.trim()
                ?.takeIf { it.isNotBlank() }
            val baseGoogleIds = auth.property("googleClientIds").getString()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            // device-flow web 클라이언트 id 를 검증 허용 aud 에 병합 — 교환된 id_token 의 aud 가
            // 이 client id 라 verifyGoogleIdToken(aud in googleClientIds)을 통과해야 한다.
            val googleClientIds = (baseGoogleIds + listOfNotNull(oauthClientId)).distinct()
            AuthConfig(
                googleClientIds = googleClientIds,
                appleClientIds = auth.propertyOrNull("appleClientIds")?.getString().orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                jwtSecret = auth.property("jwtSecret").getString(),
                jwtExpirySeconds = auth.property("jwtExpirySeconds").getString().toLong(),
                googleOauthClientId = oauthClientId,
                googleOauthClientSecret = oauthClientSecret,
            )
        },
        db = DbConfig(
            jdbcUrl = db.property("jdbcUrl").getString(),
            user = db.property("user").getString(),
            password = db.property("password").getString(),
            maxPoolSize = db.property("maxPoolSize").getString().toInt(),
        ),
        admin = AdminConfig(
            slug = admin.propertyOrNull("slug")?.getString().orEmpty().trim(),
        ),
        iap = run {
            val appleConfig = iap.config("apple")
            val googleConfig = iap.config("google")
            // Apple 자격증명. 셋 중 하나라도 blank 면 전체 비활성. env var 의 `\n` literal 을
            // 실제 newline 으로 복원 — `.p8` PEM 줄바꿈을 한 줄 env 로 주입할 수 있게.
            val appleIssuer = appleConfig.propertyOrNull("issuerId")?.getString()?.trim().orEmpty()
            val appleKeyId = appleConfig.propertyOrNull("keyId")?.getString()?.trim().orEmpty()
            val applePem = appleConfig.propertyOrNull("privateKeyPem")?.getString().orEmpty()
                .replace("\\n", "\n")
            val appleBundleId = appleConfig.propertyOrNull("bundleId")?.getString()?.trim().orEmpty()
            val appleEnv = appleConfig.propertyOrNull("environment")?.getString()?.trim()?.lowercase()
                ?.takeIf { it.isNotBlank() } ?: "production"
            val apple = if (
                appleIssuer.isNotBlank() && appleKeyId.isNotBlank() &&
                applePem.isNotBlank() && appleBundleId.isNotBlank()
            ) {
                AppleIapConfig(
                    issuerId = appleIssuer,
                    keyId = appleKeyId,
                    privateKeyPem = applePem,
                    bundleId = appleBundleId,
                    environment = appleEnv,
                )
            } else null

            val googlePackage = googleConfig.propertyOrNull("packageName")?.getString()?.trim().orEmpty()
            val googleSa = googleConfig.propertyOrNull("serviceAccountJson")?.getString().orEmpty()
            val google = if (googlePackage.isNotBlank() && googleSa.isNotBlank()) {
                GoogleIapConfig(packageName = googlePackage, serviceAccountJson = googleSa)
            } else null

            // AdMob SSV — 비밀값 없이 공개 검증키로만 동작하므로 기본 활성. 명시적으로
            // enabled=false 면 null 로 비활성 (라우트 503).
            val admobConfig = iap.config("admob")
            val admobEnabled = admobConfig.propertyOrNull("enabled")?.getString()?.trim()
                ?.lowercase() != "false"
            val admob = if (admobEnabled) {
                AdMobConfig(
                    dailyCap = admobConfig.propertyOrNull("dailyCap")?.getString()?.toIntOrNull()
                        ?: AdMobConfig.DEFAULT_DAILY_CAP,
                )
            } else null

            IapConfig(apple = apple, google = google, admob = admob)
        },
    )
}
