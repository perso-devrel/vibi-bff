package com.vibi.bff.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors() {
    val allowedOrigins = System.getenv("CORS_ALLOWED_ORIGINS")
        ?: System.getProperty("CORS_ALLOWED_ORIGINS")

    install(CORS) {
        if (allowedOrigins.isNullOrBlank()) {
            anyHost()
        } else {
            allowedOrigins.split(",").map { it.trim() }.forEach { origin ->
                allowHost(origin.removePrefix("https://").removePrefix("http://"), schemes = listOf("http", "https"))
            }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
}
