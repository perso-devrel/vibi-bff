package com.vibi.bff.service

import com.vibi.bff.FAILED_JOB_TTL_MS
import com.vibi.bff.config.SeparationConfig
import com.vibi.bff.model.PersoDownloadInfo
import com.vibi.bff.model.PersoProjectInfo
import com.vibi.bff.model.SeparationSpec
import com.vibi.bff.plugins.PersoApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// State machine:
//   PENDING → UPLOADING_UPSTREAM → SUBMITTED → PROCESSING → DOWNLOADING → READY
//                                                              │
//                                                              └→ FAILED
// READY + consumedByMixJobId=null → eligible for mix reservation.
// READY + consumedByMixJobId=set  → mix in flight; blocks re-submit.
// COMPLETED mix triggers dispose(jobId); FAILED is kept briefly so clients
// can read the error before the cleanup task reaps it.

data class SeparationJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var progressReason: String? = null,
    @Volatile var error: String? = null,
    val outputDir: File,
    @Volatile var stems: List<LocalStem> = emptyList(),
    @Volatile var consumedByMixJobId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** SeparationService.dedupIndex 와 1:1 — 버튼 연타로 동일 (source+spec) 가
     * 다시 들어와도 같은 jobId 를 돌려주기 위한 키. null 이면 dedup 대상 외
     * (legacy upload path). dispose() 가 이 키로 index 정리. */
    val dedupKey: String? = null,
)

data class LocalStem(
    val stemId: String,
    val label: String,
    val file: File,
)

