package com.vibi.bff.service

import com.vibi.bff.db.CreditTransactionsTable
import com.vibi.bff.db.UserCreditsTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/**
 * user_credits 잔액 + credit_transactions 영속화.
 *
 * - [balance] — 잔액 조회. row 가 없으면 0.
 * - [grantPurchase] — 영수증 1건을 idempotent 하게 처리. 동일 (platform, transactionId)
 *   가 이미 처리됐으면 재가산 없이 [PurchaseOutcome.granted] = 0 + 현재 잔액 반환.
 *
 * `consume` (잡 시작 시 잔액 차감) 은 본 PR 범위 밖 — 음원 분리 / 자동 더빙 라우트가 실제로
 * "1잡 = 1크레딧" 차감을 도입할 때 동일 패턴으로 `SELECT ... FOR UPDATE` + UPDATE 추가.
 */
class CreditRepository {

    data class PurchaseOutcome(
        val granted: Int,
        val balance: Int,
    )

    fun balance(userId: UUID): Int = transaction {
        UserCreditsTable
            .select(UserCreditsTable.balance)
            .where { UserCreditsTable.userId eq userId }
            .singleOrNull()
            ?.get(UserCreditsTable.balance)
            ?: 0
    }

    /**
     * 영수증 1건의 idempotent 가산. 영수증 서버 검증 통과 직후 호출.
     *
     * `insertIgnore` 로 (platform, transactionId) UNIQUE 제약을 단일 statement 로 활용 —
     * 동시 호출 race 도 DB 가 처리 (select-then-insert 의 TOCTOU 없음). insertedCount 가 0
     * 이면 이미 처리된 영수증으로 간주하고 가산 skip.
     */
    fun grantPurchase(
        userId: UUID,
        platform: String,
        transactionId: String,
        productId: String,
        credits: Int,
    ): PurchaseOutcome = transaction {
        require(credits > 0) { "credits must be positive" }
        val now = Instant.now()

        val inserted = CreditTransactionsTable.insertIgnore {
            it[CreditTransactionsTable.userId] = userId
            it[CreditTransactionsTable.platform] = platform
            it[CreditTransactionsTable.transactionId] = transactionId
            it[CreditTransactionsTable.productId] = productId
            it[CreditTransactionsTable.credits] = credits
            it[CreditTransactionsTable.createdAt] = now
        }.insertedCount
        if (inserted == 0) {
            return@transaction PurchaseOutcome(granted = 0, balance = readBalance(userId))
        }

        UserCreditsTable.upsert(
            UserCreditsTable.userId,
            onUpdate = {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[UserCreditsTable.balance] = UserCreditsTable.balance + credits
                }
                it[UserCreditsTable.updatedAt] = now
            },
        ) {
            it[UserCreditsTable.userId] = userId
            it[UserCreditsTable.balance] = credits
            it[UserCreditsTable.updatedAt] = now
        }
        PurchaseOutcome(granted = credits, balance = readBalance(userId))
    }

    private fun readBalance(userId: UUID): Int =
        UserCreditsTable
            .select(UserCreditsTable.balance)
            .where { UserCreditsTable.userId eq userId }
            .singleOrNull()
            ?.get(UserCreditsTable.balance)
            ?: 0
}
