package com.vibi.bff.service

import com.vibi.bff.db.SeparationJobsTable
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import org.slf4j.LoggerFactory

/**
 * separation_jobs 의 큐 컬럼을 read/write — Perso 동시성 제어의 source-of-truth.
 *
 * [JobAnalyticsRepository] 와 분리한 이유:
 *   - Analytics 는 best-effort write (실패해도 사용자 경험 영향 없음 → safeWrite 로 swallow)
 *   - Queue 는 load-bearing — write 실패 시 dispatcher 가 인지하고 다음 tick 에 재시도해야 함.
 *     실패 swallow 하면 SUBMITTING stuck / PROCESSING 누락 / capacity 카운트 오류가 silent.
 *
 * Dispatcher 가 호출하는 메서드는 모두 짧은 트랜잭션 (SQL 1-2개) — 실제 Perso 호출은 트랜잭션
 * 밖. 트랜잭션이 길어지면 SKIP LOCKED 의 fairness 가 깨지고 connection pool 점유가 늘어 다른
 * BFF request 까지 지연.
 */
class SeparationQueueRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * QUEUED row 를 새로 만든다. 클라이언트 응답 직전에 호출 — 이후 dispatcher 가 [claimNext]
     * 로 자기 인스턴스 row 만 골라 처리.
     *
     * @param userId 분석 row 의 user_id (V5 의 SET NULL 정책과 동기). 미인증 잡이면 null —
     *   현재는 NULL 허용 (테스트/dev 분기) 이지만 운영에선 항상 set 기대.
     */
    suspend fun enqueue(
        jobId: String,
        userId: UUID?,
        renderJobId: String?,
        sourceDurationMs: Long,
        bffInstanceId: String,
    ) = newSuspendedTransaction(Dispatchers.IO) {
        val now = Instant.now()
        SeparationJobsTable.insert {
            it[SeparationJobsTable.id] = jobId
            it[SeparationJobsTable.userId] = userId
            it[SeparationJobsTable.renderJobId] = renderJobId
            it[SeparationJobsTable.sourceDurationMs] = sourceDurationMs
            it[SeparationJobsTable.status] = STATUS_QUEUED
            it[SeparationJobsTable.createdAt] = now
            it[SeparationJobsTable.queuedAt] = now
            it[SeparationJobsTable.bffInstanceId] = bffInstanceId
            it[SeparationJobsTable.attemptCount] = 0
        }
        Unit
    }

    /**
     * 자기 인스턴스가 enqueue 한 가장 오래된 QUEUED row 한 줄을 SUBMITTING 으로 전이하며
     * jobId 반환. 단, 전체 BFF의 (SUBMITTING + PROCESSING) 개수가 [maxPersoInFlight] 이상이면
     * Perso 큐 포화 — null 반환 (claim skip).
     *
     * 한 트랜잭션 안에서 capacity check + SKIP LOCKED claim → 두 dispatcher 가 동시에 capacity
     * 를 같이 보면서 둘 다 claim 하는 race 차단.
     *
     * SKIP LOCKED 는 본 호출이 다른 트랜잭션이 lock 잡은 row 를 건너뛰게 함 — 운영상 dispatcher
     * 가 인스턴스당 1개라 정상 흐름엔 contention 없지만, stale reaper 가 같은 row 를 손보는
     * edge 케이스 안전망.
     *
     * **bff_instance_id 매칭이 핵심**: 다른 인스턴스가 enqueue 한 QUEUED row 의 소스 파일은
     * 그 인스턴스 로컬 /tmp 에 있으므로 self 가 처리 못 함. enqueue 한 인스턴스 본인만 claim.
     */
    suspend fun claimNext(
        bffInstanceId: String,
        maxPersoInFlight: Int,
    ): String? = newSuspendedTransaction(Dispatchers.IO) {
        val inFlight = SeparationJobsTable
            .selectAll()
            .where { SeparationJobsTable.status inList listOf(STATUS_SUBMITTING, STATUS_PROCESSING) }
            .count()
        if (inFlight >= maxPersoInFlight) return@newSuspendedTransaction null

        val row = SeparationJobsTable
            .selectAll()
            .where {
                (SeparationJobsTable.status eq STATUS_QUEUED) and
                    (SeparationJobsTable.bffInstanceId eq bffInstanceId)
            }
            .orderBy(SeparationJobsTable.queuedAt)
            .limit(1)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .firstOrNull() ?: return@newSuspendedTransaction null

        val claimedId = row[SeparationJobsTable.id]
        SeparationJobsTable.update({ SeparationJobsTable.id eq claimedId }) {
            it[SeparationJobsTable.status] = STATUS_SUBMITTING
            it[SeparationJobsTable.dispatchedAt] = Instant.now()
            with(SqlExpressionBuilder) {
                it[SeparationJobsTable.attemptCount] = SeparationJobsTable.attemptCount + 1
            }
        }
        claimedId
    }

    /** SUBMITTING → PROCESSING. [persoProjectSeq] 는 Perso 가 발급한 projectSeq — 인스턴스 재시작
     *  시 polling resumption 의 entry point. */
    suspend fun markProcessing(jobId: String, persoProjectSeq: Long) {
        newSuspendedTransaction(Dispatchers.IO) {
            SeparationJobsTable.update({ SeparationJobsTable.id eq jobId }) {
                it[SeparationJobsTable.status] = STATUS_PROCESSING
                it[SeparationJobsTable.persoProjectSeq] = persoProjectSeq
            }
        }
    }

    /**
     * 완료 마킹과 함께 stem 메타를 한 UPDATE 로 persist — durability 보장. [stemsJson] 은
     * JSON 배열 (`[{stemId, label, ext}, ...]`), [actualDurationMs] 는 speaker stem 길이.
     * 두 값 모두 새 인스턴스가 GET 응답을 재구축할 때 필요 — caller (SeparationService) 가
     * R2 업로드를 본 호출 **전에** 끝낸 상태여야 안전.
     *
     * 둘 다 NULL 허용 (V7 마이그레이션 이전 row 호환 + 테스트 분기) — null 이면 단순 status
     * 전이만.
     */
    suspend fun markReady(
        jobId: String,
        stemsJson: String? = null,
        actualDurationMs: Long? = null,
    ) {
        newSuspendedTransaction(Dispatchers.IO) {
            SeparationJobsTable.update({ SeparationJobsTable.id eq jobId }) {
                it[SeparationJobsTable.status] = STATUS_READY
                it[SeparationJobsTable.finishedAt] = Instant.now()
                if (stemsJson != null) it[SeparationJobsTable.stemsJson] = stemsJson
                if (actualDurationMs != null) it[SeparationJobsTable.actualDurationMs] = actualDurationMs
            }
        }
    }

    /**
     * 새 인스턴스의 in-memory 재구축용 — status=READY + stems_json 이 모두 살아있는 row 만 반환.
     * SeparationService.getJob 의 in-memory miss 분기에서 호출.
     *
     * stems_json 이 NULL 이면 V7 이전 잡 (legacy) — 재구축 못 함, null 반환. 호출자는 그대로
     * not-found 처리해 사용자한테 재요청 안내. owner_user_id 가 null 인 미인증 잡도 함께 반환되어
     * 호출자가 IDOR 검증 (route 단의 ownerUserId 매칭) 을 적용.
     */
    suspend fun loadReady(jobId: String): ReadyJobRow? = newSuspendedTransaction(Dispatchers.IO) {
        val row = SeparationJobsTable
            .selectAll()
            .where { SeparationJobsTable.id eq jobId }
            .firstOrNull() ?: return@newSuspendedTransaction null
        if (row[SeparationJobsTable.status] != STATUS_READY) return@newSuspendedTransaction null
        val json = row[SeparationJobsTable.stemsJson] ?: return@newSuspendedTransaction null
        ReadyJobRow(
            jobId = jobId,
            stemsJson = json,
            actualDurationMs = row[SeparationJobsTable.actualDurationMs],
            ownerUserId = row[SeparationJobsTable.userId],
        )
    }

    suspend fun markFailed(jobId: String) {
        newSuspendedTransaction(Dispatchers.IO) {
            SeparationJobsTable.update({ SeparationJobsTable.id eq jobId }) {
                it[SeparationJobsTable.status] = STATUS_FAILED
                it[SeparationJobsTable.finishedAt] = Instant.now()
            }
        }
    }

    /**
     * SUBMITTING 인데 [stuckThresholdSec] 초 이상 dispatched_at 이 지난 row 들을 QUEUED 로 복귀.
     * 시나리오: dispatcher 가 Perso 호출 도중 인스턴스 죽음 → 영원히 SUBMITTING 으로 stuck.
     *
     * attempt_count 는 claimNext 가 이미 증가시켰으므로 여기선 건드리지 않음. attempt_count 가
     * [maxAttempts] 도달하면 FAILED 로 직행 (영구 실패).
     *
     * 반환값: (recoveredCount, permanentlyFailedCount). 로깅 / 알람용.
     */
    suspend fun reapStuckSubmitting(
        stuckThresholdSec: Long,
        maxAttempts: Int,
    ): Pair<Int, Int> = newSuspendedTransaction(Dispatchers.IO) {
        val cutoff = Instant.now().minusSeconds(stuckThresholdSec)
        val stuckRows = SeparationJobsTable
            .selectAll()
            .where {
                (SeparationJobsTable.status eq STATUS_SUBMITTING) and
                    (SeparationJobsTable.dispatchedAt less cutoff)
            }
            .toList()

        var recovered = 0
        var failed = 0
        stuckRows.forEach { row ->
            val id = row[SeparationJobsTable.id]
            val attempts = row[SeparationJobsTable.attemptCount]
            if (attempts >= maxAttempts) {
                SeparationJobsTable.update({ SeparationJobsTable.id eq id }) {
                    it[SeparationJobsTable.status] = STATUS_FAILED
                    it[SeparationJobsTable.finishedAt] = Instant.now()
                }
                failed += 1
            } else {
                SeparationJobsTable.update({ SeparationJobsTable.id eq id }) {
                    it[SeparationJobsTable.status] = STATUS_QUEUED
                }
                recovered += 1
            }
        }
        if (recovered > 0 || failed > 0) {
            log.warn("Reaped stuck SUBMITTING: recovered={} permanentlyFailed={}", recovered, failed)
        }
        recovered to failed
    }

    /**
     * QUEUED 인데 [staleThresholdSec] 초 이상 queued_at 이 지난 row 들을 FAILED 마킹.
     * 시나리오: enqueue 한 인스턴스가 dispatch 전에 죽음 → 소스 파일이 손실된 상태로 QUEUED 가
     * 영원히 남음. 다른 인스턴스가 이어받지 못하므로 (bff_instance_id 매칭 정책) 영구 실패 처리.
     */
    suspend fun reapStaleQueued(staleThresholdSec: Long): Int = newSuspendedTransaction(Dispatchers.IO) {
        val cutoff = Instant.now().minusSeconds(staleThresholdSec)
        val updated = SeparationJobsTable.update({
            (SeparationJobsTable.status eq STATUS_QUEUED) and
                (SeparationJobsTable.queuedAt less cutoff)
        }) {
            it[SeparationJobsTable.status] = STATUS_FAILED
            it[SeparationJobsTable.finishedAt] = Instant.now()
        }
        if (updated > 0) log.warn("Reaped stale QUEUED rows: {}", updated)
        updated
    }

    /**
     * 다른 인스턴스 소유의 PROCESSING row 들을 self 로 재할당해서 가져옴 — Cloud Run 인스턴스
     * 교체 / 크래시 후 새 인스턴스가 boot 직후 호출. Perso 잡은 server-side 에서 계속 돌고
     * 있으므로 새 인스턴스가 polling 만 재개하면 사용자한테 invisible 하게 복구됨.
     *
     * 다른 인스턴스가 동시에 같은 row 를 잡으려 하면 SKIP LOCKED 로 한 인스턴스만 winner.
     * persoProjectSeq 가 null 인 row 는 polling resume 불가 (Perso 잡 자체가 없음) → 건너뜀.
     */
    suspend fun claimOrphanedProcessing(bffInstanceId: String): List<ResumableJob> =
        newSuspendedTransaction(Dispatchers.IO) {
            val orphans = SeparationJobsTable
                .selectAll()
                .where {
                    (SeparationJobsTable.status eq STATUS_PROCESSING) and
                        SeparationJobsTable.persoProjectSeq.isNotNull() and
                        (SeparationJobsTable.bffInstanceId neq bffInstanceId)
                }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .toList()

            orphans.map { row ->
                val id = row[SeparationJobsTable.id]
                SeparationJobsTable.update({ SeparationJobsTable.id eq id }) {
                    it[SeparationJobsTable.bffInstanceId] = bffInstanceId
                }
                ResumableJob(
                    jobId = id,
                    persoProjectSeq = row[SeparationJobsTable.persoProjectSeq]!!,
                    ownerUserId = row[SeparationJobsTable.userId],
                )
            }.also {
                if (it.isNotEmpty()) {
                    log.warn("Claimed {} orphaned PROCESSING jobs for resumption", it.size)
                }
            }
        }

    /**
     * 클라이언트 표시용 큐 위치 — 자기보다 먼저 큐에 들어간 QUEUED row 의 개수 + 1.
     * BFF 인스턴스 가로지름 (전체 큐 순서 기준). 잡이 이미 SUBMITTING/PROCESSING/READY 면 null.
     */
    suspend fun queuePosition(jobId: String): Int? = newSuspendedTransaction(Dispatchers.IO) {
        val row = SeparationJobsTable
            .selectAll()
            .where { SeparationJobsTable.id eq jobId }
            .firstOrNull() ?: return@newSuspendedTransaction null
        if (row[SeparationJobsTable.status] != STATUS_QUEUED) return@newSuspendedTransaction null
        val myQueuedAt = row[SeparationJobsTable.queuedAt] ?: return@newSuspendedTransaction null
        val aheadCount = SeparationJobsTable
            .selectAll()
            .where {
                (SeparationJobsTable.status eq STATUS_QUEUED) and
                    (SeparationJobsTable.queuedAt less myQueuedAt)
            }
            .count()
        (aheadCount + 1).toInt()
    }

    /** 최근 [sampleSize] 개 READY 잡의 평균 (finished_at - dispatched_at) 초. 모집단 0 이면 null. */
    suspend fun rollingAvgProcessingSec(sampleSize: Int = 50): Long? = newSuspendedTransaction(Dispatchers.IO) {
        val recent = SeparationJobsTable
            .selectAll()
            .where {
                (SeparationJobsTable.status eq STATUS_READY) and
                    SeparationJobsTable.dispatchedAt.isNotNull() and
                    SeparationJobsTable.finishedAt.isNotNull()
            }
            .orderBy(SeparationJobsTable.finishedAt, SortOrder.DESC)
            .limit(sampleSize)
            .toList()
        if (recent.isEmpty()) return@newSuspendedTransaction null
        val sumSec = recent.sumOf { row ->
            val started = row[SeparationJobsTable.dispatchedAt]!!
            val finished = row[SeparationJobsTable.finishedAt]!!
            finished.epochSecond - started.epochSecond
        }
        sumSec / recent.size
    }

    /** 관측용 — 현재 큐 깊이 (QUEUED 만). 알람 임계치 비교 / 로그용. */
    suspend fun queueDepth(): Long = newSuspendedTransaction(Dispatchers.IO) {
        SeparationJobsTable
            .selectAll()
            .where { SeparationJobsTable.status eq STATUS_QUEUED }
            .count()
    }

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SUBMITTING = "SUBMITTING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_READY = "READY"
        const val STATUS_FAILED = "FAILED"
    }
}

/** boot resumption 대상. SeparationService.resumePollingForJob 의 입력. */
data class ResumableJob(
    val jobId: String,
    val persoProjectSeq: Long,
    val ownerUserId: java.util.UUID?,
)

/** [SeparationQueueRepository.loadReady] 반환. 새 인스턴스의 in-memory 재구축용 ─
 *  stemsJson 은 JSON 배열 (`[{stemId, label, ext}, ...]`); R2 object key 는 ObjectKey
 *  .separationStem 으로 계산. */
data class ReadyJobRow(
    val jobId: String,
    val stemsJson: String,
    val actualDurationMs: Long?,
    val ownerUserId: java.util.UUID?,
)
