package com.dubcast.bff.plugins

import com.dubcast.bff.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

class NotFoundException(message: String) : RuntimeException(message)
class ElevenLabsApiException(val statusCode: Int, val body: String) : RuntimeException("ElevenLabs API error $statusCode: $body")

fun Application.configureErrorHandling() {
    val log = LoggerFactory.getLogger("ErrorHandling")

    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = cause.message ?: "Not found"))
        }
        exception<ElevenLabsApiException> { call, cause ->
            log.error("ElevenLabs API error: {} - {}", cause.statusCode, cause.body)
            val status = when (cause.statusCode) {
                429 -> HttpStatusCode.TooManyRequests
                in 400..499 -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.BadGateway
            }
            call.respond(status, ErrorResponse(error = "Upstream API error", detail = cause.body))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "Internal server error")
            )
        }
    }
}
