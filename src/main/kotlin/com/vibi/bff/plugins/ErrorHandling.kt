package com.vibi.bff.plugins

import com.vibi.bff.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException

/**
 * Netty/Ktor 가 client 가 응답 받기 전 끊었을 때 던지는 예외 식별. 1차로 알려진 타입을 잡고,
 * generic IOException 은 message contains 로 fallback (Ktor `ChannelWriteException` 등 unwrap
 * 가능한 타입은 최상단 메시지 매칭으로 흡수). 의도: ERROR 노이즈 다운그레이드. 진짜 IO 실패
 * (디스크 풀 등) 는 매칭 안 돼 그대로 ERROR.
 */
private fun Throwable.isClientDisconnect(): Boolean {
    if (this is ClosedChannelException || this is EOFException) return true
    if (this is SocketException) {
        val msg = message ?: return true
        return "Broken pipe" in msg || "Connection reset" in msg
    }
    if (this is IOException) {
        val msg = message ?: return false
        return "Cannot write to a channel" in msg ||
            "Broken pipe" in msg ||
            "Connection reset" in msg ||
            "channel was closed" in msg
    }
    return false
}

class NotFoundException(message: String) : RuntimeException(message)
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
        exception<PersoApiException> { call, cause ->
            log.error("Perso API error: {} - {}", cause.statusCode, cause.body)
            val status = when (cause.statusCode) {
                401 -> HttpStatusCode.Unauthorized
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
            if (cause.isClientDisconnect()) {
                log.debug("Client disconnected mid-response: {}", cause.message)
                return@exception
            }
            log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "Internal server error")
            )
        }
    }
}
