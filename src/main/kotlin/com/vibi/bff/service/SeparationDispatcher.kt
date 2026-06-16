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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Perso audio-separation 동시성 큐의 dispatcher.
 *
 * 디자인 원칙:
 *   1. **인스턴스당 1 dispatcher**: SKIP LOCKED 가 race 차단하지만 정상 흐름에 contention 두지
 *      않기 위해 인스턴스당 단일 loop. Application.kt 가 single instance 만 만든다.
 *   2. **kick 우선, tick 안전망**: submit / 잡 완료 시 즉시 [nudge] 호출 — 평균 latency 0.
 *      kick 못 받았을 때 대비 tick 으로 폴링 — stuck 회수 / 재시작 직후 etc. tick 주기는
 *      in-flight 잡 유무에 따라 가변: 잡이 돌고 있으면 [tickIntervalMs] (짧게, reaper 가 stuck
 *      을 빨리 회수), 완전 idle 이면 [idleTickIntervalMs] (길게). idle tick 을 길게 두는 이유는
 *      Neon (managed Postgres) 가 수 분 idle 시 compute 를 suspend 해 과금이 멈추는데, 30초마다
 *      claim/reap 쿼리를 던지면 DB 가 영영 깨어있어 24/7 과금되기 때문. idle 일 때 새 submit 은
 *      어차피 [nudge] 로 dispatcher 를 즉시 깨우므로 (그리고 그 submit HTTP 핸들러가 이미 DB 를
 *      깨움) idle tick 은 순수 안전망이라 길어도 무방. trade-off: idle → 첫 요청 시 Neon
 *      cold start latency 가 한 번 붙는다 (요청 핸들러가 어차피 감수하는 비용).
 *   3. **claim 후 작업은 비동기**: tryClaimAndRun 이 service.runQueuedJob 을 launch 하고 즉시
 *      return. 다음 tryClaimAndRun 이 capacity 여유 있으면 또 claim — Perso 큐 (1 running +
 *      1 queued) 를 빈틈없이 채울 수 있다.
 *   4. **트랜잭션은 짧게**: claimNext / markProcessing / mark{Ready,Failed} 모두 SQL 1-2개.
 *      실제 Perso 호출 (분 단위) 은 service.executePipeline 에서 트랜잭션 밖.
 *
 * Cloud Run 운영 (min-instances=0 + --no-cpu-throttling): 인스턴스가 살아있는 동안은 CPU 가 항상
 * 할당돼 dispatcher 가 in-flight 잡을 끝까지 폴링한다. 완전 idle 이 길어져 scale-to-zero 로 죽으면
 * dispatcher 도 멈추지만, 그 시점엔 처리 중 잡이 없고, 새 submit 이 들어와 인스턴스를 깨우면 dispatcher
 * 가 다시 떠 QUEUED 를 claim 한다. 큐 즉시성이 꼭 필요하면 min-instances=1 로 인스턴스를 상주.
 */
