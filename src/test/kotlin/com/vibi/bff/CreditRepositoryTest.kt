package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H2 PostgreSQL mode 로 Flyway 마이그레이션 + Exposed 같은 경로 검증.
 * V5 의 `user_credits` 테이블 + idempotent 가산 흐름이 핵심.
 */
class CreditRepositoryTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var users: UserRepository
    private lateinit var credits: CreditRepository

    @BeforeTest
    fun setup() {
        val unique = "test_" + System.nanoTime()
        dataSource = DbBootstrap.init(
            DbConfig(
                jdbcUrl = "jdbc:h2:mem:$unique;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
                maxPoolSize = 2,
            )
        )
        users = UserRepository()
        credits = CreditRepository()
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    @Test
    fun `balance returns 0 for unseen user`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        assertEquals(0, credits.balance(u.id))
    }

    @Test
    fun `grantPurchase adds credits and returns granted amount`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val r = credits.grantPurchase(
            userId = u.id,
            platform = "apple",
            transactionId = "tx-1",
            productId = "vibi.credits.50",
            credits = 50,
        )
        assertEquals(50, r.granted)
        assertEquals(50, r.balance)
        assertEquals(50, credits.balance(u.id))
    }

    @Test
    fun `grantPurchase is idempotent on duplicate transactionId`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantPurchase(u.id, "apple", "tx-1", "vibi.credits.50", 50)
        val r2 = credits.grantPurchase(u.id, "apple", "tx-1", "vibi.credits.50", 50)
        assertEquals(0, r2.granted)
        assertEquals(50, r2.balance) // 가산 안 됨
    }

    @Test
    fun `different platforms with same transactionId both succeed`() {
        // Apple/Google 영수증 시스템이 독립 — UNIQUE (platform, transactionId) 라 platform 다르면 양립.
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val a = credits.grantPurchase(u.id, "apple", "tx-1", "vibi.credits.10", 10)
        val g = credits.grantPurchase(u.id, "google", "tx-1", "vibi.credits.10", 10)
        assertEquals(10, a.granted)
        assertEquals(10, g.granted)
        assertEquals(20, credits.balance(u.id))
    }

    @Test
    fun `delete user cascades user_credits row`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantPurchase(u.id, "apple", "tx-1", "vibi.credits.10", 10)
        assertEquals(10, credits.balance(u.id))

        val deleted = users.delete(u.id)
        assertTrue(deleted >= 1)
        // user_credits row 도 CASCADE 로 사라지므로 잔액은 0 으로 fallback.
        assertEquals(0, credits.balance(u.id))
    }
}
