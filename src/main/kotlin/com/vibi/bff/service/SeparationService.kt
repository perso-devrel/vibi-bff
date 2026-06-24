package com.vibi.bff.service

import com.vibi.bff.FAILED_JOB_TTL_MS
import com.vibi.bff.config.SeparationConfig
import com.vibi.bff.model.PersoDownloadInfo
import com.vibi.bff.model.PersoProjectInfo
import com.vibi.bff.model.SeparationSpec
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.PersoApiException
import com.vibi.bff.routes.ObjectKey
import com.vibi.bff.routes.contentTypeForExtension
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// State machine (in-memory job.status):
//   QUEUED → UPLOADING_UPSTREAM → SUBMITTED → PROCESSING → DOWNLOADING → READY
//                                                              │
//                                                              └→ FAILED
//
// QUEUED 단계는 SeparationDispatcher 가 Perso 동시성 cap (env: MAX_PERSO_IN_FLIGHT) 까지
// 도달하기 전엔 기다리는 큐 상태. dispatcher 가 claim 하면 코루틴이 launch 되어 그 다음 단계로
// 진행. DB (separation_jobs.status) 는 보다 거친 단계만 추적 (QUEUED/SUBMITTING/PROCESSING/
// READY/FAILED) — 인메모리 fine-grained 상태가 사용자 progress UI source, DB 는 dispatcher 의
// capacity 카운트 source.
//
// READY 잡은 abandonTtlMs 가 지나면 cleanupAbandoned 가 dispose 로 회수. FAILED 도
// FAILED_JOB_TTL_MS 짧은 grace 후 회수. 모바일이 stem URL 들을 받아 로컬 mix 하므로
// 서버 측 mix 잡 / reservation 상태머신은 없음 — stem 들은 클라가 다 받은 뒤 abandon TTL
// 으로만 reap.

/** speaker stem id = "$SPEAKER_STEM_PREFIX$idx" (idx 0-based). 모바일 Stem.SPEAKER_PREFIX 와 일치. */
internal const val SPEAKER_STEM_PREFIX = "speaker_"

data class SeparationJob(
    val jobId: String,
    @Volatile var status: String = "QUEUED",
    @Volatile var progress: Int = 0,
    @Volatile var progressReason: String? = null,
    @Volatile var error: String? = null,
    val outputDir: File,
    @Volatile var stems: List<LocalStem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** READY 시 ffprobe 로 잰 stem FLAC 의 실측 길이 (ms). 클라이언트가 timeline 막대 길이를
     * 사용자 선택값 대신 이 값으로 보정해 stem 파일과 1:1 매칭. null = 측정 실패 또는 미-READY. */
    @Volatile var actualDurationMs: Long? = null,
    /** submit 의 principal.userId 가 그대로 보존. null 이면 미인증 잡 (테스트 / jwtSecret
     * 미주입 분기). 라우트가 status 호출 시 caller principal 과 매칭 — mismatch 는 IDOR
     * existence oracle 차단을 위해 not-found 로 응답. */
    val ownerUserId: UUID? = null,
    // ── Dispatcher 가 QUEUED row 를 pickup 한 뒤 runPipeline 에 넘기는 입력 ────────────
    // submit() 가 받은 그대로 보존. claim 시점에 dispatcher 가 in-memory lookup 으로 꺼냄.
    val sourceFile: File,
    val spec: SeparationSpec,
    val sourceDurationMs: Long = 0L,
    val renderJobId: String? = null,
)

data class LocalStem(
    val stemId: String,
    val label: String,
    val file: File,
)

/**
 * separation_jobs.stems_json 의 element schema. R2 object key 는
 * [ObjectKey.separationStem]`(jobId, stemId, ext)` 로 계산되므로 별도 컬럼 / 필드 불필요.
 * Forward-compatible: stem 개수가 가변 (1 ~ N 화자 + voice_all + background) + 향후 메타 (예:
 * peakDb, sampleRateHz) 가 늘어도 컬럼 마이그레이션 없이 JSON 필드만 추가하면 됨.
 */
@Serializable
internal data class StemMeta(
    val stemId: String,
    val label: String,
    val ext: String,
)

