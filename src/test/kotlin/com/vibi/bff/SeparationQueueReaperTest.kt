package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.service.SeparationQueueRepository
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * reaper 가 인프라 사망(Cloud Run 인스턴스 교체)으로 FAILED 처리한 잡의 **jobId 를 반환**하는지
 * 회귀 가드. SeparationDispatcher.reapPass 가 이 목록으로 크레딧을 환불하므로(블로커: 무단 크레딧
 * 소실), 반환이 비면 환불도 안 일어난다. H2 PostgreSQL mode + Flyway 로 실제 큐 컬럼 경로 검증.
 *
 * reapStuckSubmitting 의 영구-FAILED 분기도 동일하게 failedJobIds 를 반환하지만, 그 입력(SUBMITTING
 * row)을 만들려면 claimNext 가 필요하고 claimNext 는 PostgreSQL `FOR UPDATE SKIP LOCKED` 라 H2
 * 미지원이라 여기선 stale-QUEUED 경로로 "id 반환 → 환불 가능" 계약을 대표 검증한다.
 */
class SeparationQueueReaperTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var queue: SeparationQueueRepository

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
        queue = SeparationQueueRepository()
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    @Test
    fun `reapStaleQueued returns failed jobIds for stale QUEUED rows`() = runBlocking {
        val jobId = "sep-${UUID.randomUUID()}"
        queue.enqueue(jobId, null, null, 1_000, "inst-1")

        // threshold 0 → cutoff=now, queuedAt(직전) < now 라 stale 로 잡힌다.
        val failed = queue.reapStaleQueued(staleThresholdSec = 0)

        assertEquals(listOf(jobId), failed, "stale QUEUED 잡의 jobId 가 반환되어야 환불이 가능")
        // 한 번 더 reap 하면 이미 FAILED 라 비어야 한다 (conditional UPDATE 가 이중 환불 트리거 방지).
        assertTrue(queue.reapStaleQueued(staleThresholdSec = 0).isEmpty())
    }
}
