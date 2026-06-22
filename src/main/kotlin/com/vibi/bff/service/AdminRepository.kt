package com.vibi.bff.service

import com.vibi.bff.model.AdminActiveJob
import com.vibi.bff.model.AdminDailyStats
import com.vibi.bff.model.AdminDurationBucket
import com.vibi.bff.model.AdminExternalCallDaily
import com.vibi.bff.model.AdminJobStatusBreakdown
import com.vibi.bff.model.AdminOverview
import com.vibi.bff.model.AdminRevenue
import com.vibi.bff.model.AdminRevenueDaily
import com.vibi.bff.model.AdminSignupDaily
import com.vibi.bff.model.AdminUserJob
import com.vibi.bff.model.AdminUserOverview
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

private val INSTANT_T = JavaInstantColumnType()
private val TEXT_T = TextColumnType()
private val INT_T = IntegerColumnType()
private val UUID_T = UUIDColumnType()

private fun instantArg(v: Instant): Pair<IColumnType<*>, Any?> = INSTANT_T to v
private fun textArg(v: String): Pair<IColumnType<*>, Any?> = TEXT_T to v
private fun intArg(v: Int): Pair<IColumnType<*>, Any?> = INT_T to v
private fun uuidArg(v: UUID): Pair<IColumnType<*>, Any?> = UUID_T to v

private fun scalarLong(sql: String, args: List<Pair<IColumnType<*>, Any?>> = emptyList()): Long =
    TransactionManager.current().exec(sql, args = args) { rs ->
        rs.next(); rs.getLong(1)
    } ?: 0L

/**
 * admin 대시보드용 read-only 쿼리. mutating action 없음 (v1 의도적 제외).
 *
 * 모든 쿼리는 raw SQL — Exposed 의 group-by/aggregation API 로도 가능하나 raw 가 가독성 우위.
 * Postgres + H2 (PostgreSQL mode) 양쪽에서 동일 구문이 동작하는지 확인된 SQL 만 사용.
 */
class AdminRepository {

