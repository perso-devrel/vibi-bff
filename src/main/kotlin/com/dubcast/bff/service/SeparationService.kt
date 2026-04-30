package com.dubcast.bff.service

import com.dubcast.bff.config.SeparationConfig
import com.dubcast.bff.model.PersoScriptSentence
import com.dubcast.bff.model.SeparationSpec
import com.dubcast.bff.plugins.PersoApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
private const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L

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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, SeparationJob>()
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

    fun submit(sourceFile: File, spec: SeparationSpec): String {
        val jobId = "sep-${UUID.randomUUID()}"
        val outputDir = File(separationDir, jobId).apply { mkdirs() }
        val job = SeparationJob(jobId = jobId, outputDir = outputDir)
        jobs[jobId] = job

        scope.launch {
            try {
                runPipeline(job, sourceFile, spec)
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
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
        // 폴링 전략: 음성분리 처리 시간 ≈ 구간 길이 × 3.
        val rangeMs = if (spec.trimStartMs != null && spec.trimEndMs != null) {
            spec.trimEndMs - spec.trimStartMs
        } else 0L
        val initialDelayMs = if (rangeMs > 0) rangeMs * 3 else 30_000L
        log.info("Initial Perso wait: {}ms (range×3, range={}ms)", initialDelayMs, rangeMs)
        delay(initialDelayMs)
        pollPersoUntilComplete(
            persoClient, scope, projectSeq,
            pollIntervalMs = 10_000L,
            maxPollMinutes = maxPollMinutes,
        ) { progress, reason ->
            job.progress = progress
            job.progressReason = reason
        }

        // 1) audio-separation script paginated 수집.
        job.status = "DOWNLOADING"
        job.progressReason = "Fetching script"
        val sentences = collectSeparationScript(projectSeq)
        if (sentences.isEmpty()) {
            throw PersoApiException(500, "Perso audio-separation returned 0 sentences for project $projectSeq")
        }

        // 2) 화자별로 sentences group 후 timeline-aligned mp3 stem 생성.
        // Perso 의 audio-separation 은 utterance 단위 audioUrl 만 제공 — 화자별 stem 은 클라이언트 합성.
        val totalDurationMs = sentences.maxOf { it.offsetMs + it.durationMs }
        // audioUrl 누락 진단: null vs blank 비율, 화자별 분포, sample 1건 노출.
        val nullCount = sentences.count { it.audioUrl == null }
        val blankCount = sentences.count { it.audioUrl != null && it.audioUrl!!.isBlank() }
        val validCount = sentences.size - nullCount - blankCount
        val perSpeaker = sentences.groupBy { it.speakerOrderIndex }
            .mapValues { (_, v) -> v.count { !it.audioUrl.isNullOrBlank() } to v.size }
        log.info("Separation script audioUrl status: projectSeq={} total={} valid={} null={} blank={} perSpeaker(valid/total)={}",
            projectSeq, sentences.size, validCount, nullCount, blankCount, perSpeaker)
        if (validCount == 0) {
            val sample = sentences.first()
            log.warn("All audioUrl missing — sample sentence: seq={} speakerOrderIndex={} offsetMs={} durationMs={} audioUrl={} originalText={}",
                sample.seq, sample.speakerOrderIndex, sample.offsetMs, sample.durationMs, sample.audioUrl, sample.originalText)
        }
        val bySpeaker = sentences
            .filter { !it.audioUrl.isNullOrBlank() }
            .groupBy { it.speakerOrderIndex }
            .toSortedMap()

        val local = mutableListOf<LocalStem>()
        bySpeaker.entries.forEachIndexed { idx, (speakerIdx, sList) ->
            val stemId = "speaker_$idx"
            val label = "화자 ${idx + 1}"
            val stemFile = File(job.outputDir, "$stemId.mp3")
            buildSpeakerStem(sList, totalDurationMs, job.outputDir, stemFile)
            local.add(LocalStem(stemId, label, stemFile))
            log.info("Built speaker stem: speakerOrderIndex={} stemId={} segments={}",
                speakerIdx, stemId, sList.size)
        }

        // 3) voice_all = 모든 화자 mix. 화자별 stem 이 이미 timeline-aligned 이므로 그것들만 amix 하면
        // raw utterance 재다운로드/재합성 안 해도 됨. 화자가 1명이면 voice_all == speaker_0 이라
        // 중복이라 skip.
        if (local.size >= 2) {
            val voiceAllFile = File(job.outputDir, "voice_all.mp3")
            ffmpegMixFiles(local.map { it.file }, voiceAllFile)
            local.add(0, LocalStem("voice_all", "모든 화자", voiceAllFile))
        }

        if (local.isEmpty()) {
            throw PersoApiException(500, "No speaker stems extracted from Perso for project $projectSeq")
        }

        job.stems = local
        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("Separation READY: jobId={} stems={}", job.jobId, local.map { it.stemId })
    }

    // ── audio-separation script paginated 수집 ─────────────────────────────
    private suspend fun collectSeparationScript(projectSeq: Long): List<PersoScriptSentence> {
        val all = mutableListOf<PersoScriptSentence>()
        var cursor: Long? = null
        var pages = 0
        while (true) {
            val page = persoClient.getAudioSeparationScript(projectSeq, cursor)
            all += page.sentences
            pages += 1
            if (!page.hasNext || page.nextCursorId == null || pages >= 200) break
            cursor = page.nextCursorId
        }
        log.info("Collected separation sentences: projectSeq={} count={} pages={}", projectSeq, all.size, pages)
        return all.sortedBy { it.offsetMs }
    }

    // ── 화자 stem 빌드: 각 utterance audioUrl 다운로드 → ffmpeg adelay+amix 로 timeline-aligned 합성 ──
    private suspend fun buildSpeakerStem(
        utterances: List<PersoScriptSentence>,
        totalDurationMs: Long,
        workDir: File,
        outputFile: File,
    ) {
        if (utterances.isEmpty()) {
            // 빈 stem — 무음 트랙으로 대체.
            ffmpegSilence(totalDurationMs, outputFile)
            return
        }

        val downloaded = mutableListOf<Pair<File, Long>>() // file → offsetMs
        try {
            utterances.forEachIndexed { idx, s ->
                val url = s.audioUrl ?: return@forEachIndexed
                val tmp = File(workDir, "u_${outputFile.nameWithoutExtension}_$idx.mp3")
                persoClient.streamDownloadAuthorized(url, tmp)
                downloaded.add(tmp to s.offsetMs)
            }
            ffmpegMixWithOffsets(downloaded, totalDurationMs, outputFile)
        } finally {
            downloaded.forEach { (f, _) -> f.delete() }
        }
    }

    private fun ffmpegSilence(durationMs: Long, output: File) {
        val durSec = durationMs.coerceAtLeast(1L) / 1000.0
        val process = ProcessBuilder(
            "ffmpeg", "-y",
            "-f", "lavfi",
            "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
            "-t", durSec.toString(),
            "-q:a", "4",
            output.absolutePath,
        ).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg silence")
    }

    private fun ffmpegMixWithOffsets(
        inputs: List<Pair<File, Long>>,
        totalDurationMs: Long,
        output: File,
    ) {
        if (inputs.isEmpty()) {
            ffmpegSilence(totalDurationMs, output); return
        }
        val durSec = totalDurationMs.coerceAtLeast(1L) / 1000.0
        val cmd = mutableListOf("ffmpeg", "-y")
        // input 0: silence base (총 길이 보장)
        cmd += listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100")
        // input 1..N: 각 utterance
        inputs.forEach { (file, _) -> cmd += listOf("-i", file.absolutePath) }
        // filter_complex: 각 input 에 adelay=offset, 마지막에 amix
        val filterParts = mutableListOf<String>()
        val mixLabels = mutableListOf("[0:a]")
        inputs.forEachIndexed { i, (_, offsetMs) ->
            val srcIdx = i + 1
            val label = "[a$srcIdx]"
            filterParts.add("[$srcIdx:a]adelay=${offsetMs}|${offsetMs}$label")
            mixLabels.add(label)
        }
        val mixCount = mixLabels.size
        filterParts.add("${mixLabels.joinToString("")}amix=inputs=$mixCount:duration=first:dropout_transition=0[out]")
        cmd += listOf("-filter_complex", filterParts.joinToString(";"))
        cmd += listOf("-map", "[out]", "-t", durSec.toString(), "-q:a", "4", output.absolutePath)

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg mix (${inputs.size} inputs)")
    }

    /**
     * 이미 timeline-aligned 된 mp3 파일들 amix. utterances 재다운로드/재합성 회피용.
     * 단일 입력이면 ffmpeg 호출 대신 파일 복사 (불필요한 재인코딩 회피).
     */
    private fun ffmpegMixFiles(inputs: List<File>, output: File) {
        require(inputs.isNotEmpty()) { "ffmpegMixFiles needs at least one input" }
        if (inputs.size == 1) {
            inputs[0].copyTo(output, overwrite = true)
            return
        }
        val cmd = mutableListOf("ffmpeg", "-y")
        inputs.forEach { cmd += listOf("-i", it.absolutePath) }
        val labels = inputs.indices.joinToString("") { "[$it:a]" }
        cmd += listOf(
            "-filter_complex",
            "${labels}amix=inputs=${inputs.size}:duration=longest:dropout_transition=0[out]"
        )
        cmd += listOf("-map", "[out]", "-q:a", "4", output.absolutePath)
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg mix files (${inputs.size})")
    }

    /**
     * 영상 → mp3 audio 추출. 음성분리는 audio 만 필요하므로 video 트랙 통째로 버림.
     */
    private fun extractAudioForUpload(input: File, output: File) {
        val process = ProcessBuilder(
            "ffmpeg", "-y",
            "-i", input.absolutePath,
            "-vn",
            "-c:a", "libmp3lame",
            "-q:a", "5",
            output.absolutePath
        ).redirectErrorStream(true).start()
        drainAndAwait(process, "ffmpeg audio extract")
        log.info("ffmpeg audio extract done: input={} ({}B) output={} ({}B)",
            input.name, input.length(), output.name, output.length())
    }

    private fun drainAndAwait(process: Process, label: String) {
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { out.appendLine(it) }
            }
        }.also { it.isDaemon = true; it.start() }
        val finished = process.waitFor(10, TimeUnit.MINUTES)
        reader.join(2000)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("$label timed out\n${out.toString().takeLast(2000)}")
        }
        if (process.exitValue() != 0) {
            throw RuntimeException("$label failed (exit=${process.exitValue()}):\n${out.toString().takeLast(3000)}")
        }
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
