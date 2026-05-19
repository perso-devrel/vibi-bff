package com.vibi.bff.config

import io.ktor.server.config.*

data class AppConfig(
    val storage: StorageConfig,
    val baseUrl: String,
    val perso: PersoConfig,
    val gemini: GeminiConfig,
    val separation: SeparationConfig,
    val auth: AuthConfig,
    val db: DbConfig,
    val admin: AdminConfig,
)

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
    /** Cloudflare 계정 ID (dashboard URL 의 32자 hex). endpoint host 결정. */
    val r2AccountId: String,
    /** R2 API token access key (Object Read & Write 권한). */
    val r2AccessKeyId: String,
    /** R2 API token secret. */
    val r2SecretAccessKey: String,
    /**
     * Presigned URL TTL. 60..86400 범위. 모바일이 status 응답 받자마자 다운로드한다는 가정.
     */
    val signedUrlTtlSec: Long,
) {
    init {
        if (r2Bucket.isNotBlank()) {
            require(r2AccountId.isNotBlank()) { "R2_ACCOUNT_ID required when R2_BUCKET set" }
            require(r2AccessKeyId.isNotBlank()) { "R2_ACCESS_KEY_ID required when R2_BUCKET set" }
            require(r2SecretAccessKey.isNotBlank()) { "R2_SECRET_ACCESS_KEY required when R2_BUCKET set" }
            require(signedUrlTtlSec in 60..86_400) {
                "SIGNED_URL_TTL_SEC must be in 60..86400 (got $signedUrlTtlSec)"
            }
        }
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
 * Vertex AI configuration.
 *
 * Auth 우선순위 ([com.vibi.bff.service.GeminiClient.loadOrRefreshCredentials] 참고):
 * 1. [credentialsPath] 가 비어있지 않으면 그 파일을 service account JSON 으로 사용 (로컬 dev).
 * 2. 비어있으면 Application Default Credentials — Cloud Run / GCE 의 attached service
 *    account, 또는 로컬의 `gcloud auth application-default login` 캐시 / env
 *    `GOOGLE_APPLICATION_CREDENTIALS` 자동 탐색.
 *
 * Validation 은 [GeminiClient] 의 첫 호출 시점까지 지연돼, 자막 번역 비활성 dev 환경에서도
 * 서버 부팅이 가능.
 */
data class GeminiConfig(
    val projectId: String,
    val location: String,
    val credentialsPath: String,
    val model: String,
) {
    init {
        require(model.isNotBlank()) { "GEMINI_MODEL must not be blank" }
        require(location.isNotBlank()) { "GEMINI_LOCATION must not be blank" }
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
 */
data class AuthConfig(
    val googleClientIds: List<String>,
    val appleClientIds: List<String>,
    val jwtSecret: String,
    val jwtExpirySeconds: Long,
) {
    init {
        require(googleClientIds.isNotEmpty()) { "GOOGLE_OAUTH_CLIENT_IDS must not be empty (comma-separated)" }
        require(googleClientIds.all { it.isNotBlank() }) { "GOOGLE_OAUTH_CLIENT_IDS contains blank entry" }
        require(appleClientIds.all { it.isNotBlank() }) { "APPLE_OAUTH_CLIENT_IDS contains blank entry" }
        require(jwtSecret.length >= 32) {
            "AUTH_JWT_SECRET must be at least 32 chars (got ${jwtSecret.length}). " +
                "Generate with: openssl rand -hex 32"
        }
        require(jwtExpirySeconds in 60..(90L * 24 * 3600)) {
            "AUTH_JWT_EXPIRY_SECONDS must be in 60..7776000 (got $jwtExpirySeconds)"
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
        require(maxPoolSize in 1..50) { "DB_MAX_POOL must be in 1..50 (got $maxPoolSize)" }
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
        require(urlTtlSec in 60..604_800) { "SEPARATION_URL_TTL_SEC must be in 60..604800 (got $urlTtlSec)" }
        require(mixUrlTtlSec in 60..604_800) { "SEPARATION_MIX_URL_TTL_SEC must be in 60..604800 (got $mixUrlTtlSec)" }
    }
}

fun loadConfig(config: ApplicationConfig): AppConfig {
    val vibi = config.config("vibi")
    val storage = vibi.config("storage")
    val perso = vibi.config("perso")
    val gemini = vibi.config("gemini")
    val separation = vibi.config("separation")
    val auth = vibi.config("auth")
    val db = vibi.config("db")
    val admin = vibi.config("admin")

    return AppConfig(
        storage = StorageConfig(
            basePath = storage.property("basePath").getString(),
            r2Bucket = storage.propertyOrNull("r2Bucket")?.getString()?.trim().orEmpty(),
            r2AccountId = storage.propertyOrNull("r2AccountId")?.getString()?.trim().orEmpty(),
            r2AccessKeyId = storage.propertyOrNull("r2AccessKeyId")?.getString()?.trim().orEmpty(),
            r2SecretAccessKey = storage.propertyOrNull("r2SecretAccessKey")?.getString()?.trim().orEmpty(),
            signedUrlTtlSec = storage.propertyOrNull("signedUrlTtlSec")?.getString()?.toLong() ?: 900L,
        ),
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
        gemini = GeminiConfig(
            projectId = gemini.property("projectId").getString(),
            location = gemini.property("location").getString(),
            credentialsPath = gemini.property("credentialsPath").getString(),
            model = gemini.property("model").getString(),
        ),
        separation = SeparationConfig(
            abandonTtlMs = separation.property("abandonTtlMs").getString().toLong(),
            mixTtlMs = separation.property("mixTtlMs").getString().toLong(),
            signingSecret = separation.property("signingSecret").getString(),
            urlTtlSec = separation.property("urlTtlSec").getString().toLong(),
            mixUrlTtlSec = separation.property("mixUrlTtlSec").getString().toLong(),
        ),
        auth = AuthConfig(
            googleClientIds = auth.property("googleClientIds").getString()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            appleClientIds = auth.propertyOrNull("appleClientIds")?.getString().orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            jwtSecret = auth.property("jwtSecret").getString(),
            jwtExpirySeconds = auth.property("jwtExpirySeconds").getString().toLong(),
        ),
        db = DbConfig(
            jdbcUrl = db.property("jdbcUrl").getString(),
            user = db.property("user").getString(),
            password = db.property("password").getString(),
            maxPoolSize = db.property("maxPoolSize").getString().toInt(),
        ),
        admin = AdminConfig(
            slug = admin.propertyOrNull("slug")?.getString().orEmpty().trim(),
        ),
    )
}