    /**
     * 지정 기간 [fromInclusive, toExclusive) 의 일별 render/separation 카운트 + 누적 입력 길이.
     * 날짜 단위는 UTC. 빈 날짜는 결과에 포함되지 않음 — 호출자가 필요하면 채워서 표시.
     */
    fun getDailyStats(fromInclusive: Instant, toExclusive: Instant): List<AdminDailyStats> = transaction {
        val sql = """
            SELECT
                d::date AS day,
                COALESCE(r.cnt, 0) AS render_count,
                COALESCE(r.dur, 0) AS render_duration,
                COALESCE(s.cnt, 0) AS separation_count
            FROM (
                SELECT generate_series(?::timestamp::date, (?::timestamp - INTERVAL '1 day')::date, INTERVAL '1 day') AS d
            ) days
            LEFT JOIN (
                SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt, SUM(source_duration_ms) AS dur
                FROM render_jobs WHERE created_at >= ? AND created_at < ? GROUP BY 1
            ) r ON r.day = d::date
            LEFT JOIN (
                SELECT date_trunc('day', created_at)::date AS day, COUNT(*) AS cnt
                FROM separation_jobs WHERE created_at >= ? AND created_at < ? GROUP BY 1
            ) s ON s.day = d::date
            ORDER BY d::date
        """.trimIndent()
        val results = mutableListOf<AdminDailyStats>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
            instantArg(fromInclusive), instantArg(toExclusive),
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                val day = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                results += AdminDailyStats(
                    date = day,
                    renderCount = rs.getLong("render_count"),
                    separationCount = rs.getLong("separation_count"),
                    totalSourceDurationMs = rs.getLong("render_duration"),
                )
            }
        }
        results
    }

    /**
     * 사용자 페이지 + 각 사용자의 누적 사용량. 최근 활동 시간 기준 desc 정렬.
     * total 은 같은 트랜잭션에서 count(*) — 페이지 사이 가입자 유입 차이는 v1 무시.
     *
     * [query] non-blank 면 email/name 부분일치 검색 (대소문자 무시). 인터뷰/지원 대응 시 자주 필요.
     */
    fun getUsersOverview(limit: Int, offset: Int, query: String?): Pair<List<AdminUserOverview>, Long> = transaction {
        require(limit in 1..200) { "limit must be in 1..200 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }

        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val likePattern = q?.let { "%${it.lowercase().replace("%", "\\%")}%" }
        val whereClause = if (q != null) "WHERE LOWER(u.email) LIKE ? OR LOWER(u.name) LIKE ?" else ""
        val whereArgs: List<Pair<IColumnType<*>, Any?>> =
            if (likePattern != null) listOf(textArg(likePattern), textArg(likePattern)) else emptyList()

        val total: Long = scalarLong("SELECT COUNT(*) FROM users u $whereClause", whereArgs)

        val rows = mutableListOf<AdminUserOverview>()
        val sql = """
            SELECT
                u.id, u.email, u.name, u.role, u.created_at,
                COALESCE(r.cnt, 0) AS render_count,
                COALESCE(r.dur, 0) AS render_duration,
                COALESCE(r.last_at, NULL) AS render_last,
                COALESCE(s.cnt, 0) AS sep_count,
                COALESCE(s.last_at, NULL) AS sep_last
            FROM users u
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS cnt, SUM(source_duration_ms) AS dur, MAX(created_at) AS last_at
                FROM render_jobs GROUP BY user_id
            ) r ON r.user_id = u.id
            LEFT JOIN (
                SELECT user_id, COUNT(*) AS cnt, MAX(created_at) AS last_at
                FROM separation_jobs GROUP BY user_id
            ) s ON s.user_id = u.id
            $whereClause
            ORDER BY GREATEST(
                COALESCE(r.last_at, u.created_at),
                COALESCE(s.last_at, u.created_at)
            ) DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
        if (likePattern != null) {
            args.add(textArg(likePattern))
            args.add(textArg(likePattern))
        }
        args.add(intArg(limit))
        args.add(intArg(offset))
        TransactionManager.current().exec(sql, args = args) { rs ->
            while (rs.next()) {
                val renderLast = rs.getTimestamp("render_last")?.toInstant()
                val sepLast = rs.getTimestamp("sep_last")?.toInstant()
                val created = rs.getTimestamp("created_at").toInstant()
                val lastActivity = listOfNotNull(renderLast, sepLast).maxOrNull() ?: created
                rows += AdminUserOverview(
                    userId = (rs.getObject("id") as UUID).toString(),
                    email = rs.getString("email"),
                    name = rs.getString("name"),
                    role = rs.getString("role"),
                    totalRenders = rs.getLong("render_count"),
                    totalSeparations = rs.getLong("sep_count"),
                    totalSourceDurationMs = rs.getLong("render_duration"),
                    lastActivityAt = DateTimeFormatter.ISO_INSTANT.format(lastActivity),
                )
            }
        }
        rows to total
    }

    /**
     * 외부 API 호출 일별 추세. provider/endpoint 별 grouping — 비용 예측의 raw 데이터.
     * p95 latency 는 Postgres `percentile_cont` 사용 (H2 는 동일 함수 없어 dev/test 에선 0 fallback).
     */
    fun getExternalCallsDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminExternalCallDaily> = transaction {
        val isPostgres = TransactionManager.current().db.url.startsWith("jdbc:postgresql:")
        val p95Expr = if (isPostgres) {
            "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0)::bigint"
        } else {
            "COALESCE(MAX(latency_ms), 0)" // H2 fallback — exact p95 없으므로 max 로 근사
        }
        val sql = """
            SELECT
                date_trunc('day', created_at)::date AS day,
                provider,
                endpoint,
                COUNT(*) AS call_count,
                SUM(CASE WHEN success = false THEN 1 ELSE 0 END) AS failure_count,
                $p95Expr AS p95_latency
            FROM external_api_calls
            WHERE created_at >= ? AND created_at < ?
            GROUP BY 1, 2, 3
            ORDER BY 1, 2, 3
        """.trimIndent()
        val rows = mutableListOf<AdminExternalCallDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminExternalCallDaily(
                    date = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    provider = rs.getString("provider"),
                    endpoint = rs.getString("endpoint"),
                    callCount = rs.getLong("call_count"),
                    failureCount = rs.getLong("failure_count"),
                    p95LatencyMs = rs.getLong("p95_latency"),
                )
            }
        }
        rows
    }

    /**
     * 영상 길이 분포 5 bucket. Perso 영업 미팅에서 자주 묻는 "어떤 길이가 주로 분리되나" 답변.
     * Postgres CASE WHEN 으로 buckets 직접 — 차트가 0 행도 표시할 수 있도록 모든 bucket 반환.
     */
    fun getDurationHistogram(): List<AdminDurationBucket> = transaction {
        val sql = """
            SELECT
                CASE
                    WHEN source_duration_ms < 60000 THEN '0-1m'
                    WHEN source_duration_ms < 300000 THEN '1-5m'
                    WHEN source_duration_ms < 900000 THEN '5-15m'
                    WHEN source_duration_ms < 3600000 THEN '15-60m'
                    ELSE '60m+'
                END AS bucket,
                COUNT(*) AS c
            FROM render_jobs
            GROUP BY 1
        """.trimIndent()
        val counts = mutableMapOf<String, Long>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) counts[rs.getString("bucket")] = rs.getLong("c")
        }
        // 0 count bucket 도 명시적으로 포함해 차트가 일관된 5 칸으로 렌더링.
        listOf("0-1m", "1-5m", "5-15m", "15-60m", "60m+").map { b ->
            AdminDurationBucket(bucket = b, count = counts[b] ?: 0L)
        }
    }

    /**
     * 진행 중 잡 (render + separation, status='PROCESSING'). 서버 재시작 후 orphan 탐지.
     * 가장 오래된 것 먼저 (stuck 의심 가능). 최대 100개 cap.
     */
    fun getActiveJobs(): List<AdminActiveJob> = transaction {
        val sql = """
            SELECT job_type, job_id, email, source_duration_ms, created_at FROM (
                SELECT 'render' AS job_type, r.id AS job_id, u.email, r.source_duration_ms, r.created_at
                FROM render_jobs r JOIN users u ON u.id = r.user_id
                WHERE r.status = 'PROCESSING'
                UNION ALL
                SELECT 'separation' AS job_type, s.id AS job_id, u.email, s.source_duration_ms, s.created_at
                FROM separation_jobs s JOIN users u ON u.id = s.user_id
                WHERE s.status = 'PROCESSING'
            ) t
            ORDER BY created_at ASC
            LIMIT 100
        """.trimIndent()
        val rows = mutableListOf<AdminActiveJob>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                rows += AdminActiveJob(
                    jobType = rs.getString("job_type"),
                    jobId = rs.getString("job_id"),
                    userEmail = rs.getString("email"),
                    sourceDurationMs = rs.getLong("source_duration_ms"),
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("created_at").toInstant()),
                )
            }
        }
        rows
    }

    /**
     * 일별 신규 가입자 + provider 분포. iOS-first 정책의 Apple 사용자 비중 검증용.
     */
    fun getSignupDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminSignupDaily> = transaction {
        val sql = """
            SELECT
                date_trunc('day', created_at)::date AS day,
                SUM(CASE WHEN provider = 'google' THEN 1 ELSE 0 END) AS google_count,
                SUM(CASE WHEN provider = 'apple'  THEN 1 ELSE 0 END) AS apple_count
            FROM users
            WHERE created_at >= ? AND created_at < ?
            GROUP BY 1
            ORDER BY 1
        """.trimIndent()
        val rows = mutableListOf<AdminSignupDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminSignupDaily(
                    date = rs.getDate("day").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    googleCount = rs.getLong("google_count"),
                    appleCount = rs.getLong("apple_count"),
                )
            }
        }
        rows
    }

    /**
     * 특정 사용자의 render 잡 페이지 + 각 잡의 separation 횟수. 최신순.
     */
    fun getUserJobs(userId: UUID, limit: Int, offset: Int): Pair<List<AdminUserJob>, Long> = transaction {
        require(limit in 1..200) { "limit must be in 1..200 (got $limit)" }
        require(offset >= 0) { "offset must be >= 0 (got $offset)" }

        val total: Long = scalarLong(
            "SELECT COUNT(*) FROM render_jobs WHERE user_id = ?",
            listOf(uuidArg(userId)),
        )

        val rows = mutableListOf<AdminUserJob>()
        val sql = """
            SELECT
                r.id, r.status, r.source_duration_ms, r.created_at, r.finished_at,
                COALESCE(s.cnt, 0) AS separation_count
            FROM render_jobs r
            LEFT JOIN (
                SELECT render_job_id, COUNT(*) AS cnt FROM separation_jobs
                WHERE render_job_id IS NOT NULL GROUP BY render_job_id
            ) s ON s.render_job_id = r.id
            WHERE r.user_id = ?
            ORDER BY r.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        TransactionManager.current().exec(sql, args = listOf(
            uuidArg(userId), intArg(limit), intArg(offset),
        )) { rs ->
            while (rs.next()) {
                rows += AdminUserJob(
                    jobId = rs.getString("id"),
                    status = rs.getString("status"),
                    sourceDurationMs = rs.getLong("source_duration_ms"),
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("created_at").toInstant()),
                    finishedAt = rs.getTimestamp("finished_at")?.toInstant()?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
                    separationCount = rs.getLong("separation_count"),
                )
            }
        }
        rows to total
    }

    /**
     * 수익/IAP 요약. credit_transactions 에서 admin-grant 제외 집계 + admin grant 참고값.
     * 화폐 금액은 미저장 — "판매된 크레딧 수" 로 매출 표현. (AdminRevenue KDoc 참조)
     */
    fun getRevenue(): AdminRevenue = transaction {
        val thirtyDaysAgo = Instant.now().minusSeconds(30L * 24 * 3600)
        val sql = """
            SELECT
                COUNT(*) AS purchase_count,
                COUNT(DISTINCT user_id) AS paying_users,
                COALESCE(SUM(credits), 0) AS credits_sold,
                COALESCE(SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END), 0) AS purchase_count_30d,
                COALESCE(SUM(CASE WHEN created_at >= ? THEN credits ELSE 0 END), 0) AS credits_sold_30d,
                COALESCE(SUM(CASE WHEN platform = 'apple'  THEN 1 ELSE 0 END), 0) AS apple_count,
                COALESCE(SUM(CASE WHEN platform = 'google' THEN 1 ELSE 0 END), 0) AS google_count,
                COALESCE(SUM(CASE WHEN platform = 'apple'  THEN credits ELSE 0 END), 0) AS apple_credits,
                COALESCE(SUM(CASE WHEN platform = 'google' THEN credits ELSE 0 END), 0) AS google_credits
            FROM credit_transactions
            WHERE platform <> 'admin'
        """.trimIndent()
        var revenue = AdminRevenue(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(thirtyDaysAgo), instantArg(thirtyDaysAgo),
        )) { rs ->
            if (rs.next()) {
                revenue = revenue.copy(
                    purchaseCount = rs.getLong("purchase_count"),
                    payingUsers = rs.getLong("paying_users"),
                    creditsSold = rs.getLong("credits_sold"),
                    purchaseCount30d = rs.getLong("purchase_count_30d"),
                    creditsSold30d = rs.getLong("credits_sold_30d"),
                    applePurchaseCount = rs.getLong("apple_count"),
                    googlePurchaseCount = rs.getLong("google_count"),
                    appleCredits = rs.getLong("apple_credits"),
                    googleCredits = rs.getLong("google_credits"),
                )
            }
        }
        revenue.copy(
            adminGrantedCredits = scalarLong(
                "SELECT COALESCE(SUM(credits), 0) FROM credit_transactions WHERE platform = 'admin'",
            ),
        )
    }

    /**
     * 일별 IAP 추세 (admin-grant 제외). 빈 날짜는 미포함 — 호출자/프론트가 채워서 표시.
     */
    fun getRevenueDaily(fromInclusive: Instant, toExclusive: Instant): List<AdminRevenueDaily> = transaction {
        // 별칭은 'bucket_date' — 'day' 는 H2(PostgreSQL mode) 예약어라 AS day 가 깨진다.
        val sql = """
            SELECT
                date_trunc('day', created_at)::date AS bucket_date,
                COALESCE(SUM(CASE WHEN platform = 'apple'  THEN credits ELSE 0 END), 0) AS apple_credits,
                COALESCE(SUM(CASE WHEN platform = 'google' THEN credits ELSE 0 END), 0) AS google_credits,
                COUNT(*) AS purchase_count
            FROM credit_transactions
            WHERE platform <> 'admin' AND created_at >= ? AND created_at < ?
            GROUP BY 1
            ORDER BY 1
        """.trimIndent()
        val rows = mutableListOf<AdminRevenueDaily>()
        TransactionManager.current().exec(sql, args = listOf(
            instantArg(fromInclusive), instantArg(toExclusive),
        )) { rs ->
            while (rs.next()) {
                rows += AdminRevenueDaily(
                    date = rs.getDate("bucket_date").toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    appleCredits = rs.getLong("apple_credits"),
                    googleCredits = rs.getLong("google_credits"),
                    purchaseCount = rs.getLong("purchase_count"),
                )
            }
        }
        rows
    }

    /**
     * render/separation 잡의 성공/실패/진행중 분해. 성공 status 가 종류별로 다름:
     * render=COMPLETED, separation=READY. 실패는 둘 다 FAILED, 나머지는 inProgress.
     */
    fun getJobStatusBreakdown(): List<AdminJobStatusBreakdown> = transaction {
        fun breakdown(table: String, successStatus: String): AdminJobStatusBreakdown {
            val sql = """
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN status = '$successStatus' THEN 1 ELSE 0 END) AS succeeded,
                    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM $table
            """.trimIndent()
            var total = 0L
            var succeeded = 0L
            var failed = 0L
            TransactionManager.current().exec(sql) { rs ->
                if (rs.next()) {
                    total = rs.getLong("total")
                    succeeded = rs.getLong("succeeded")
                    failed = rs.getLong("failed")
                }
            }
            return AdminJobStatusBreakdown(
                jobType = if (table == "render_jobs") "render" else "separation",
                total = total,
                succeeded = succeeded,
                failed = failed,
                inProgress = (total - succeeded - failed).coerceAtLeast(0),
            )
        }
        // 잡 종류 식별자는 'render'/'separation' 고정 — 테이블명에서 파생.
        listOf(
            breakdown("render_jobs", "COMPLETED"),
            breakdown("separation_jobs", "READY"),
        )
    }

    /**
     * 대시보드 상단 KPI 카드 4종. 단일 쿼리 묶음.
     */
    fun getOverview(): AdminOverview = transaction {
        val sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 3600)
        AdminOverview(
            totalUsers = scalarLong("SELECT COUNT(*) FROM users"),
            totalRenders = scalarLong("SELECT COUNT(*) FROM render_jobs"),
            totalSeparations = scalarLong("SELECT COUNT(*) FROM separation_jobs"),
            totalSourceDurationMs = scalarLong("SELECT COALESCE(SUM(source_duration_ms), 0) FROM render_jobs"),
            activeUsersLast7Days = scalarLong(
                """
                    SELECT COUNT(DISTINCT user_id) FROM (
                        SELECT user_id FROM render_jobs WHERE created_at >= ?
                        UNION ALL
                        SELECT user_id FROM separation_jobs WHERE created_at >= ?
                    ) t
                """.trimIndent(),
                listOf(instantArg(sevenDaysAgo), instantArg(sevenDaysAgo)),
            ),
        )
    }
}
