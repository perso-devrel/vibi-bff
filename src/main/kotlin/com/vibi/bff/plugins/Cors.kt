package com.vibi.bff.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import org.slf4j.LoggerFactory

/**
 * CORS 는 admin SPA 가 BFF 와 다른 origin 으로 분리 배포될 때만 필요. 모바일 클라이언트
 * (Ktor Client) 는 CORS 와 무관. 운영에선 admin SPA 가 `staticResources("/$adminSlug", ...)`
 * 로 BFF 자체에서 서빙되므로 same-origin — CORS 비활성이 default 안전.
 *
 * CORS_ALLOWED_ORIGINS env 가 비면 **CORS 자체 install skip** (이전 default 였던
 * `anyHost()` 는 모든 origin 허용으로 admin UI 가 외부 도메인에서 CSRF 벡터로 쓰일 수
 * 있어 위험). 명시적으로 host 가 콤마 분리로 들어와 있을 때만 install + 그 host 들만 허용.
 */
fun Application.configureCors() {
    val allowedOrigins = (System.getenv("CORS_ALLOWED_ORIGINS")
        ?: System.getProperty("CORS_ALLOWED_ORIGINS"))
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val log = LoggerFactory.getLogger("com.vibi.bff.plugins.Cors")
    if (allowedOrigins == null) {
        log.info("CORS_ALLOWED_ORIGINS not set — CORS not installed (same-origin only)")
        return
    }

    install(CORS) {
        allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { origin ->
            allowHost(
                origin.removePrefix("https://").removePrefix("http://"),
                schemes = listOf("http", "https"),
            )
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
}
