package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * 외부 API 호출 추적 테이블. V4__external_api_calls.sql 과 1:1.
 *
 * provider/endpoint/success/latency_ms 가 admin 대시보드의 일별 카운트 + 실패율 + p95 latency
 * 계산 원본. error_class 는 성공이면 null, 실패면 예외 클래스명 (예: 'PersoApiException').
 */
object ExternalApiCallsTable : LongIdTable("external_api_calls", "id") {
    val provider = text("provider")
    val endpoint = text("endpoint")
    val statusCode = integer("status_code").nullable()
    val success = bool("success")
    val latencyMs = long("latency_ms")
    val errorClass = text("error_class").nullable()
    val createdAt = timestamp("created_at")
}
