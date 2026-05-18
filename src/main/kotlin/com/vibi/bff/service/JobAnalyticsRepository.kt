package com.vibi.bff.service

import com.vibi.bff.db.RenderJobsTable
import com.vibi.bff.db.SeparationJobsTable
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * render_jobs / separation_jobs Postgres write 만 담당. RenderService / SeparationService
 * 가 status 변화 시점에 호출 — admin 대시보드의 source-of-truth.
 *
 * **Non-fatal**: 모든 메서드가 try/catch 로 감싸 transaction 실패가 ffmpeg 파이프라인을
 * 깨뜨리지 않도록 한다. 분석 데이터는 보조 정보 — DB 가 잠시 끊겨도 user-facing 동작은 계속.
 *
 * 모든 write 는 [Dispatchers.IO] 로 wrap — Ktor request coroutine 이 Netty 이벤트 루프에서
 * 호출해도 JDBC 가 루프를 막지 않게.
 */
class JobAnalyticsRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun insertRenderJob(
        jobId: String,
        userId: UUID,
        sourceDurationMs: Long,
        status: String,
    ) = safeWrite("insertRenderJob jobId=$jobId") {
        RenderJobsTable.insert {
            it[RenderJobsTable.id] = jobId
            it[RenderJobsTable.userId] = userId
            it[RenderJobsTable.sourceDurationMs] = sourceDurationMs
            it[RenderJobsTable.status] = status
            it[RenderJobsTable.createdAt] = Instant.now()
        }
    }

    suspend fun updateRenderJobStatus(jobId: String, status: String) =
        safeWrite("updateRenderJobStatus jobId=$jobId status=$status") {
            val terminal = status == "COMPLETED" || status == "FAILED"
            val now = Instant.now()
            RenderJobsTable.update({ RenderJobsTable.id eq jobId }) {
                it[RenderJobsTable.status] = status
                if (terminal) it[RenderJobsTable.finishedAt] = now
            }
        }

    suspend fun insertSeparationJob(
        jobId: String,
        userId: UUID,
        renderJobId: String?,
        sourceDurationMs: Long,
        status: String,
    ) = safeWrite("insertSeparationJob jobId=$jobId") {
        SeparationJobsTable.insert {
            it[SeparationJobsTable.id] = jobId
            it[SeparationJobsTable.userId] = userId
            it[SeparationJobsTable.renderJobId] = renderJobId
            it[SeparationJobsTable.sourceDurationMs] = sourceDurationMs
            it[SeparationJobsTable.status] = status
            it[SeparationJobsTable.createdAt] = Instant.now()
        }
    }

    suspend fun updateSeparationJobStatus(jobId: String, status: String) =
        safeWrite("updateSeparationJobStatus jobId=$jobId status=$status") {
            val terminal = status == "READY" || status == "FAILED"
            val now = Instant.now()
            SeparationJobsTable.update({ SeparationJobsTable.id eq jobId }) {
                it[SeparationJobsTable.status] = status
                if (terminal) it[SeparationJobsTable.finishedAt] = now
            }
        }

    private suspend inline fun safeWrite(label: String, crossinline block: () -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                transaction { block() }
            }
        } catch (e: Exception) {
            log.warn("Analytics write failed: {} ({}: {})", label, e.javaClass.simpleName, e.message)
        }
    }
}