class SeparationService(
    private val persoClient: PersoClient,
    private val separationDir: File,
    private val config: SeparationConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val pollIntervalMs: Long,
    private val maxPollMinutes: Int,
    /** admin 대시보드용 Postgres 영속화. null 이면 분석 write skip (테스트). */
    private val analytics: JobAnalyticsRepository? = null,
    /** Perso API 호출 instrumentation. null 이면 외부 호출 추적 skip. */
    private val externalCalls: ExternalApiCallsRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, SeparationJob>()
    /** dedupKey → jobId. submit 의 atomic check-and-claim 으로 같은 키 중복 submit
     * 차단 (버튼 연타 방어). 키는 SeparationRoutes.buildSeparationDedupKey 가 계산.
     * dispose() 가 SeparationJob.dedupKey 로 entry 정리. FAILED 잡은 dedup 대상
     * 아님 (재시도 허용). */
    private val dedupIndex = ConcurrentHashMap<String, String>()
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

    fun getJob(jobId: String): SeparationJob? = jobs[jobId]

    /**
     * 주어진 [dedupKey] 로 등록된 jobId 중 still-active 한 것을 반환. active 는
     * READY 직전까지의 진행 상태 + READY-but-not-yet-consumed-by-mix. FAILED 나
     * 이미 mix 에 소비된 READY 잡은 active 가 아니므로 null 반환 → caller 는 새
     * 잡을 만들면 됨. SeparationRoutes 의 pre-check 가 disk 낭비 회피 용도로 사용.
     */
    fun findActiveJob(dedupKey: String): String? {
        val jobId = dedupIndex[dedupKey] ?: return null
        val job = jobs[jobId] ?: return null
        val active = job.status != "FAILED" &&
            !(job.status == "READY" && job.consumedByMixJobId != null)
        return if (active) jobId else null
    }

    /**
     * [dedupKey] 가 non-null 이면 atomic check-and-claim — 같은 키의 active 잡이
     * 있으면 [sourceFile] 을 삭제하고 기존 jobId 를 반환한다. dispose() 가 같은
     * 키 entry 를 정리하므로 disposed 잡의 키는 자유롭게 재사용된다.
     *
     * [userId] / [sourceDurationMs] / [renderJobId] 는 admin 대시보드 분석 row 용.
     * userId == null 이면 분석 write skip (테스트 등). renderJobId 는 spec.editedRenderJobId
     * 경유 시 RenderJobsTable 의 PK 와 동일 텍스트, legacy 업로드 경로면 null.
     */
    fun submit(
        sourceFile: File,
        spec: SeparationSpec,
        dedupKey: String? = null,
        userId: UUID? = null,
        sourceDurationMs: Long = 0L,
        renderJobId: String? = null,
    ): String {
        if (dedupKey != null) {
            // synchronized(dedupIndex) 로 (check, claim) 을 원자화 — 두 동시 호출이
            // 동일 키로 들어와도 한 쪽만 새 잡 생성, 다른 쪽은 기존 jobId 반환.
            // findActiveJob 자체는 비-원자이지만 critical section 안에서 호출하므로 OK.
            synchronized(dedupIndex) {
                findActiveJob(dedupKey)?.let { existing ->
                    sourceFile.delete()
                    log.info("Separation dedup hit: key={} → existing jobId={}", dedupKey, existing)
                    return existing
                }
                val jobId = createAndLaunch(sourceFile, spec, dedupKey, userId, sourceDurationMs, renderJobId)
                dedupIndex[dedupKey] = jobId
                return jobId
            }
        }
        return createAndLaunch(sourceFile, spec, dedupKey = null, userId, sourceDurationMs, renderJobId)
    }

    private fun createAndLaunch(
        sourceFile: File,
        spec: SeparationSpec,
        dedupKey: String?,
        userId: UUID? = null,
        sourceDurationMs: Long = 0L,
        renderJobId: String? = null,
    ): String {
        val jobId = "sep-${UUID.randomUUID()}"
        val outputDir = File(separationDir, jobId).apply { mkdirs() }
        val job = SeparationJob(jobId = jobId, outputDir = outputDir, dedupKey = dedupKey)
        jobs[jobId] = job

        scope.launch {
            if (userId != null) {
                analytics?.insertSeparationJob(jobId, userId, renderJobId, sourceDurationMs, "PROCESSING")
            }
            try {
                externalCalls.withExternalCall("perso", "audio-separation") {
                    runPipeline(job, sourceFile, spec)
                }
                if (userId != null && job.status == "READY") {
                    analytics?.updateSeparationJobStatus(jobId, "READY")
                }
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
                // FAILED 상태에선 같은 키 재submit 이 새 잡을 만들어야 하므로 즉시
                // dedup entry 정리 (cleanupAbandoned 의 FAILED_JOB_TTL 까지 기다리면
                // 그동안 mobile 이 재시도해도 실패한 잡 ID 만 받게 됨).
                dedupKey?.let { dedupIndex.remove(it, jobId) }
                if (userId != null) {
                    analytics?.updateSeparationJobStatus(jobId, "FAILED")
                }
                // Leave sourceFile on disk — caller can retry; cleanupAbandoned
                // reaps it via dispose() once FAILED_JOB_TTL_MS passes.
                log.error("Separation pipeline failed: jobId={}", jobId, e)
            }
        }
        return jobId
    }

    // Atomically reserve stems for a given mix. Returns null if the job is
    // not READY or has already been consumed, ensuring stems can never be
    // double-deleted by two concurrent mix submissions.
    fun reserveForMix(jobId: String, mixJobId: String): SeparationJob? {
        val job = jobs[jobId] ?: return null
        synchronized(job) {
            if (job.status != "READY" || job.consumedByMixJobId != null) return null
            job.consumedByMixJobId = mixJobId
            return job
        }
    }

    fun releaseReservation(jobId: String) {
        val job = jobs[jobId] ?: return
        synchronized(job) {
            if (job.status == "READY") job.consumedByMixJobId = null
        }
    }

    fun dispose(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        // remove(key, expectedValue) 로 stale entry (이미 다른 잡이 같은 키로 교체된 경우)
        // 는 건드리지 않음. FAILED 경로에서도 동일 정리가 일어나지만 멱등 안전.
        job.dedupKey?.let { dedupIndex.remove(it, jobId) }
        job.outputDir.deleteRecursively()
        log.info("Disposed separation job: {}", jobId)
    }

    // ── Pipeline ─────────────────────────────────────────────────────────────

    private suspend fun runPipeline(job: SeparationJob, sourceFile: File, spec: SeparationSpec) {
        val isVideo = spec.mediaType == "VIDEO"

        // 영상 → mp3 추출 후 audio project 로 업로드 — audio-separation 결과는 audio 트랙만으로
        // 동일하게 나오므로 video 트랙 통째로 보내는 건 낭비.
        job.status = "PROCESSING"
        val uploadFile = if (isVideo) {
            job.progressReason = "Extracting audio"
            val mp3 = File(job.outputDir, "audio.mp3")
            extractAudioForUpload(sourceFile, mp3)
            sourceFile.delete()
            mp3
        } else {
            sourceFile
        }

        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        val registration = persoClient.uploadMedia(PersoMediaType.AUDIO, uploadFile)
        uploadFile.delete()

        // Perso 전용 audio-separation 프로젝트 생성. audio-only 업로드라 isVideoProject=false.
        job.status = "SUBMITTED"
        val projectSeq = persoClient.submitAudioSeparation(
            mediaSeq = registration.seq,
            isVideoProject = false,
            title = "separation-${job.jobId}",
        )

        job.status = "PROCESSING"
        // 폴링 전략: 음성분리 처리 시간 ≈ 구간 길이 × 3. 매우 긴 영상이면 5분으로 cap —
        // 그 이상 sleep 하면 client 가 PROCESSING 상태에서 progressReason 변화 없이
        // 멈춰있는 것처럼 보여 timeout 으로 의심받음. 5분 후엔 정상 polling 으로 진입.
        val rangeMs = if (spec.trimStartMs != null && spec.trimEndMs != null) {
            spec.trimEndMs - spec.trimStartMs
        } else 0L
        val initialDelayMs = (if (rangeMs > 0) rangeMs * 3 else 30_000L)
            .coerceAtMost(5 * 60_000L)
        log.info("Initial Perso wait: {}ms (range×3 capped 5min, range={}ms)", initialDelayMs, rangeMs)
        delay(initialDelayMs)
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

        val local = mutableListOf<LocalStem>()
        speakerFiles.forEachIndexed { idx, file ->
            val stemId = "speaker_$idx"
            val finalFile = File(job.outputDir, "$stemId.${file.extension.ifEmpty { "audio" }}")
            // renameTo 는 cross-FS / target-exists / Windows file lock 등에서 silently
            // false 반환 — fallback 으로 copy + delete. 둘 다 실패하면 stem 자체가 없는
            // 셈이라 skip 하지 않고 explicit throw (downstream amix 에서 NPE 보다 명확).
            if (!file.renameTo(finalFile)) {
                file.copyTo(finalFile, overwrite = true)
                file.delete()
            }
            local.add(LocalStem(stemId, "화자 ${idx + 1}", finalFile))
            log.info("Speaker stem ready: stemId={} file={} size={}B", stemId, finalFile.name, finalFile.length())
        }

        // voice_all = 모든 화자 mix. 화자가 1명이면 speaker_0 와 동일하므로 skip.
        if (local.size >= 2) {
            val voiceAllFile = File(job.outputDir, "voice_all.mp3")
            ffmpegMixFiles(local.map { it.file }, voiceAllFile)
            local.add(0, LocalStem("voice_all", "모든 화자", voiceAllFile))
        }

        backgroundFile?.let {
            local.add(LocalStem("background", "배경음", it))
            log.info("Background stem ready: file={} size={}B", it.name, it.length())
        }

        job.stems = local
        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("Separation READY: jobId={} stems={}", job.jobId, local.map { it.stemId })
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
        // StemMixService 가 다시 amix 하면 누적 -12dB ~ 까지 떨어짐.
        cmd += listOf(
            "-filter_complex",
            "${labels}amix=inputs=${inputs.size}:duration=longest:dropout_transition=0:normalize=0[out]"
        )
        cmd += listOf("-map", "[out]", "-q:a", "4", output.absolutePath)
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg mix files (${inputs.size})")
    }

    /**
     * 영상 → mp3 audio 추출. 음성분리는 audio 만 필요하므로 video 트랙 통째로 버림.
     */
    private suspend fun extractAudioForUpload(input: File, output: File) {
        extractMp3(input, output, quality = 5, timeoutMinutes = 10, label = "ffmpeg audio extract")
        log.info("ffmpeg audio extract done: input={} ({}B) output={} ({}B)",
            input.name, input.length(), output.name, output.length())
    }

    private suspend fun drainAndAwait(process: Process, label: String) {
        FfmpegRunner.drainAndAwait(process, label, timeoutMinutes = 10)
    }

    private fun cleanupAbandoned() {
        val now = System.currentTimeMillis()
        jobs.entries
            .filter { (_, j) ->
                val age = now - j.createdAt
                val abandoned = j.status == "READY" && j.consumedByMixJobId == null &&
                    age > config.abandonTtlMs
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
