# vibi-bff — Ktor 3 / JDK 21 / ffmpeg.
# 레이어 캐시: gradle 설정 → 의존성 해결 → 소스 → 빌드 순으로 분리.
# 컨테이너 메모리 인식: -XX:MaxRAMPercentage 로 Cloud Run --memory 한도 활용.

# ============================================================
# Build stage — JDK 21 + Gradle
# ============================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 1) Gradle wrapper + 빌드 스크립트만 먼저 — 의존성 그래프 변경 시에만 무효화
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew

# 2) 의존성 사전 해결. src 변경에 무관하게 이 레이어는 캐시 hit.
RUN ./gradlew --no-daemon -q dependencies --configuration runtimeClasspath > /dev/null

# 3) 소스 복사 — 자주 변경됨
COPY src src

# 4) 빌드. application plugin 의 installDist → /app/build/install/vibi-bff/{bin,lib}
RUN ./gradlew --no-daemon installDist

# ============================================================
# Runtime stage — JRE 21 + ffmpeg
# ============================================================
FROM eclipse-temurin:21-jre

# ffmpeg / ffprobe — CLAUDE.md 의 runtime 필수. ca-certificates — Vertex AI HTTPS.
# 한 RUN 으로 묶어 레이어 최소화, apt 캐시는 즉시 제거.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --user-group --no-create-home app

WORKDIR /app
COPY --from=build --chown=app:app /app/build/install/vibi-bff /app

# Cloud Run --memory 한도의 75% 까지 heap. 나머지 25% 는 native (ffmpeg subprocess 가 아닌
# JVM 자체의 Netty direct buffer, Metaspace, code cache) 가 사용.
# ExitOnOutOfMemoryError: OOM 시 JVM 즉시 종료 → Cloud Run 이 인스턴스 교체.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV PORT=8080

USER 1001:1001
EXPOSE 8080

# application plugin start script. JAVA_OPTS 를 자동 적용.
ENTRYPOINT ["/app/bin/vibi-bff"]