class SeparationDispatcher(
    private val service: SeparationService,
    private val queue: SeparationQueueRepository,
    private val bffInstanceId: String,
    private val maxPersoInFlight: Int,
    parentScope: CoroutineScope? = null,
    /** in-flight 잡이 있을 때의 tick — reaper 가 stuck SUBMITTING/QUEUED 을 빨리 회수하도록 짧게. */
    private val tickIntervalMs: Long = 30_000,
    /** 완전 idle (in-flight 0) 일 때의 tick. **순수 missed-nudge 안전망**이라 매우 길어도 된다 —
     *  새 submit 은 [nudge] 가 즉시 깨우고(그 HTTP 핸들러가 이미 DB resume), capacity 로 막힌
     *  QUEUED 는 inFlight>0 이라 active tick 이 처리한다. idle 에서 reap 으로 회수할 잡은 사실상
     *  없다. 반면 매 idle tick fire 는 Neon 을 깨우고 그때마다 autosuspend 타이머(보통 ~5분)만큼
     *  compute 가 켜진 채 과금된다 → tick 이 잦을수록 (예: 15분 → 96회/일 × 5분 = 8h/일) 무료
     *  한도를 그대로 갉아먹는다. 그래서 기본 1시간(24회/일 × ~5분 ≈ 2h/일). 트래픽이 거의 없다면
     *  env 로 6h 등 더 길게 줄여도 안전. */
    private val idleTickIntervalMs: Long = TimeUnit.HOURS.toMillis(1),
    /** SUBMITTING 상태가 이 초 이상 지나면 stuck (인스턴스 사망) 으로 간주, QUEUED 로 복귀.
     *  주의: SUBMITTING 구간은 빠른 Perso submit 호출만이 아니라 그 앞의 **≤100MB 블롭 업로드
     *  + withTransientRetry(3회 × 3s)** 를 통째로 포함한다 (markProcessing 은 업로드+submit 이
     *  다 끝난 뒤 호출). 큰 파일 업로드는 60초를 쉽게 넘기므로 임계가 짧으면 reaper 가 멀쩡히
     *  업로드 중인 잡을 죽은 줄 알고 재큐잉 → 같은 인스턴스가 동시 2회 실행, 첫 실행의
     *  finally{sourceFile.delete()} 가 둘째 실행의 업로드 소스를 지워 FileNotFoundException.
     *  운영값은 SeparationConfig.stuckSubmittingSec(기본 300s) 로 주입. 본 기본값은 테스트용. */
    private val stuckSubmittingSec: Long = 300,
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
    // 현재 이 dispatcher 가 launch 해 돌고 있는 잡 수. 0 이면 idle tick (DB suspend 허용),
    // >0 이면 active tick (reaper 가 stuck 을 빨리 회수). runQueuedJob launch 직전 ++, finally --.
    private val inFlight = AtomicInteger(0)

    @OptIn(ExperimentalCoroutinesApi::class) // onTimeout(Duration) — 안정화 예정.
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            log.info(
                "SeparationDispatcher started: bffInstanceId={} maxPersoInFlight={} activeTickMs={} idleTickMs={}",
                bffInstanceId, maxPersoInFlight, tickIntervalMs, idleTickIntervalMs,
            )
            // 첫 tick: 인스턴스 시작 직후 stuck 회수 + reapStaleQueued 한 번 돌려 어수선한
            // 상태 정리. nudge 없이 곧장 시도.
            runCatching { reapPass() }
                .onFailure { log.warn("Initial reap pass failed: {}", it.message) }

            while (isActive) {
                runCatching { tryClaimAndRun() }
                    .onFailure { log.error("tryClaimAndRun crashed (continuing): {}", it.message, it) }

                // kick 또는 tick 둘 중 먼저 오는 것 기다리기. tick 마다 reap 도 같이.
                // in-flight 잡이 있으면 짧은 tick (stuck 빠른 회수), 완전 idle 이면 긴 tick
                // (Neon suspend 허용 — 새 submit 은 어차피 nudge 가 즉시 깨움).
                val tickMs = if (inFlight.get() > 0) tickIntervalMs else idleTickIntervalMs
                select<Unit> {
                    kick.onReceive { /* kick — 즉시 다음 사이클 */ }
                    onTimeout(tickMs.milliseconds) {
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
        // inFlight 증감으로 active/idle tick 을 가른다 (잡 도는 동안만 짧은 tick).
        inFlight.incrementAndGet()
        scope.launch {
            try {
                runCatching { service.runQueuedJob(jobId) }
                    .onFailure { log.error("runQueuedJob threw (unexpected — executePipeline should catch): jobId={}", jobId, it) }
            } finally {
                inFlight.decrementAndGet()
                // 잡 종료 → capacity/idle 전환을 loop 가 즉시 재평가하도록 깨움.
                nudge()
            }
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
