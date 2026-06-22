package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * H2 PostgreSQL mode 로 Flyway 마이그레이션 + raw SQL 같은 경로 검증.
 * 신규 surface (수익/IAP, 잡 성공·실패 분해) 의 집계 정확성 회귀 가드.
 *
 * H2 는 V9 의 hash-named CHECK 제약이 'admin' platform 을 거부하므로 admin-grant 케이스는
 * 여기서 커버하지 않는다 (adminGrantedCredits=0 만 확인). apple/google 경로는 정상.
 */
class AdminRepositoryTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var users: UserRepository
    private lateinit var credits: CreditRepository
    private lateinit var admin: AdminRepository

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
        admin = AdminRepository()
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    private fun insertRenderJob(userId: UUID, status: String) = transaction {
        val id = "render-" + UUID.randomUUID()
        exec(
            "INSERT INTO render_jobs (id, user_id, source_duration_ms, status) " +
                "VALUES ('$id', CAST('$userId' AS UUID), 1000, '$status')",
        )
    }

    private fun insertSeparationJob(userId: UUID, status: String) = transaction {
        val id = "sep-" + UUID.randomUUID()
        exec(
            "INSERT INTO separation_jobs (id, user_id, source_duration_ms, status) " +
                "VALUES ('$id', CAST('$userId' AS UUID), 1000, '$status')",
        )
    }

    /**
     * credit_transactions 에 직접 INSERT — platform/created_at 을 명시 제어한다.
     * created_at 은 timestamptz 라 파라미터(Instant)로 바인딩. CreditRepository.grantPurchase
     * 는 created_at=now 고정 + platform CHECK(apple/google) 이라 과거시각·admin 케이스 못 만듦.
     */
    private fun insertTxn(userId: UUID, platform: String, txId: String, credits: Int, createdAt: Instant) = transaction {
        exec(
            "INSERT INTO credit_transactions (user_id, platform, transaction_id, product_id, credits, created_at) " +
                "VALUES (CAST('$userId' AS UUID), '$platform', '$txId', 'vibi.credits.test', $credits, ?)",
            args = listOf<Pair<IColumnType<*>, Any?>>(JavaInstantColumnType() to createdAt),
        )
    }

    /**
     * H2 의 platform CHECK 제약을 제거해 platform='admin' INSERT 를 허용한다.
     * V9 가 named 제약을 admin 허용으로 재생성하지만 V5 의 hash-named 원본 제약이 H2 에 잔존해
     * 여전히 admin 을 거부한다(V9 주석). CHECK_CLAUSE 에 'apple' 이 들어간 제약을 모두 드롭 —
     * credits>0 제약은 'apple' 을 포함 안 해 보존된다.
     */
    private fun allowAdminPlatformInH2() = transaction {
        val names = mutableListOf<String>()
        exec(
            "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CHECK_CLAUSE LIKE '%apple%'",
        ) { rs -> while (rs.next()) names += rs.getString(1) }
        names.forEach { exec("ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS \"$it\"") }
    }

    // ── getJobStatusBreakdown ────────────────────────────────────────────────

    @Test
    fun `job status breakdown counts success failure in-progress per type`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        // render: 성공=COMPLETED, 나머지 진행중/실패
        insertRenderJob(u.id, "COMPLETED")
        insertRenderJob(u.id, "COMPLETED")
        insertRenderJob(u.id, "FAILED")
        insertRenderJob(u.id, "PROCESSING")
        // separation: 성공=READY (COMPLETED 아님!), 진행중은 QUEUED/SUBMITTING/PROCESSING
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "READY")
        insertSeparationJob(u.id, "FAILED")
        insertSeparationJob(u.id, "QUEUED")
        insertSeparationJob(u.id, "PROCESSING")

        val rows = admin.getJobStatusBreakdown()
        assertEquals(listOf("render", "separation"), rows.map { it.jobType })

        val render = rows.first { it.jobType == "render" }
        assertEquals(4, render.total)
        assertEquals(2, render.succeeded)
        assertEquals(1, render.failed)
        assertEquals(1, render.inProgress)

        val sep = rows.first { it.jobType == "separation" }
        assertEquals(6, sep.total)
        assertEquals(3, sep.succeeded) // READY 만 성공으로 카운트
        assertEquals(1, sep.failed)
        assertEquals(2, sep.inProgress) // QUEUED + PROCESSING
    }

    @Test
    fun `job status breakdown returns zeroed rows on empty db`() {
        val rows = admin.getJobStatusBreakdown()
        assertEquals(2, rows.size)
        rows.forEach {
            assertEquals(0, it.total)
            assertEquals(0, it.succeeded)
            assertEquals(0, it.failed)
            assertEquals(0, it.inProgress)
        }
    }

    // ── getRevenue ───────────────────────────────────────────────────────────

    @Test
    fun `revenue aggregates purchases credits and paying users by platform`() {
        val u1 = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val u2 = users.upsert(AuthProvider.APPLE, "a-2", "b@example.com", "B", null)
        credits.grantPurchase(u1.id, "apple", "tx-1", "vibi.credits.50", 50)
        credits.grantPurchase(u1.id, "google", "tx-2", "vibi.credits.30", 30)
        credits.grantPurchase(u2.id, "apple", "tx-3", "vibi.credits.50", 50)

        val r = admin.getRevenue()
        assertEquals(2, r.payingUsers)        // u1, u2 distinct
        assertEquals(3, r.purchaseCount)
        assertEquals(130, r.creditsSold)
        assertEquals(2, r.applePurchaseCount)
        assertEquals(1, r.googlePurchaseCount)
        assertEquals(100, r.appleCredits)
        assertEquals(30, r.googleCredits)
        assertEquals(0, r.adminGrantedCredits) // H2 라 admin platform 미사용
        // 방금 적립이라 모두 최근 30일 윈도우 안.
        assertEquals(3, r.purchaseCount30d)
        assertEquals(130, r.creditsSold30d)
    }

    @Test
    fun `revenue excludes admin grants from sales but counts them in adminGrantedCredits`() {
        allowAdminPlatformInH2()
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        val now = Instant.now()
        insertTxn(u.id, "apple", "tx-a", 50, now)
        insertTxn(u.id, "google", "tx-g", 30, now)
        insertTxn(u.id, "admin", "tx-admin", 100, now) // 수동 지급 — 매출 아님

        val r = admin.getRevenue()
        assertEquals(80, r.creditsSold)          // apple+google 만, admin 제외
        assertEquals(2, r.purchaseCount)         // admin row 제외
        assertEquals(50, r.appleCredits)
        assertEquals(30, r.googleCredits)
        assertEquals(100, r.adminGrantedCredits) // admin 만 별도 합산
    }

    @Test
    fun `revenue 30d window splits from cumulative total`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        insertTxn(u.id, "apple", "tx-now", 50, Instant.now())
        insertTxn(u.id, "apple", "tx-old", 20, Instant.now().minusSeconds(40L * 24 * 3600)) // 40일 전

        val r = admin.getRevenue()
        assertEquals(70, r.creditsSold)      // 누적엔 둘 다 포함
        assertEquals(2, r.purchaseCount)
        assertEquals(50, r.creditsSold30d)   // 30일 윈도우엔 최근 것만
        assertEquals(1, r.purchaseCount30d)
    }

    @Test
    fun `revenue is all zeros on empty db`() {
        val r = admin.getRevenue()
        assertEquals(0, r.payingUsers)
        assertEquals(0, r.purchaseCount)
        assertEquals(0, r.creditsSold)
        assertEquals(0, r.appleCredits)
        assertEquals(0, r.googleCredits)
        assertEquals(0, r.adminGrantedCredits)
    }

    // ── getRevenueDaily ──────────────────────────────────────────────────────

    @Test
    fun `revenue daily groups credits by platform within range`() {
        val u = users.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "A", null)
        credits.grantPurchase(u.id, "apple", "tx-1", "vibi.credits.50", 50)
        credits.grantPurchase(u.id, "google", "tx-2", "vibi.credits.30", 30)

        val from = Instant.now().minusSeconds(2L * 24 * 3600)
        val to = Instant.now().plusSeconds(24L * 3600)
        val rows = admin.getRevenueDaily(from, to)

        // 모두 오늘 적립 → 한 버킷에 합산.
        assertEquals(50, rows.sumOf { it.appleCredits })
        assertEquals(30, rows.sumOf { it.googleCredits })
        assertEquals(2, rows.sumOf { it.purchaseCount })
    }
}
