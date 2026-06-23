package com.vibi.bff.routes

import com.vibi.bff.MAX_SEPARATION_AUDIO_SIZE
import com.vibi.bff.model.PeaksError
import com.vibi.bff.model.PeaksResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.PeaksService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.File
import java.util.concurrent.Semaphore
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.routes.PeaksRoutes")

// 입력 파형 미리보기 — 디코드(ffmpeg)가 RAM/CPU 를 쓰므로 동시 실행 cap. 초과 시 503(큐잉 X —
// 큰 멀티파트 바디를 RAM 에 고정하지 않도록). plugin 의 MAX_CONCURRENT_PEAKS 와 동일 기본 2.
private val MAX_CONCURRENT_PEAKS =
    System.getenv("MAX_CONCURRENT_PEAKS")?.toIntOrNull()?.coerceAtLeast(1) ?: 2
private val peaksGate = Semaphore(MAX_CONCURRENT_PEAKS)

private val PEAKS_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav")

/**
 * POST /api/v2/peaks — 입력 오디오의 파형 미리보기 peak. UXP 가 mp3/AAC 를 직접 디코드 못 해 서버에
 * 요청한다. 크레딧 무차감. plugin server/ 의 routes/peaks.ts 포팅.
 */
fun Route.peaksRoutes(fileStorage: FileStorageService, jwtSecret: String?) {
    post("/peaks") {
        // 무차감이지만 익명 대량 호출(CPU 남용) 차단 — 인증 요구.
        jwtSecret?.let { call.requireUser(it) }
        // 동시 실행 cap. 초과면 큐잉 없이 503(큰 바디를 RAM 에 묶지 않음).
        if (!peaksGate.tryAcquire()) {
            call.response.header(HttpHeaders.RetryAfter, "5")
            call.respond(HttpStatusCode.ServiceUnavailable, PeaksError("server_busy"))
            return@post
        }
        var audio: File? = null
        try {
            audio = parseSingleFileUpload(
                call.receiveMultipart(formFieldLimit = MAX_SEPARATION_AUDIO_SIZE),
                fileStorage,
                MAX_SEPARATION_AUDIO_SIZE,
                fileFieldName = "audio",
            ) ?: throw ApiErrorException(HttpStatusCode.BadRequest, "audio_required")

            val ext = audio.extension.lowercase()
            if (ext !in PEAKS_AUDIO_EXTENSIONS) {
                throw ApiErrorException(HttpStatusCode.UnsupportedMediaType, "unsupported_format")
            }

            val result = PeaksService.computePeaks(audio)
            call.respond(HttpStatusCode.OK, PeaksResponse(peaks = result.peaks, durationSec = result.durationSec))
        } catch (e: ApiErrorException) {
            throw e
        } catch (e: Exception) {
            log.warn("peaks failed: {}", e.message)
            throw ApiErrorException(HttpStatusCode.InternalServerError, "peaks_failed")
        } finally {
            audio?.let { runCatching { it.delete() } }
            peaksGate.release()
        }
    }
}
