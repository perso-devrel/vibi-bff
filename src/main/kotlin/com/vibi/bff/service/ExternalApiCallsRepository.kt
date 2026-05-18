package com.vibi.bff.service

import com.vibi.bff.db.ExternalApiCallsTable
import com.vibi.bff.plugins.PersoApiException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 외부 API 호출 (Perso / Gemini / ...) instrumentation. 운영 admin 대시보드의 비용/안정성
 * 가시성 source-of-truth.
 *
 * 모든 write 는 try/catch swallow — analytics 실패가 user-facing flow 깨뜨리지 않도록.
 * Insert 자체도 [Dispatchers.IO] 로 wrap 해 Netty 이벤트 루프를 막지 않게.
 */
class ExternalApiCallsRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun record(
        provider: String,
        endpoint: String,
        statusCode: Int?,
        success: Boolean,
        latencyMs: Long,
        errorClass: String?,
    ) = withContext(Dispatchers.IO) {
        try {
            transaction {
                ExternalApiCallsTable.insert {
                    it[ExternalApiCallsTable.provider] = provider
                    it[ExternalApiCallsTable.endpoint] = endpoint
                    it[ExternalApiCallsTable.statusCode] = statusCode
                    it[ExternalApiCallsTable.success] = success
                    it[ExternalApiCallsTable.latencyMs] = latencyMs
                    it[ExternalApiCallsTable.errorClass] = errorClass
                    it[ExternalApiCallsTable.createdAt] = Instant.now()
                }
            }
        } catch (e: Exception) {
            log.warn(
                "External call analytics write failed: {}/{} ({}: {})",
                provider, endpoint, e.javaClass.simpleName, e.message,
            )
        }
    }
}

/**
 * 외부 API 호출 wrapper. 시작 시각·실패 클래스·HTTP status 를 자동 캡처해 receiver 에 기록.
 *
 * Receiver 가 null 이면 instrumentation 없이 [block] 만 실행 — dev/test 에서 repo 미설정 시
 * 호출자 분기 없이 동일 시그니처로 사용.
 *
 * [PersoApiException] 은 status code 가 의미있는 정보이므로 그대로 추출. 그 외 예외는
 * statusCode=null, success=false 로 기록.
 */
suspend inline fun <T> ExternalApiCallsRepository?.withExternalCall(
    provider: String,
    endpoint: String,
    block: () -> T,
): T {
    val repo = this ?: return block()
    val start = System.currentTimeMillis()
    var statusCode: Int? = null
    var success = false
    var errorClass: String? = null
    try {
        val result = block()
        statusCode = 200
        success = true
        return result
    } catch (e: Throwable) {
        errorClass = e.javaClass.simpleName
        statusCode = when (e) {
            is PersoApiException -> e.statusCode
            else -> null
        }
        throw e
    } finally {
        repo.record(
            provider = provider,
            endpoint = endpoint,
            statusCode = statusCode,
            success = success,
            latencyMs = System.currentTimeMillis() - start,
            errorClass = errorClass,
        )
    }
}
