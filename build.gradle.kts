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

group = "com.dubcast"
version = "0.1.0"

application {
    mainClass.set("com.dubcast.bff.ApplicationKt")
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

    // .env file support
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.2")

    // Google Cloud auth (Vertex AI access tokens via service account JSON)
    implementation("com.google.auth:google-auth-library-oauth2-http:1.27.0")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    testImplementation("io.ktor:ktor-client-mock:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.13")
}

// Windows 의 260자 경로 제한 회피용으로 짧은 절대경로를 쓰되, 다른 OS 는 기본값 사용.
// 환경변수 DUBCAST_BFF_BUILD_DIR 로 명시 override 가능.
val buildDirOverride = System.getenv("DUBCAST_BFF_BUILD_DIR")
    ?: if (System.getProperty("os.name").lowercase().contains("win")) "C:/tmp/dubcast-bff-build" else null
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
