package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.CreditCost
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.InsufficientCreditsException
import com.vibi.bff.service.SIGNUP_BONUS_CREDITS
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `grantPurchase cross-user replay grants 0 and original owner keeps credit`() {
        // 보안 속성(#6): 다른 user 가 이미 청구된 영수증을 재청구해도 가산 0 — 영수증은
        // 최초 청구자에게만 귀속. (cross-user 시도는 fraud 신호로 warn 로깅되지만 동작은 granted=0.)
        val owner = users.upsert(AuthProvider.GOOGLE, "g-owner", "owner@example.com", "Owner", null)
        val attacker = users.upsert(AuthProvider.APPLE, "a-atk", "atk@example.com", "Atk", null)

        val first = credits.grantPurchase(owner.id, "apple", "tx-shared", "vibi.credits.50", 50)
        assertEquals(50, first.granted)

        val replay = credits.grantPurchase(attacker.id, "apple", "tx-shared", "vibi.credits.50", 50)
        assertEquals(0, replay.granted)            // attacker 는 한 푼도 못 받음
        assertEquals(0, replay.balance)            // attacker 잔액 그대로 0
        assertEquals(50, credits.balance(owner.id)) // 최초 청구자 잔액 보존
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

    // ── 신규 가입 보너스 ─────────────────────────────────────────────────────

    @Test
    fun `grantSignupBonus adds SIGNUP_BONUS_CREDITS once`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val r = credits.grantSignupBonus(u.id)
        assertEquals(SIGNUP_BONUS_CREDITS, r.granted)
        assertEquals(SIGNUP_BONUS_CREDITS, r.balance)
    }

    @Test
    fun `grantSignupBonus is idempotent on second call`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id)
        val again = credits.grantSignupBonus(u.id)
        // 두 번째 호출은 (platform='signup', txId='signup-<userId>') UNIQUE 가 막아 가산 안 됨.
        assertEquals(0, again.granted)
        assertEquals(SIGNUP_BONUS_CREDITS, again.balance)
    }

    // ── reserve / refund ────────────────────────────────────────────────────

    @Test
    fun `reserve deducts balance and records consume ledger`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id) // = SIGNUP_BONUS_CREDITS

        val r = credits.reserve(u.id, jobId = "sep-job-1", credits = 2)
        assertEquals(2, r.charged)
        assertEquals(SIGNUP_BONUS_CREDITS - 2, r.balance)
        assertEquals(SIGNUP_BONUS_CREDITS - 2, credits.balance(u.id))
    }

    @Test
    fun `reserve throws when balance insufficient`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        // grantSignupBonus 안 함 → balance=0.
        val ex = assertFailsWith<InsufficientCreditsException> {
            credits.reserve(u.id, "sep-x", 1)
        }
        assertEquals(1, ex.required)
        assertEquals(0, ex.balance)
        assertEquals(0, credits.balance(u.id))
    }

    @Test
    fun `reserve is idempotent on same jobId (no double-charge)`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id) // 3 credits

        val first = credits.reserve(u.id, "sep-dup", 2)
        val second = credits.reserve(u.id, "sep-dup", 2) // same jobId — 이미 차감됨
        assertEquals(2, first.charged)
        assertEquals(2, second.charged) // 멱등: 같은 metadata 반환
        assertEquals(SIGNUP_BONUS_CREDITS - 2, credits.balance(u.id)) // 한 번만 차감
    }

    @Test
    fun `refund restores balance and is idempotent on double call`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantSignupBonus(u.id)
        credits.reserve(u.id, "sep-refund", 2)
        assertEquals(SIGNUP_BONUS_CREDITS - 2, credits.balance(u.id))

        val r1 = credits.refund("sep-refund")
        assertNotNull(r1)
        assertEquals(2, r1.granted)
        assertEquals(SIGNUP_BONUS_CREDITS, r1.balance)

        // 두 번째 환불은 (platform='refund', txId='refund-<jobId>') UNIQUE 가 차단 → 가산 안 됨.
        val r2 = credits.refund("sep-refund")
        assertNotNull(r2)
        assertEquals(0, r2.granted)
        assertEquals(SIGNUP_BONUS_CREDITS, r2.balance) // 잔액 변화 없음
    }

    @Test
    fun `refund on never-reserved jobId returns null gracefully`() {
        // 환불 hook 이 미인증 / 무료 잡 분기에서도 무해하게 통과해야 한다 (SeparationService FAILED hook 호환).
        val r = credits.refund("sep-never-charged")
        assertNull(r)
    }

    // ── CreditCost ──────────────────────────────────────────────────────────

    @Test
    fun `CreditCost forSeparation charges 1 credit per started 1min block with boundary grace`() {
        // 정책: 시작된 1분 블록당 1 크레딧 (ceil), 최소 1. block 경계는 BOUNDARY_GRACE_MS(1초)
        // 만큼 뒤로 밀려 인코더 패딩 off-by-one 을 흡수한다. 모바일·플러그인 공통 단가.
        assertEquals(1, CreditCost.forSeparation(0))             // 0 → floor 1
        assertEquals(1, CreditCost.forSeparation(-100))          // 음수(probe 완전 실패) → floor 1
        assertEquals(1, CreditCost.forSeparation(1))             // 1ms → 1
        assertEquals(1, CreditCost.forSeparation(60_000))        // 정확히 1분 → 1
        assertEquals(1, CreditCost.forSeparation(60_500))        // 1분+0.5초 (패딩) → grace 흡수 → 1
        assertEquals(1, CreditCost.forSeparation(61_000))        // 1분+1초 (grace 경계) → 1
        assertEquals(2, CreditCost.forSeparation(61_001))        // 1분+1초+1ms → 2
        assertEquals(2, CreditCost.forSeparation(120_000))       // 2분 → 2
        assertEquals(2, CreditCost.forSeparation(121_000))       // 2분+1초 → 2
        assertEquals(3, CreditCost.forSeparation(121_001))       // 2분+1초+1ms → 3
        assertEquals(5, CreditCost.forSeparation(300_000))       // 5분 → 5
        assertEquals(60, CreditCost.forSeparation(60 * 60_000L)) // 60분 → 60
    }
}
