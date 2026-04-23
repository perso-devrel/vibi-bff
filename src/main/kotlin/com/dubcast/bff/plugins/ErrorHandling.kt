package com.dubcast.bff.plugins

import com.dubcast.bff.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

class NotFoundException(message: String) : RuntimeException(message)
class ElevenLabsApiException(val statusCode: Int, val body: String) : RuntimeException("ElevenLabs API error $statusCode: $body")
class PersoApiException(val statusCode: Int, val body: String) : RuntimeException("Perso API error $statusCode: $body")

// Structured API error: lets a handler specify the wire error code + detail
// independently of a free-form message. Use for validation failures where
// the client switches on the `error` field (e.g. "trim_end_exceeds_duration").
class ApiErrorException(
    val statusCode: HttpStatusCode,
    val errorCode: String,
    val detail: String? = null,
) : RuntimeException("$errorCode${detail?.let { ": $it" } ?: ""}")

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
            val message = when (cause.statusCode) {
                401 -> "Authentication failed with upstream service"
                429 -> "Rate limit exceeded, please try again later"
                in 400..499 -> "Invalid request to upstream service"
                else -> "Upstream service unavailable"
            }
            call.respond(status, ErrorResponse(error = message))
        }
        exception<PersoApiException> { call, cause ->
            log.error("Perso API error: {} - {}", cause.statusCode, cause.body)
            val status = when (cause.statusCode) {
                402 -> HttpStatusCode.PaymentRequired
                429 -> HttpStatusCode.TooManyRequests
                in 400..499 -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.BadGateway
            }
            val message = when (cause.statusCode) {
                401 -> "Authentication failed with Perso"
                402 -> "Insufficient Perso quota"
                429 -> "Perso rate limit exceeded, please try again later"
                in 400..499 -> "Invalid request to Perso"
                else -> "Perso service unavailable"
            }
            call.respond(status, ErrorResponse(error = message))
        }
        exception<ApiErrorException> { call, cause ->
            call.respond(cause.statusCode, ErrorResponse(error = cause.errorCode, detail = cause.detail))
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
