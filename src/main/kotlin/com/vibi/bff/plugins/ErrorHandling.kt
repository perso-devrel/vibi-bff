package com.vibi.bff.plugins

import com.vibi.bff.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.sentry.Sentry
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
    val log = LoggerFactory.getLogger("com.vibi.bff.plugins.ErrorHandling")

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
            // 한 줄 INFO — silent 4xx 가 발생해도 어느 라우트에서 무슨 코드로 떨어졌는지
            // 흔적은 남겨야 client 측 잘못된 요청 디버깅 가능.
            log.info("4xx {} {} -> {} ({}{})",
                call.request.local.method.value,
                call.request.local.uri,
                cause.statusCode.value,
                cause.errorCode,
                cause.detail?.let { ": $it" } ?: "",
            )
            call.respond(cause.statusCode, ErrorResponse(error = cause.errorCode, detail = cause.detail))
        }
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            // call.receive<T>() 가 malformed/누락 필드 JSON 에 던지는 예외 (ContentTransformation
            // Exception 포함). 기존엔 generic Throwable 핸들러로 떨어져 500 → 클라가 서버 장애로
            // 오인 + Sentry 가 클라 검증 오류로 도배. 안정적 400 + 머신리더블 코드로 통일.
            // 내부 메시지는 노출하지 않는다 (sanitize 규약).
            log.info("4xx {} {} -> 400 (bad_request: {})",
                call.request.local.method.value,
                call.request.local.uri,
                cause.message,
            )
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "bad_request"))
        }
        exception<IllegalArgumentException> { call, cause ->
            log.info("4xx {} {} -> 400 ({})",
                call.request.local.method.value,
                call.request.local.uri,
                cause.message ?: "Bad request",
            )
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            if (cause.isClientDisconnect()) {
                log.debug("Client disconnected mid-response: {}", cause.message)
                return@exception
            }
            log.error("Unhandled exception", cause)
            // Sentry — DSN 미설정이면 capture no-op. ApiErrorException 4xx 분기는 위에서 이미
            // 처리됐고 여기는 진짜 unhandled (대부분 5xx) 만 떨어진다. captureException 호출은
            // best-effort 라 실패해도 응답에 영향 없음.
            runCatching { Sentry.captureException(cause) }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(error = "Internal server error")
            )
        }
    }
}
