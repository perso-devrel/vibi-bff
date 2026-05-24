package com.vibi.bff.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Perso audio-separation 동시성 큐의 dispatcher.
 *
 * 디자인 원칙:
 *   1. **인스턴스당 1 dispatcher**: SKIP LOCKED 가 race 차단하지만 정상 흐름에 contention 두지
 *      않기 위해 인스턴스당 단일 loop. Application.kt 가 single instance 만 만든다.
 *   2. **kick 우선, tick 안전망**: submit / 잡 완료 시 즉시 [nudge] 호출 — 평균 latency 0.
 *      kick 못 받았을 때 대비 30초 tick 으로 폴링 — stuck 회수 / 재시작 직후 etc.
 *   3. **claim 후 작업은 비동기**: tryClaimAndRun 이 service.runQueuedJob 을 launch 하고 즉시
 *      return. 다음 tryClaimAndRun 이 capacity 여유 있으면 또 claim — Perso 큐 (1 running +
 *      1 queued) 를 빈틈없이 채울 수 있다.
 *   4. **트랜잭션은 짧게**: claimNext / markProcessing / mark{Ready,Failed} 모두 SQL 1-2개.
 *      실제 Perso 호출 (분 단위) 은 service.executePipeline 에서 트랜잭션 밖.
 *
 * Cloud Run min-instances=0 일 때 trade-off: 인스턴스가 idle 로 잠들면 dispatcher 도 죽음.
 * 그 사이 새 submit 이 들어와도 QUEUED 만 쌓이고 처리 안 됨. min-instances=1 로 운영하거나
 * (권장) 새 submit 이 들어올 때만 처리되는 한계를 받아들여야 함.
 */
class SeparationDispatcher(
    private val service: SeparationService,
    private val queue: SeparationQueueRepository,
    private val bffInstanceId: String,
    private val maxPersoInFlight: Int,
    parentScope: CoroutineScope? = null,
    private val tickIntervalMs: Long = 30_000,
    /** SUBMITTING 상태가 이 초 이상 지나면 stuck 으로 간주, QUEUED 로 복귀. 정상 Perso 호출은
     *  10-30초 내 끝나므로 60초면 stuck 으로 분류해도 false positive 거의 없음. */
    private val stuckSubmittingSec: Long = 60,
    /** QUEUED 상태가 이 초 이상 지나면 (보통 소스 파일 소유 인스턴스가 죽은 경우) FAILED. 30분
     *  은 모바일이 합리적으로 재시도 / 사용자 인내 한도를 모두 넘기는 보수적 threshold. */
    private val staleQueuedSec: Long = TimeUnit.MINUTES.toSeconds(30),
    /** claim 이 attempt_count 를 증가시킨 후 SUBMITTING reaper 가 본 횟수 이상 본 잡은 영구
     *  FAILED. 3 = "Perso 가 받지도 못한 채 인스턴스 죽음" 이 3번이면 사용자한테 실패 알림 합리. */
    private val maxAttempts: Int = 3,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // CONFLATED: 여러 nudge 가 빠르게 와도 1개로 합쳐짐 — dispatcher 가 한 사이클에 여러 잡
    // claim 하면 충분하므로 별도 누적 의미 없음. UNLIMITED 라면 kick 메시지가 끝없이 쌓일 수 있음.
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val scope: CoroutineScope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsScope: Boolean = parentScope == null
    private var loop: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout(Duration) — 안정화 예정.
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            log.info(
                "SeparationDispatcher started: bffInstanceId={} maxPersoInFlight={} tickMs={}",
                bffInstanceId, maxPersoInFlight, tickIntervalMs,
            )
            // 첫 tick: 인스턴스 시작 직후 stuck 회수 + reapStaleQueued 한 번 돌려 어수선한
            // 상태 정리. nudge 없이 곧장 시도.
            runCatching { reapPass() }
                .onFailure { log.warn("Initial reap pass failed: {}", it.message) }

            while (isActive) {
                runCatching { tryClaimAndRun() }
                    .onFailure { log.error("tryClaimAndRun crashed (continuing): {}", it.message, it) }

                // kick 또는 tick 둘 중 먼저 오는 것 기다리기. tick 마다 reap 도 같이.
                select<Unit> {
                    kick.onReceive { /* kick — 즉시 다음 사이클 */ }
                    onTimeout(tickIntervalMs.milliseconds) {
                        runCatching { reapPass() }
                            .onFailure { log.warn("Reap pass failed: {}", it.message) }
                    }
                }
            }
        }
    }

    /** submit / 완료 시 호출. dispatcher 가 sleep 중이면 깨워서 다음 claim 시도. */
    fun nudge() {
        kick.trySend(Unit) // CONFLATED 라 항상 success 또는 conflate — 무시 안전.
    }

    fun shutdown() {
        kick.close()
        loop?.cancel()
        if (ownsScope) scope.cancel()
    }

    private suspend fun tryClaimAndRun() {
        val jobId = queue.claimNext(bffInstanceId, maxPersoInFlight) ?: return
        log.info("Dispatcher claimed jobId={}", jobId)
        // service.runQueuedJob 은 분 단위 — launch 로 떼어내야 다음 claim 시도가 capacity 풀릴
        // 때까지 막히지 않음. 본 loop 는 다음 iteration 에서 capacity 보고 또 claim 가능.
        scope.launch {
            runCatching { service.runQueuedJob(jobId) }
                .onFailure { log.error("runQueuedJob threw (unexpected — executePipeline should catch): jobId={}", jobId, it) }
        }
        // 한 사이클 안에서 capacity 가 더 남았으면 곧장 또 시도. select 의 timeout/kick 으로
        // sleep 들어가기 전에 큐 비울 수 있게.
        nudge()
    }

    private suspend fun reapPass() {
        queue.reapStuckSubmitting(stuckSubmittingSec, maxAttempts)
        queue.reapStaleQueued(staleQueuedSec)
    }
}
