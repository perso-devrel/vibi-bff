package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * credit_transactions — IAP 영수증 1건당 1 row.
 *
 * `(platform, transactionId)` UNIQUE — 모바일이 retry 로 같은 영수증을 두 번 보내도
 * `INSERT ... ON CONFLICT` 로 중복 가산 차단 (idempotency).
 * user_id 는 회원탈퇴 시 SET NULL — 결제 이력은 환불/감사용으로 익명 보존.
 */
object CreditTransactionsTable : LongIdTable("credit_transactions", "id") {
    val userId = uuid("user_id").nullable()
    val platform = varchar("platform", 16)
    val transactionId = varchar("transaction_id", 255)
    val productId = varchar("product_id", 128)
    val credits = integer("credits")
    val createdAt = timestamp("created_at")
}
