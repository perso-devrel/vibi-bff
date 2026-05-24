val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    application
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    // io.ktor.plugin (fat-jar/Docker support) 는 dev 에서 불필요해 제거 — application plugin
    // 만으로 ./gradlew run 충분. 빌드 그래프 단순화 + configuration cache 호환성 향상.
}

group = "com.vibi"
version = "0.1.0"

application {
    mainClass.set("com.vibi.bff.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-client-logging:$ktor_version")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

    // Swagger UI
    implementation("io.ktor:ktor-server-swagger:$ktor_version")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")
    // Cloud Run / GCP Error Reporting 자동 수집용 JSON encoder. K_SERVICE env 가 set 인 경우만
    // 활성 (logback.xml 의 if-property), 로컬에선 기존 텍스트 포맷 유지.
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    // logback.xml 의 <if condition> 평가 엔진. 부팅 시 ~50KB 메모리, eval 1회.
    implementation("org.codehaus.janino:janino:3.1.12")

    // .env file support
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.2")

    // Google Cloud auth (Vertex AI access tokens via service account JSON)
    implementation("com.google.auth:google-auth-library-oauth2-http:1.27.0")

    // JWT (자체 access token 발급/검증). HS256 으로 자체 access token 발급, RS256 으로
    // Apple ID Token 검증 (Apple JWKS public key).
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.auth0:jwks-rsa:0.22.1")

    // DB — Neon (managed Postgres) + Exposed DSL + HikariCP pool + Flyway migration.
    // Vendor-neutral: Cloud Run / Cloudflare Containers 양쪽 동등 동작.
    val exposedVersion = "0.56.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.18.2")
    implementation("org.flywaydb:flyway-database-postgresql:10.18.2")
    // 단위 테스트용 in-memory DB (Exposed 호환). Flyway 도 H2 지원.
    testImplementation("com.h2database:h2:2.3.232")

    // Apache Commons Compress — Perso audio-separation 의 OriginalVoiceSpeakers .tar archive 풀이용.
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Cloudflare R2 (S3-compatible) — Cloud Run egress 분리. 큰 산출물 (render mp4,
    // separation stem, mix) 을 R2 에 업로드 후 SigV4 presigned URL 로 클라이언트가 직접
    // 다운로드. **R2 egress 무료** 라 BFF 가 부담하는 egress 비용이 0 으로 떨어짐 (GCS 는
    // 1GB/월 free 후 $0.12/GB). url-connection-client 는 SDK 의 가장 가벼운 sync HTTP 백엔드 —
    // Apache HttpClient 의존성 제거. 미설정 (R2_BUCKET blank) 시 로컬 디스크 fallback.
    implementation("software.amazon.awssdk:s3:2.29.40")
    implementation("software.amazon.awssdk:url-connection-client:2.29.40")

    // Sentry — 운영 모니터링. SENTRY_DSN_BFF env 가 비면 init no-op (dev/test 무영향).
    // logback appender 까지는 도입 안 함 (5xx 캐치에서 explicit captureException 만 사용).
    implementation("io.sentry:sentry:7.18.1")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    testImplementation("io.ktor:ktor-client-mock:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.13")
}

// Windows 의 260자 경로 제한 회피용으로 짧은 절대경로를 쓰되, 다른 OS 는 기본값 사용.
// 환경변수 VIBI_BFF_BUILD_DIR 로 명시 override 가능.
val buildDirOverride = System.getenv("VIBI_BFF_BUILD_DIR")
    ?: if (System.getProperty("os.name").lowercase().contains("win")) "C:/tmp/vibi-bff-build" else null
if (buildDirOverride != null) {
    layout.buildDirectory = file(buildDirOverride)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Dfile.encoding=UTF-8")
}

kotlin {
    jvmToolchain(21)
}