class SeparationService(
    private val persoClient: PersoClient,
    private val separationDir: File,
    private val config: SeparationConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val pollIntervalMs: Long,
    private val maxPollMinutes: Int,
    /** Perso 동시성 큐의 source-of-truth. null 이면 큐 write skip — 테스트/dev 분기에서만
     *  허용 (DB 없이 in-memory 만으로 동작). 운영에선 항상 주입. */
    private val queue: SeparationQueueRepository? = null,
    /** Perso API 호출 instrumentation. null 이면 외부 호출 추적 skip. */
    private val externalCalls: ExternalApiCallsRepository? = null,
    /** Dispatcher 의 깨우기 신호. submit (새 QUEUED) / 완료 (capacity 해제) 시 호출.
     *  null 이면 dispatcher 가 자기 30초 tick 으로만 깨어남 — 테스트 분기. */
    private val onJobChange: (() -> Unit)? = null,
    /** Cloud Run 인스턴스 단위 UUID — bff_instance_id 컬럼에 기록되어 dispatcher 가 자기
     *  인스턴스 QUEUED 만 claim 하게 함. 소스 파일이 인스턴스 로컬 디스크에 있기 때문. */
    private val bffInstanceId: String = UUID.randomUUID().toString(),
    /** R2 object store. **READY 마킹 직전 stem 들을 eager upload** 해 인스턴스 사망 시 데이터
     *  손실 차단. null 이면 eager upload skip — 로컬 dev / R2 미사용 분기에서만 허용 (인스턴스
     *  churn 손실 위험 감수). */
    private val objectStore: ObjectStore? = null,
    /** 잡 FAILED 진입 시 호출 — 라우트가 선차감한 크레딧을 환불하는 hook. 인자는 jobId.
     *  CreditRepository.refund 가 (platform='consume', txId='consume-<jobId>') 를 찾아
     *  매칭 시 환불 + (platform='refund', txId='refund-<jobId>') row 기록. 멱등 (이중 환불
     *  차단) 이므로 enqueue 실패 / pipeline catch / dispatcher orphan 어디서 불러도 안전.
     *  null 이면 환불 skip — 테스트 / dev 분기. */
    private val onJobFailed: (suspend (String) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, SeparationJob>()
    /** 현재 이 인스턴스에서 파이프라인이 돌고 있는 jobId 집합 — 동일 잡 동시실행 가드.
     *  reaper 가 업로드 도중 SUBMITTING 잡을 stuck 으로 오판해 QUEUED 로 되돌리면 dispatcher 가
     *  같은 잡을 재claim 하는데, 첫 실행이 아직 살아있으면 둘째 실행이 같은 sourceFile 을 두고
     *  경합 → 첫 실행의 finally{delete} 가 둘째의 업로드 소스를 지워 FileNotFoundException.
     *  add() 가 false (이미 in-flight) 면 둘째 진입을 즉시 버려 차단. 진짜 인스턴스 사망 시엔
     *  이 집합도 JVM 과 함께 사라지므로 새 인스턴스의 정상 재시도는 막지 않는다. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "separation-cleanup").apply { isDaemon = true }
    }

    init {
        separationDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupAbandoned, 10, 10, TimeUnit.MINUTES)
    }

    fun shutdown() {
        cleanup.shutdown()
        if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) cleanup.shutdownNow()
    }

    /**
     * In-memory hit 우선, miss 면 DB 에서 status=READY row 를 가져와 in-memory 재구축.
     *
     * 시나리오: 이전 인스턴스가 RUN/READY 직전에 죽음 → 새 인스턴스 boot resumption 은
     * PROCESSING 만 다루므로 READY 상태 잡은 in-memory 에 없음. 본 메서드가 DB 에서 READY 잡을
     * 가져와 재구축 — stems FLAC 은 R2 가 source-of-truth (V7 마이그레이션 이후 잡은 READY 마킹
     * 직전에 eager upload 됨).
     *
     * 재구축된 SeparationJob 의 [SeparationJob.sourceFile] / [SeparationJob.spec] /
     * [LocalStem.file] 은 placeholder — caller 는 mix/download 만 가능 (재처리 불가).
     * stems_json 이 NULL 인 V7 이전 잡은 재구축 불가능 — null 반환 (사용자 재요청 안내).
     */
    suspend fun getJob(jobId: String): SeparationJob? {
        jobs[jobId]?.let { return it }
        val ready = queue?.loadReady(jobId) ?: return null
        return rebuildReadyJob(ready)
    }

    /**
     * In-memory 전용 lookup — DB fallback 없음. dispatcher / mix 예약 등 본 인스턴스가 소유 중인
     * 잡만 다루는 분기에서 사용 (재구축된 잡은 source/spec 이 placeholder 라 쓸모없음).
     */
    private fun getInMemoryJob(jobId: String): SeparationJob? = jobs[jobId]

    /**
     * [SeparationQueueRepository.loadReady] 결과로 in-memory SeparationJob 재구축. putIfAbsent
     * 로 race-safe — 동시에 두 caller 가 같은 잡을 재구축 시도해도 한 쪽만 win.
     */
    private fun rebuildReadyJob(ready: ReadyJobRow): SeparationJob {
        val outputDir = File(separationDir, ready.jobId).apply { mkdirs() }
        val metas = AppJson.decodeFromString(ListSerializer(StemMeta.serializer()), ready.stemsJson)
        val stemsList = metas.map { meta ->
            // placeholder file — 실체는 R2. /stem/{id} 경로의 respondDownload 가 ObjectStore
            // HEAD 로 R2 hit 확인 후 signed URL 302. file.exists()=false 라도 정상 동작.
            LocalStem(meta.stemId, meta.label, File(outputDir, "${meta.stemId}.${meta.ext}"))
        }
        val rebuilt = SeparationJob(
            jobId = ready.jobId,
            outputDir = outputDir,
            ownerUserId = ready.ownerUserId,
            sourceFile = File(outputDir, ".rebuilt-no-source"),
            spec = SeparationSpec(),
        ).apply {
            status = "READY"
            progress = 100
            progressReason = "Completed"
            stems = stemsList
            actualDurationMs = ready.actualDurationMs
        }
        val existing = jobs.putIfAbsent(ready.jobId, rebuilt)
        return existing ?: rebuilt
    }

    /**
     * 새 분리 잡을 등록하고 비동기 파이프라인을 시작. 반환은 jobId. 라우트의 크레딧 선차감
     * 흐름은 [providedJobId] 로 미리 생성한 ID 를 차감 ledger key 로 사용 — null 이면 내부에서
     * "sep-<UUID>" 생성.
     *
     * [userId] / [sourceDurationMs] / [renderJobId] 는 admin 대시보드 분석 row 용. userId == null
     * 이면 분석 write skip (테스트 등).
     */
    fun submit(
        sourceFile: File,
        spec: SeparationSpec,
        userId: UUID? = null,
        sourceDurationMs: Long = 0L,
        renderJobId: String? = null,
        providedJobId: String? = null,
    ): String {
        val jobId = providedJobId ?: "sep-${UUID.randomUUID()}"
        val outputDir = File(separationDir, jobId).apply { mkdirs() }
        val job = SeparationJob(
            jobId = jobId,
            outputDir = outputDir,
            ownerUserId = userId,
            sourceFile = sourceFile,
            spec = spec,
            sourceDurationMs = sourceDurationMs,
            renderJobId = renderJobId,
        )
        jobs[jobId] = job

        if (queue != null) {
            // 운영 경로: DB enqueue → dispatcher 가 capacity 보고 자기 인스턴스 row claim →
            // runQueuedJob 호출. 본 호출은 그 자리에서 DB INSERT 만 — Perso 호출은 dispatcher 가.
            scope.launch {
                runCatching {
                    queue.enqueue(
                        jobId = jobId,
                        userId = userId,
                        renderJobId = renderJobId,
                        sourceDurationMs = sourceDurationMs,
                        bffInstanceId = bffInstanceId,
                        projectId = spec.projectId,
                        fileName = spec.fileName,
                        byteLength = spec.byteLength,
                    )
                }.onFailure { e ->
                    // enqueue 실패면 dispatcher 가 영원히 모름 → in-memory 도 FAILED 마킹.
                    log.error("Failed to enqueue separation jobId={}: {}", jobId, e.message, e)
                    job.status = "FAILED"
                    job.error = "enqueue failed"
                    // 선차감 환불 — 라우트에서 reserve 한 크레딧이 있다면 복원.
                    runCatching { onJobFailed?.invoke(jobId) }
                        .onFailure { ex -> log.warn("refund hook failed jobId={}: {}", jobId, ex.message) }
                }
                onJobChange?.invoke()
            }
        } else {
            // 테스트 / DB-less dev 분기: 큐 없이 즉시 launch. 기존 동작과 동일.
            scope.launch { executePipeline(job) }
        }
        return jobId
    }

    /**
     * Dispatcher 가 호출하는 진입점. claim 한 jobId 의 in-memory 잡을 꺼내 파이프라인 실행.
     * 파이프라인이 Perso projectSeq 를 받는 즉시 queue.markProcessing 호출 (인스턴스 재시작 시
     * polling resumption 의 entry point).
     *
     * in-memory miss 는 stale claim (인스턴스 재시작 후의 잔여) — 운영상 발생 안 해야 하지만
     * 발생하면 FAILED 마킹 후 끝.
     */
    suspend fun runQueuedJob(jobId: String) {
        val job = jobs[jobId]
        if (job == null) {
            log.warn("Dispatcher claimed unknown in-memory jobId={} — marking FAILED", jobId)
            queue?.markFailed(jobId)
            // 인스턴스 재시작 후의 stale claim — 라우트가 선차감했을 가능성 있음. 환불 시도.
            runCatching { onJobFailed?.invoke(jobId) }
                .onFailure { ex -> log.warn("refund hook failed jobId={}: {}", jobId, ex.message) }
            onJobChange?.invoke()
            return
        }
        executePipeline(job)
    }

    /**
     * 인스턴스 재시작 후 PROCESSING 잡 polling 재개. [persoProjectSeq] 는 DB 에 보존된 값으로,
     * Perso 측 잡은 server-side 에서 계속 돌고 있으므로 결과만 받아오면 됨.
     *
     * 호출 전 caller (Application.kt boot scanner) 가 queueRepo.claimOrphanedProcessing 으로
     * bff_instance_id 를 self 로 재할당해두어야 함 — 본 메서드는 in-memory 재구성 + 폴링 launch
     * 만 담당.
     *
     * source 파일은 이미 죽은 인스턴스 디스크에 있어 복구 불가 — runPipelineDownloadPhase 가
     * source 의존성 없이 polling+download 만 하므로 정상 동작.
     */
    fun resumePollingForJob(jobId: String, persoProjectSeq: Long, ownerUserId: UUID?) {
        val outputDir = File(separationDir, jobId).apply { mkdirs() }
        // sourceFile/spec 는 본 경로에서 사용 안 함 — placeholder 로 주입.
        val placeholderSource = File(outputDir, ".resumed-no-source")
        val job = SeparationJob(
            jobId = jobId,
            outputDir = outputDir,
            ownerUserId = ownerUserId,
            sourceFile = placeholderSource,
            spec = SeparationSpec(),
        ).apply {
            status = "PROCESSING"
            progressReason = "Resumed after restart"
        }
        jobs[jobId] = job
        log.info("Resuming separation polling: jobId={} persoProjectSeq={}", jobId, persoProjectSeq)

        scope.launch {
            try {
                externalCalls.withExternalCall("perso", "audio-separation-resume") {
                    // 재시작 후엔 Perso 가 이미 처리 끝났을 가능성 높음 — 곧장 polling 진입해
                    // 첫 호출에서 Completed 받고 download/markReady 흐름으로 진행.
                    runPipelineDownloadPhase(job, persoProjectSeq)
                }
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
                queue?.markFailed(jobId)
                runCatching { onJobFailed?.invoke(jobId) }
                    .onFailure { ex -> log.warn("refund hook failed jobId={}: {}", jobId, ex.message) }
                log.error("Resumed separation pipeline failed: jobId={}", jobId, e)
            } finally {
                onJobChange?.invoke()
            }
        }
    }

    /** 본 함수는 트랜잭션 밖 — 분 단위 Perso 호출 포함. 시작 시점 queue row 는 이미
     *  SUBMITTING (dispatcher claimNext 결과) 또는 in-memory only (queue==null 분기).
     *  markReady DB 콜은 runPipelineDownloadPhase 내부에서 stems_json/actualDurationMs 와
     *  함께 atomic 하게 처리되므로 여기서는 markFailed 만 다룬다. */
    private suspend fun executePipeline(job: SeparationJob) {
        // 동일 잡 동시실행 가드 — reaper 오판으로 재claim 된 잡이 첫 실행이 살아있는 동안 다시
        // 들어오면 즉시 버린다. add()=false 면 이미 in-flight. 차단된 쪽은 capacity 만 풀고 종료.
        if (!inFlight.add(job.jobId)) {
            log.warn("Pipeline already in-flight — skipping duplicate execution: jobId={}", job.jobId)
            onJobChange?.invoke()
            return
        }
        try {
            externalCalls.withExternalCall("perso", "audio-separation") {
                runPipeline(job, job.sourceFile) { projectSeq ->
                    // Perso 가 projectSeq 발급한 직후 → DB 를 SUBMITTING → PROCESSING 으로.
                    queue?.markProcessing(job.jobId, projectSeq)
                }
            }
        } catch (e: Exception) {
            job.status = "FAILED"
            job.error = e.message
            queue?.markFailed(job.jobId)
            // 선차감 환불 — 라우트에서 reserve 한 크레딧이 있다면 복원. 환불 자체가 throw 해도
            // pipeline 의 FAILED 마킹은 그대로 유지 (runCatching).
            runCatching { onJobFailed?.invoke(job.jobId) }
                .onFailure { ex -> log.warn("refund hook failed jobId={}: {}", job.jobId, ex.message) }
            log.error("Separation pipeline failed: jobId={}", job.jobId, e)
        } finally {
            // in-flight 가드 해제 — 이후 정상 재시도 (다른 인스턴스 / 후속 claim) 는 다시 진입 가능.
            inFlight.remove(job.jobId)
            // 끝 → capacity 한 칸 비움. dispatcher 가 즉시 다음 QUEUED 를 pickup 하도록.
            onJobChange?.invoke()
        }
    }

    fun dispose(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        job.outputDir.deleteRecursively()
        log.info("Disposed separation job: {}", jobId)
    }

    /**
     * 사용자가 **대기 중(QUEUED)** 잡을 삭제했을 때의 정리. DB row 삭제는 라우트가
     * [SeparationQueueRepository.deleteOwnedReturning] 으로 이미 끝낸 뒤 호출 — 본 메서드는
     * (1) in-memory 잡 + outputDir 정리([dispose]), (2) 선차감 크레딧 환불([onJobFailed], 멱등),
     * (3) dispatcher 깨우기([onJobChange]) 로 비워진 capacity 를 다음 QUEUED 가 즉시 채우게 한다.
     * 진행 중(SUBMITTING/PROCESSING)·완료(READY) 잡엔 호출하지 않는다(라우트가 status 로 분기).
     */
    suspend fun onQueuedDeleted(jobId: String) {
        dispose(jobId)
        runCatching { onJobFailed?.invoke(jobId) }
            .onFailure { ex -> log.warn("refund hook failed (queued-delete) jobId={}: {}", jobId, ex.message) }
        onJobChange?.invoke()
    }

    /**
     * 큐 레벨 reaper(인스턴스 사망 → stale QUEUED / 영구 FAILED SUBMITTING)가 FAILED 처리한 잡의
     * 크레딧 환불 + in-memory 상태 정리. [SeparationDispatcher.reapPass] 가 reaper 결과의
     * failedJobIds 마다 호출한다. onJobFailed(=CreditRepository.refund) 는 멱등이라 다른 경로의
     * 환불과 겹쳐도 이중 환불 없음. pipeline 의 catch 경로와 달리 reaper 는 in-memory 잡을
     * 거치지 않으므로 별도 진입점이 필요하다 (블로커: 인프라 사망 시 무단 크레딧 소실).
     */
    suspend fun onReapedFailed(jobId: String) {
        jobs[jobId]?.let {
            it.status = "FAILED"
            if (it.error == null) it.error = "reaped (instance churn)"
        }
        runCatching { onJobFailed?.invoke(jobId) }
            .onFailure { ex -> log.warn("refund hook failed (reaped) jobId={}: {}", jobId, ex.message) }
    }

    // ── Pipeline ─────────────────────────────────────────────────────────────

    private suspend fun runPipeline(
        job: SeparationJob,
        sourceFile: File,
        onPersoProjectSeq: suspend (Long) -> Unit = {},
    ) {
        // 모바일이 항상 audio (m4a/mp3/wav) 를 보내므로 sourceFile 그대로 Perso 에 업로드.
        // 라우트가 화이트리스트 검증 + size 제한 적용. video → audio 추출은 모바일 책임.
        job.status = "PROCESSING"
        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        // sourceFile 은 라우트가 caller-owned 로 넘긴 audio. 업로드 성공/실패 무관하게 finally
        // 로 정리 — dispose() 는 outputDir 만 reap 하므로 따로 닦지 않으면 디스크 누수.
        val registration = try {
            persoClient.uploadMedia(PersoMediaType.AUDIO, sourceFile)
        } finally {
            sourceFile.delete()
        }

        // Perso 전용 audio-separation 프로젝트 생성. audio-only 업로드라 isVideoProject=false.
        job.status = "SUBMITTED"
        val projectSeq = persoClient.submitAudioSeparation(
            mediaSeq = registration.seq,
            isVideoProject = false,
            title = "separation-${job.jobId}",
        )
        // queue DB row 를 SUBMITTING → PROCESSING + persoProjectSeq 기록. 인스턴스 재시작 시
        // resumption 의 entry point. 본 hook 호출 실패는 catch 안에서 throw → catch 단에서 FAILED.
        onPersoProjectSeq(projectSeq)

        // 곧장 polling 진입 — Perso 가 source 길이 × 3 보다 훨씬 빨리 끝나는 케이스가 잦아
        // 이전 initialDelay(최대 5분) 동안 사용자 UI 가 progress=0 으로 멈춰있는 사고가 반복됨.
        // pollIntervalMs(default 15s) 단위 getProgress 가 withTransientRetry 로 보호되므로
        // submit 직후 한 번 추가로 친다고 안전성 비용 없음.
        runPipelineDownloadPhase(job, projectSeq)
    }

    /**
     * Perso 가 projectSeq 발급한 이후 단계 — 폴링 + 다운로드 + transcode + READY 마킹.
     * 본 메서드는 [runPipeline] 의 후반부 + 인스턴스 재시작 후 [resumePollingForJob] 의 진입점.
     * source 파일 / spec 의존성이 없어 둘 다 안전하게 호출 가능.
     */
    private suspend fun runPipelineDownloadPhase(
        job: SeparationJob,
        projectSeq: Long,
    ) {
        job.status = "PROCESSING"
        log.info("Perso polling phase begin: jobId={} projectSeq={}", job.jobId, projectSeq)
        pollPersoUntilComplete(
            persoClient, scope, projectSeq,
            pollIntervalMs = pollIntervalMs,
            maxPollMinutes = maxPollMinutes,
        ) { progress, reason ->
            job.progress = progress
            job.progressReason = reason
        }

        // Perso audio-separation 결과 다운로드 — utterance/script 합성 대신 download 엔드포인트 사용.
        //   target=originalVoiceSpeakers → audioFile.voiceAudioDownloadLink (.tar) — 화자별 audio collection
        //   target=originalSubBackground → audioFile.originalSubBackgroundDownloadLink (.wav) — BGM
        // voice_all 전용 target 은 없음 — 화자 stem 들 ffmpeg amix 로 합성.
        //
        // progressReason=Completed 직후엔 download-info 의 flag 가 즉시 true 라 해도 storage upload 가
        // 아직 끝나지 않은 케이스가 있어 첫 다운로드 시 404 발생. download-info 폴링으로 readiness 확정.
        job.status = "DOWNLOADING"
        job.progressReason = "Waiting for storage upload"
        val readyInfo = waitForDownloadInfoReady(projectSeq)

        job.progressReason = "Fetching speaker stems"
        val speakerFiles = downloadAndExtractSpeakerCollection(projectSeq, job.outputDir)
        if (speakerFiles.isEmpty()) {
            throw PersoApiException(500, "No speaker files extracted from Perso for project $projectSeq")
        }

        job.progressReason = "Fetching background"
        val backgroundFile = downloadBackgroundStem(projectSeq, job.outputDir, readyInfo)

        // Perso 가 내려주는 stem 은 PCM wav (비압축) — egress 비용의 큰 부분. FLAC 으로 transcode
        // 해 lossless 유지하면서 ~50% 절감. 모바일 (Android MediaPlayer / iOS AVAudioFile) 모두
        // native 디코드 지원. content-type 은 SeparationRoutes 가 stem.file.extension 으로 자동 매핑.
        val local = mutableListOf<LocalStem>()
        speakerFiles.forEachIndexed { idx, file ->
            val stemId = "$SPEAKER_STEM_PREFIX$idx"
            val finalFile = File(job.outputDir, "$stemId.flac")
            transcodeToFlac(file, finalFile)
            file.delete()
            local.add(LocalStem(stemId, "화자 ${idx + 1}", finalFile))
            log.info("Speaker stem ready: stemId={} file={} size={}B", stemId, finalFile.name, finalFile.length())
        }

        // voice_all = 모든 화자 mix. 화자가 1명이면 speaker_0 와 동일하므로 skip.
        if (local.size >= 2) {
            val voiceAllFile = File(job.outputDir, "voice_all.flac")
            ffmpegMixFiles(local.map { it.file }, voiceAllFile)
            local.add(0, LocalStem("voice_all", "모든 화자", voiceAllFile))
        }

        backgroundFile?.let { wav ->
            val flacBg = File(job.outputDir, "background.flac")
            transcodeToFlac(wav, flacBg)
            wav.delete()
            local.add(LocalStem("background", "배경음", flacBg))
            log.info("Background stem ready: file={} size={}B", flacBg.name, flacBg.length())
        }

        // speaker stem 들은 모두 같은 trim 입력에서 분리돼 동일 길이라 1개만 측정.
        val measuredDurationMs = runCatching {
            MediaTrimmer.probeDurationMs(local.first { it.stemId.startsWith(SPEAKER_STEM_PREFIX) }.file)
        }.getOrNull()
        log.info(
            "Stem actual duration probed: jobId={} actualDurationMs={}",
            job.jobId, measuredDurationMs,
        )

        // Stems 를 in-memory 에 우선 set (status != READY 이므로 route 응답에는 아직 노출 안 됨).
        // 다음으로 R2 eager upload → DB markReady 순서로 durable 한 상태를 만든 뒤 status=READY 로
        // commit. 순서가 중요 — status=READY 가 먼저면 user 가 GET 받았는데 R2/DB 미반영인 짧은
        // window 가 생김.
        job.stems = local
        job.actualDurationMs = measuredDurationMs

        // (1) R2 eager upload — 인스턴스 사망에도 stems FLAC 이 살아남도록. uploadIfAbsent 는
        //     HEAD-first 멱등이라 재호출 비용 minimal. R2 미설정 (로컬 dev) 면 skip — 그 환경은
        //     인스턴스 churn 위험 자체가 없으므로 OK.
        eagerUploadStems(job)

        // (2) DB markReady 에 stem 메타 동봉 — 새 인스턴스가 in-memory 재구축할 때 필요. stems_json
        //     이 NULL 인 채 status=READY 인 row 는 V7 이전 잡 (legacy) 으로 fallback 불가.
        val stemsJson = AppJson.encodeToString(
            ListSerializer(StemMeta.serializer()),
            local.map { StemMeta(it.stemId, it.label, it.file.extension.ifBlank { "flac" }) },
        )
        // markReady 가 0행이면 잡이 **진행 중 삭제**됨 (사용자 DELETE / 계정 erase 가 row 를 지움).
        // 이미 (1) 에서 eager-upload 한 stem 이 DB row 없이 R2 에 남아 orphan 이 되므로 즉시 purge +
        // in-memory 정리 후 종료 — status=READY 로 commit 하지 않는다. queue==null (로컬 dev) 이면
        // 삭제 감지 대상 자체가 없어 null 반환 → 정상 흐름 유지.
        val updated = queue?.markReady(job.jobId, stemsJson, measuredDurationMs)
        if (updated == 0) {
            log.info("Separation job deleted mid-flight — purging R2 stems & disposing: jobId={}", job.jobId)
            purgeUploadedStems(job)
            dispose(job.jobId)
            return
        }

        // (3) Commit — status=READY 후 route 가 stems list 를 응답에 노출.
        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("Separation READY: jobId={} stems={}", job.jobId, local.map { it.stemId })
    }

    /**
     * READY 마킹 직전 stems 를 R2 로 eager upload — 인스턴스 사망에도 데이터 손실 없도록.
     * objectStore null (로컬 dev) 분기에선 skip. ObjectStore.uploadIfAbsent 는 HEAD-first 멱등이라
     * 재시도 / 동일 잡 두 번 처리되어도 안전. 실패하면 throw → 호출자(runPipelineDownloadPhase)에서
     * 그대로 던져 executePipeline catch 가 FAILED 처리 — markReady 가 호출되지 않으므로 DB 도
     * READY 로 안 옮겨감 (durability 일관성 유지).
     */
    private suspend fun eagerUploadStems(job: SeparationJob) {
        val store = objectStore ?: return
        withContext(Dispatchers.IO) {
            job.stems.forEach { stem ->
                val ext = stem.file.extension.ifBlank { "flac" }
                val contentType = contentTypeForExtension(ext, ContentType("audio", "flac")).toString()
                store.uploadIfAbsent(
                    file = stem.file,
                    objectKey = ObjectKey.separationStem(job.jobId, stem.stemId, ext),
                    contentType = contentType,
                )
            }
        }
        log.info("Eager R2 upload completed: jobId={} stems={}", job.jobId, job.stems.size)
    }

    /**
     * 진행 중 삭제된 잡의 eager-upload 된 stem 을 R2 에서 제거 — [eagerUploadStems] 의 역연산.
     * markReady 0행(=row 사라짐) 감지 시 호출. stem 키를 in-memory [SeparationJob.stems] 로 정확히
     * 알고 있어 prefix 스캔 없이 정확 삭제. ext != wav 면 플러그인 lazy WAV transcode 캐시 키도
     * 함께 시도(라우트 DELETE 의 purge 와 동일 — orphan 방지). objectStore null (dev) 이면 skip.
     */
    private suspend fun purgeUploadedStems(job: SeparationJob) {
        val store = objectStore ?: return
        withContext(Dispatchers.IO) {
            job.stems.forEach { stem ->
                val ext = stem.file.extension.ifBlank { "flac" }
                store.deleteObject(ObjectKey.separationStem(job.jobId, stem.stemId, ext))
                if (ext != "wav") store.deleteObject(ObjectKey.separationStem(job.jobId, stem.stemId, "wav"))
            }
        }
        log.info("Purged R2 stems for deleted job: jobId={} stems={}", job.jobId, job.stems.size)
    }

    // ── Speaker collection (.tar) 다운로드 + 풀이 ──────────────────────────────
    private suspend fun downloadAndExtractSpeakerCollection(
        projectSeq: Long,
        outputDir: File,
    ): List<File> {
        val tarFile = File(outputDir, "speakers.tar")
        downloadFreshLinkWithRetry(
            label = "speakers tar (project $projectSeq)",
            target = tarFile,
        ) {
            persoClient.getSeparationDownloadLinks(projectSeq, "originalVoiceSpeakers")
                .audioFile?.voiceAudioDownloadLink
                ?: throw PersoApiException(500,
                    "Perso originalVoiceSpeakers link absent for project $projectSeq")
        }
        log.info("Downloaded speakers tar: projectSeq={} size={}B", projectSeq, tarFile.length())

        val extractDir = File(outputDir, "speakers_raw").apply { mkdirs() }
        val extracted = mutableListOf<File>()
        try {
            TarArchiveInputStream(tarFile.inputStream().buffered()).use { tin ->
                var entry = tin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // directory traversal 방지 — entry name 의 basename 만 사용.
                        val safeName = File(entry.name).name.ifEmpty { "entry_${extracted.size}" }
                        val out = File(extractDir, safeName)
                        out.outputStream().use { os -> tin.copyTo(os) }
                        extracted.add(out)
                        log.info("Extracted speaker entry: name={} size={}B", entry.name, out.length())
                    }
                    entry = tin.nextEntry
                }
            }
        } finally {
            tarFile.delete()
        }
        // 알파벳 순 정렬 — Perso 가 보통 화자 인덱스 포함 파일명 사용 (speaker_1.wav 등).
        // entry 이름 무관하게 정렬된 순서로 speaker_0..N 매핑.
        return extracted.sortedBy { it.name }
    }

    // ── Background (.wav) 다운로드 ──────────────────────────────────────────
    // 정책 (사용자 피드백): 화자 수와 무관하게 항상 **순수 BGM** 만 노출. originalBackgroundPath
    // (OriginalBaseBackground) 만 사용 — Perso 가 화자 수 무관하게 분리해 주는 진짜 BGM only.
    // 과거 1명 화자에서 SubBackground 로 폴백하던 path 는 풀믹스(리액션 포함) 가 섞여 들어와
    // "순수 배경음" 약속을 깨므로 영구 제거. 진짜 BGM 이 누락된 케이스는 stem 자체를 누락시키는
    // 쪽이 안전 — 클라이언트 UI 는 background stem 부재를 graceful 하게 처리한다.
    private suspend fun downloadBackgroundStem(
        projectSeq: Long,
        outputDir: File,
        projectInfo: PersoProjectInfo,
    ): File? {
        val baseBgPath = projectInfo.downloadPathInfo?.originalBackgroundPath
        if (baseBgPath == null) {
            log.warn("originalBackgroundPath absent — no pure BGM stem emitted (project {})", projectSeq)
            return null
        }
        return try {
            val wavFile = File(outputDir, "background.wav")
            downloadFreshLinkWithRetry(
                label = "background base wav (project $projectSeq)",
                target = wavFile,
            ) { baseBgPath }
            log.info("Downloaded background (Base) wav: projectSeq={} size={}B", projectSeq, wavFile.length())
            wavFile
        } catch (e: Exception) {
            log.warn("Failed to download background stem (project {}): {}", projectSeq, e.message)
            null
        }
    }

    /**
     * download-info 의 가용성 플래그가 모두 true 가 될 때까지 폴링 — Perso 가 storage upload 를
     * 끝낸 readiness 신호로 사용. speakers (`hasOriginalSpeakerAudioCollection`) 가 핵심,
     * background (`hasOriginalBackground` — pure BGM, OriginalBaseBackground) 는 best-effort.
     * 과거에는 `hasOriginalSubBackground` 를 기다렸으나, 실제로 fetch 하는 것은 BaseBackground
     * (`originalBackgroundPath`) 라 flag mismatch — wait 가 일찍 풀려 baseBgPath 가 미생성 상태로
     * null 회수되는 사고가 1명 화자 케이스에서 자주 관측됐다. 같은 pure BGM flag 를 본다.
     */
    private suspend fun waitForDownloadInfoReady(
        projectSeq: Long,
        intervalMs: Long = 3_000,
        timeoutMs: Long = 60_000,
    ): PersoProjectInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (true) {
            // getProjectInfo 는 downloadInfo 와 downloadPathInfo 를 한 응답에 묶어 돌려주므로
            // 마지막 응답을 호출자에게 반환 — downloadBackgroundStem 이 originalBackgroundPath
            // 를 다시 fetch 하지 않게 한다.
            val full = persoClient.getProjectInfo(projectSeq)
            val info = full.downloadInfo ?: PersoDownloadInfo()
            attempt += 1
            if (info.hasOriginalSpeakerAudioCollection && info.hasOriginalBackground) {
                log.info("download-info ready (speakers + pure BGM): projectSeq={} attempts={}", projectSeq, attempt)
                return full
            }
            if (System.currentTimeMillis() >= deadline) {
                // speakers 가 ready 면 진행 — pure BGM 만 누락이면 best-effort 로 통과.
                if (info.hasOriginalSpeakerAudioCollection) {
                    log.warn("download-info partial ready: projectSeq={} speakers=true pureBGM={} (timeout — proceed)",
                        projectSeq, info.hasOriginalBackground)
                    return full
                }
                throw PersoApiException(500,
                    "Perso storage not ready after ${timeoutMs}ms: speakers=${info.hasOriginalSpeakerAudioCollection}")
            }
            log.info("download-info not ready: projectSeq={} speakers={} pureBGM={} attempt={} — sleeping {}ms",
                projectSeq, info.hasOriginalSpeakerAudioCollection, info.hasOriginalBackground, attempt, intervalMs)
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    /**
     * Perso storage 다운로드 backoff retry — **매 시도마다 getSeparationDownloadLinks 를 다시
     * 호출**해 fresh path 를 받는다. 단순 retry 가 안 되는 이유:
     *   1) Perso 응답 path 의 timestamp 가 호출 시점에 임베드 (`..._OriginalVoiceSpeakers_<ts>.tar`).
     *      매 호출마다 unique path → cache key 도 다름.
     *   2) 첫 GET 의 404 응답이 `Cache-Control: public, max-age=14400` 로 Cloudflare 에 4시간 캐시.
     *      같은 path 로 retry 하면 4시간 동안 같은 404 가 cache 에서 옴 (storage 에 file 이 올라와도).
     * 따라서 매 시도마다 새 path 를 받아야 새 cache key 로 origin 까지 재요청 — storage 가 ready 면
     * 이번엔 200 으로 풀림.
     */
    private suspend fun downloadFreshLinkWithRetry(
        label: String,
        target: File,
        attempts: Int = 12,
        backoffMs: Long = 5_000,
        getUrl: suspend () -> String,
    ) {
        var lastErr: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val url = getUrl()
                persoClient.streamDownloadAuthorized(url, target)
                if (attempt > 0) log.info("{} succeeded on attempt {}/{}", label, attempt + 1, attempts)
                return
            } catch (e: PersoApiException) {
                // 4xx 중 404 만 재시도 (storage upload 지연). 다른 4xx 는 즉시 throw — 재시도해도 안 풀림.
                if (e.statusCode != 404 && e.statusCode in 400..499) throw e
                lastErr = e
                log.warn("{} {} (attempt {}/{}) — fetching fresh link in {}ms",
                    label, e.statusCode, attempt + 1, attempts, backoffMs)
                kotlinx.coroutines.delay(backoffMs)
            } catch (e: Exception) {
                // getUrl 실패도 동일 backoff (link 응답이 한시적으로 비어있을 수 있음)
                lastErr = e
                log.warn("{} link fetch threw (attempt {}/{}) — retrying in {}ms: {}",
                    label, attempt + 1, attempts, backoffMs, e.message)
                kotlinx.coroutines.delay(backoffMs)
            }
        }
        throw lastErr ?: RuntimeException("$label failed after $attempts attempts")
    }

    /**
     * 이미 timeline-aligned 된 audio 파일들 amix. (Perso 의 화자 stem 들은 timeline-aligned)
     * 단일 입력이면 ffmpeg 호출 대신 파일 복사 (불필요한 재인코딩 회피).
     */
    private suspend fun ffmpegMixFiles(inputs: List<File>, output: File) {
        require(inputs.isNotEmpty()) { "ffmpegMixFiles needs at least one input" }
        if (inputs.size == 1) {
            inputs[0].copyTo(output, overwrite = true)
            return
        }
        val cmd = mutableListOf("ffmpeg", "-y")
        inputs.forEach { cmd += listOf("-i", it.absolutePath) }
        val labels = inputs.indices.joinToString("") { "[$it:a]" }
        // normalize=0: voice_all 은 화자 stem 들을 원본 발화 bus 로 재조립하는 것 →
        // 원본 = sum(speaker stems) 이므로 평균(default normalize=1) 이 아니라 합산이
        // 맞음. 평균을 내면 voice_all 자체가 -6dB(2 화자) 이상 작아지고, 그걸 또
        // downstream (RenderService.buildFfmpegCommand 의 separation amix) 가 다시
        // 입력으로 받으면 누적 -12dB ~ 까지 떨어짐.
        cmd += listOf(
            "-filter_complex",
            "${labels}amix=inputs=${inputs.size}:duration=longest:dropout_transition=0:normalize=0[out]"
        )
        // FLAC lossless — voice_all 은 downstream render amix 의 입력으로 쓰므로
        // mp3 같은 lossy 단계가 끼면 음질 손실 누적. compression_level 5 는 ffmpeg default,
        // encode 시간/크기 균형.
        cmd += listOf("-map", "[out]", "-c:a", "flac", "-compression_level", "5", output.absolutePath)
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg mix files (${inputs.size})")
    }

    /**
     * Single-stream FLAC transcode. Perso wav stem 을 FLAC 으로 무손실 압축해 egress 절반.
     * compression_level 5 (ffmpeg default) — 더 높은 level 은 encode 시간만 늘고 size 차이 미미.
     */
    private suspend fun transcodeToFlac(input: File, output: File) {
        require(input.absolutePath != output.absolutePath) { "transcodeToFlac: input == output" }
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", input.absolutePath,
            "-c:a", "flac",
            "-compression_level", "5",
            output.absolutePath,
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg flac transcode ${input.name}")
        if (!output.exists()) throw RuntimeException("ffmpeg flac transcode produced no output: ${input.name}")
    }

    private suspend fun drainAndAwait(process: Process, label: String) {
        FfmpegRunner.drainAndAwait(process, label, timeoutMinutes = 10)
    }

    private fun cleanupAbandoned() {
        val now = System.currentTimeMillis()
        jobs.entries
            .filter { (_, j) ->
                val age = now - j.createdAt
                val abandoned = j.status == "READY" && age > config.abandonTtlMs
                // FAILED jobs get a short grace period so clients can observe
                // the error once, then are reaped independently of the longer
                // abandoned-READY TTL.
                val failedExpired = j.status == "FAILED" && age > FAILED_JOB_TTL_MS
                abandoned || failedExpired
            }
            .forEach { (id, _) ->
                dispose(id)
                log.info("Cleaned up separation: {}", id)
            }
    }

}
