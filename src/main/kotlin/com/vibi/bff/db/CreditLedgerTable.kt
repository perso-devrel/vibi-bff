package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * credit_ledger — IAP 외 잔액 변동 ledger. V8 마이그레이션과 1:1.
 *
 * kind:
 *   signup  — 신규 가입 1회 보너스. ref_id = "signup-<userId>"
 *   consume — /separate 잡 시작 시 차감. ref_id = "consume-<jobId>"
 *   refund  — 잡 실패/취소 시 환불. ref_id = "refund-<jobId>" (이중 환불 차단)
 *
 * (kind, ref_id) UNIQUE 가 모든 멱등 보장의 핵심 — 같은 사용자가 두 번 가입 보너스를 받지
 * 못하고, 같은 잡이 두 번 차감/환불되지 못한다.
 *
 * credits 는 항상 양수 magnitude. 잔액 방향은 kind 로 판별 (signup/refund 는 +, consume 은 -).
 * user_credits.balance 가 잔액 source-of-truth — 본 ledger SUM 으로 재구성하지 않음 (race 안전).
 *
 * user_id 는 회원탈퇴 시 SET NULL — 분석 row 익명 보존.
 */
object CreditLedgerTable : LongIdTable("credit_ledger", "id") {
    val userId = uuid("user_id").nullable()
    val kind = varchar("kind", 16)
    val refId = varchar("ref_id", 64)
    val credits = integer("credits")
    val createdAt = timestamp("created_at")
}
