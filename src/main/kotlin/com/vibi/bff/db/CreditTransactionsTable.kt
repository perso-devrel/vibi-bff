package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * credit_transactions — IAP 영수증 1건당 1 row (apple/google/admin).
 *
 * `(platform, transactionId)` UNIQUE — 모바일이 retry 로 같은 영수증을 두 번 보내도
 * `INSERT ... ON CONFLICT` 로 중복 가산 차단 (idempotency).
 * user_id 는 회원탈퇴 시 SET NULL — 결제 이력은 환불/감사용으로 익명 보존.
 *
 * 비-IAP 잔액 변동 (signup 보너스 / consume / refund) 은 별도 [CreditLedgerTable] 에 기록.
 * V5 의 platform CHECK 가 ('apple','google') 만 허용해 'signup'/'consume'/'refund' 등을
 * 여기 끼워넣을 수 없고, CHECK 이름이 dialect 마다 달라 단일 ALTER 로 확장하기도 까다로워
 * 분리 설계. (admin 도 이 CHECK 와 충돌하지만 본 PR 범위 외 — 별개 이슈.)
 */
object CreditTransactionsTable : LongIdTable("credit_transactions", "id") {
    val userId = uuid("user_id").nullable()
    val platform = varchar("platform", 16)
    val transactionId = varchar("transaction_id", 255)
    val productId = varchar("product_id", 128)
    val credits = integer("credits")
    val createdAt = timestamp("created_at")
}
