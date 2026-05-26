package com.vibi.bff.service

import com.vibi.bff.model.BgmClip
import com.vibi.bff.model.Segment
import com.vibi.bff.routes.ObjectKey
import com.vibi.bff.routes.contentTypeForExtension
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 출력 audio 인코딩 프로필. 최종 mix 패스의 video 는 `-c:v copy` 로 stream-copy 되므로
 * audio bitrate 만 결정. per-segment trim 의 libx264 는 별개 고정 설정 (preset=veryfast,
 * default CRF) — quality 와 무관.
 *
 * crf/preset 필드는 향후 video 재인코딩이 다시 필요해질 경우의 hook 으로 남겨둠 (현재 운영
 * surface 는 BGM atrim+amix / 음성분리 stem mix 만 — final 패스가 `-c:v copy` 인 한 미사용).
 *
 * Input validation 은 [com.vibi.bff.model.RenderConfig.init] 에서 일어나 high/medium/low
 * 외 값은 [of] 도달 전에 reject 됨.
 */
internal data class QualityProfile(
    val crf: Int,
    val preset: String,
    val audioBitrate: String,
) {
    companion object {
        fun of(name: String): QualityProfile = when (name) {
            "high"   -> QualityProfile(crf = 20, preset = "medium", audioBitrate = "192k")
            "medium" -> QualityProfile(crf = 23, preset = "fast",   audioBitrate = "192k")
            "low"    -> QualityProfile(crf = 28, preset = "fast",   audioBitrate = "128k")
            else     -> error("unreachable: validated upstream, got '$name'")
        }
    }
}

data class RenderJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var error: String? = null,
    val outputFile: File,
    val createdAt: Long = System.currentTimeMillis(),
    /** "queued" while waiting for an ffmpeg permit; null once acquired or done. */
    @Volatile var progressReason: String? = null,
    /** Separation pipeline references this job via editedRenderJobId. Cleanup is
     * last-access based so a referenced job stays alive as long as the client
     * keeps touching it. */
    @Volatile var lastAccessedAt: Long = System.currentTimeMillis(),
    /** submitRender 의 principal.userId 가 그대로 보존. null 이면 미인증 잡 (테스트 /
     * 운영 boot 직후 jwtSecret 미주입 분기). 다운로드 / editedRenderJobId 재사용 시
     * 소유권 검증에 사용 — null 이면 검증 skip. */
    val ownerUserId: UUID? = null,
)

/** 한 separationDirective 의 ffmpeg 입력용 형태. selections 의 audioUrl 은 라우트 단계에서
 * 로컬 파일로 해석되어 본 구조체로 들어온다. range 는 mute 윈도우 + adelay 의 base 이다. */
data class DirectiveStem(val file: File, val volume: Float)
data class DirectiveWithStemFiles(
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val muteOriginalSegmentAudio: Boolean,
    val stems: List<DirectiveStem>,
    /** Stem audio 파일 안의 시작 offset (ms). split directive 의 뒤쪽 piece 가 stem 중간부터 재생. */
    val sourceOffsetMs: Long = 0L,
)

private const val MAX_PARALLEL_SEGMENTS = 2

/**
 * 너무 짧은 audio segment 는 ffmpeg/AAC 인코더가 받아도 의미 없고 (STT 도 인식 못 함),
 * `Double.toString()` 의 scientific notation 변환과 결합해 ffmpeg `-t` 파싱 자체를
 * 깨뜨려 본 파이프라인 전체를 fail 시킨다. 20ms 미만은 silent fallback 으로 직행하고,
 * 그 fallback 도 최소 20ms 길이를 강제한다.
 */
private const val MIN_AUDIO_SEGMENT_SEC = 0.020

/**
 * ffmpeg 의 `-ss`, `-t`, `-to` 등은 fixed-decimal 시간 인자만 받는다 — Kotlin 의
 * `Double.toString()` 은 작은 값(예: 0.000670)을 `"6.7E-4"` 같은 scientific
 * notation 으로 출력해 ffmpeg 가 `Invalid duration for option t` 로 즉시 실패한다.
 *
 * 마이크로초(6 자리) 정밀도는 ffmpeg 가 사용하는 내부 PTS 단위(`AV_TIME_BASE=1e6`)
 * 와 일치하므로 정확한 cutoff. Locale 을 US 로 고정해 콤마-decimal 로케일에서도
 * 안전.
 */
internal fun secondsToFfmpegArg(seconds: Double): String =
    String.format(Locale.US, "%.6f", seconds)

class RenderService(
    private val renderDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    /** 2h window for the separation pipeline to pick up the rendered output as
     * source. cleanup uses lastAccessedAt so each touch resets the clock. */
    private val jobTtlMs: Long = 7200_000,
    /** Hard cap on concurrent top-level render jobs that can spawn ffmpeg.
     * 5+ variant fan-out otherwise saturates CPU + memory. submitRender
     * returns immediately; the launched coroutine acquires the permit
     * before doing any ffmpeg work, so queued jobs report status=PROCESSING
     * with progressReason="queued" until a slot frees up. */
    maxConcurrentRenders: Int = defaultMaxConcurrent(),
    /** admin 대시보드용 Postgres 영속화. null 이면 분석 write skip (테스트 / DB-less dev).
     * 운영에선 Application.kt 가 항상 주입한다. */
    private val analytics: JobAnalyticsRepository? = null,
    /** R2 eager push 용. null 이면 lazy fallback 만 동작 (기존 DownloadResponder.respondDownload
     * 의 uploadIfAbsent 가 다운로드 시점에 안전망). */
    private val objectStore: ObjectStore? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, RenderJob>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "render-cleanup").apply { isDaemon = true }
    }
    private val ffmpegSemaphore = Semaphore(maxConcurrentRenders.coerceAtLeast(1))

    init {
        renderDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupExpiredJobs, 10, 10, TimeUnit.MINUTES)
        log.info(
            "Render directory initialized at {} (maxConcurrent={})",
            renderDir.absolutePath, maxConcurrentRenders.coerceAtLeast(1)
        )
    }

    fun shutdown() {
        cleanup.shutdown()
        if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) cleanup.shutdownNow()
    }

    fun getJob(jobId: String): RenderJob? = jobs[jobId]

    /**
     * 렌더 완료 직후 R2 에 결과물을 즉시 푸시. Cloud Run idle scale-down 시 로컬 outputFile
     * 손실 위험을 제거하고, `/download` 호출 시 R2 HEAD 만으로 즉시 presigned URL 발급되게 한다.
     *
     * 실패는 swallow — [com.vibi.bff.routes.respondDownload] 의 `uploadIfAbsent` 가
     * 다운로드 시점에 lazy 재시도. 잡 자체는 COMPLETED 유지.
     */
    private suspend fun maybeEagerPushToR2(job: RenderJob) {
        val store = objectStore ?: return
        runCatching {
            val ext = job.outputFile.extension.ifBlank { "mp4" }
            val fileName = "${job.jobId}.$ext"
            val key = ObjectKey.renderOutput(job.jobId, fileName)
            val ct = contentTypeForExtension(ext, ContentType.Application.OctetStream).toString()
            withContext(Dispatchers.IO) { store.uploadIfAbsent(job.outputFile, key, ct) }
        }.onFailure { e ->
            log.warn(
                "Eager R2 push failed (lazy fallback on download): jobId={} err={}",
                job.jobId, e.message,
            )
        }
    }

    fun submitRender(
        legacyVideoFile: File?,
        videoFiles: Map<String, File>,
        videoDurationMs: Long?,
        segments: List<Segment>?,
        bgmAudioFiles: Map<String, File> = emptyMap(),
        bgmClips: List<BgmClip> = emptyList(),
        separationDirectives: List<DirectiveWithStemFiles> = emptyList(),
        inputFilesToCleanup: List<File> = emptyList(),
        /** Phase 1.5: "video" (default mp4) | "audio" (m4a/AAC). audio mode
         * 는 분리 파이프라인 source 로 쓰일 결과만 만들어내므로 separation stems
         * 도 무시된다 (BGM mix 는 여전히 적용). */
        outputKind: String = "video",
        /** 최종 인코딩 품질 프로필 (audio bitrate). high/medium/low. audio 모드는 무시.
         * Video 는 stream-copy 라 quality 영향 없음 (per-segment trim 의 libx264 는 고정 설정). */
        quality: String = "medium",
        /** admin 대시보드 귀속용. JWT sub 에서 라우트가 추출. null 이면 분석 write skip
         * (테스트 등). 운영에선 RenderRoutes 가 항상 채운다. */
        userId: UUID? = null,
        /** 사용자가 올린 입력 영상의 총 길이 (segments trim 합산 또는 legacy videoDurationMs).
         * 대시보드의 "영상 길이" KPI 원본. */
        sourceDurationMs: Long = 0L,
    ): String {
        require(outputKind == "video" || outputKind == "audio") {
            "outputKind must be 'video' or 'audio' (got '$outputKind')"
        }
        val qualityProfile = QualityProfile.of(quality)
        val jobId = "render-${UUID.randomUUID()}"
        val outputExt = if (outputKind == "audio") "m4a" else "mp4"
        val outputFile = File(renderDir, "$jobId.$outputExt")
        val job = RenderJob(jobId = jobId, outputFile = outputFile, ownerUserId = userId)
        jobs[jobId] = job

        scope.launch {
            if (userId != null) {
                analytics?.insertRenderJob(jobId, userId, sourceDurationMs, "PROCESSING")
            }
            var process: Process? = null
            // While waiting for an ffmpeg permit, mark the job as PROCESSING with
            // progressReason="queued" so polling clients see meaningful state
            // instead of a stale PENDING. We acquire BEFORE entering the try
            // block so we can release symmetrically in finally.
            job.status = "PROCESSING"
            job.progress = 0
            job.progressReason = if (ffmpegSemaphore.availablePermits == 0) "queued" else null
            ffmpegSemaphore.acquire()
            job.progressReason = null
            try {
                if (outputKind == "audio") {
                    // Audio-only path — segments 우선, 없으면 legacy single video.
                    val audioSegments = segments
                        ?: legacyVideoFile?.let {
                            // legacy 단일 비디오를 단일 segment 로 매핑.
                            val dur = videoDurationMs
                                ?: throw IllegalArgumentException("videoDurationMs is required for legacy audio render")
                            require(dur > 0) { "videoDurationMs must be positive" }
                            listOf(
                                Segment(
                                    sourceFileKey = "video_0",
                                    order = 0,
                                    durationMs = dur,
                                ),
                            )
                        }
                        ?: throw IllegalArgumentException("audio render requires segments or legacy video")
                    val legacyVideoMap = if (segments == null && legacyVideoFile != null) {
                        mapOf("video_0" to legacyVideoFile)
                    } else videoFiles
                    runAudioOnlyRender(
                        job, audioSegments, legacyVideoMap,
                        bgmAudioFiles, bgmClips,
                    )
                } else if (segments != null) {
                    runMultiSegmentRender(
                        job, segments, videoFiles,
                        bgmAudioFiles, bgmClips,
                        separationDirectives, qualityProfile,
                    )
                } else {
                    // Legacy path: single video
                    val videoFile = legacyVideoFile
                        ?: videoFiles["video_0"]
                        ?: throw IllegalArgumentException("No video file provided")
                    val duration = videoDurationMs
                        ?: throw IllegalArgumentException("videoDurationMs is required for legacy render")
                    require(duration > 0) { "videoDurationMs must be positive" }

                    val command = buildFfmpegCommand(
                        videoFile, duration, outputFile,
                        bgmAudioFiles, bgmClips,
                        separationDirectives, qualityProfile,
                    )
                    log.info("Starting legacy ffmpeg render: jobId={}", jobId)
                    process = ProcessBuilder(command).redirectErrorStream(true).start()
                    val durationSec = duration / 1000.0
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            parseFfmpegProgress(line, durationSec)?.let { job.progress = it }
                        }
                    }
                    val exitCode = process.waitFor()
                    if (exitCode == 0 && outputFile.exists()) {
                        job.status = "COMPLETED"
                        job.progress = 100
                        // Phase 1: bump access time so the TTL window effectively
                        // starts at completion (not job creation) — long renders
                        // shouldn't shrink the downstream-reference window.
                        job.lastAccessedAt = System.currentTimeMillis()
                        log.info("Render completed: jobId={}, size={}", jobId, outputFile.length())
                        maybeEagerPushToR2(job)
                    } else {
                        job.status = "FAILED"
                        job.error = "ffmpeg exited with code $exitCode"
                        outputFile.delete()
                        log.error("Render failed: jobId={}, exitCode={}", jobId, exitCode)
                    }
                }
            } catch (e: Exception) {
                process?.destroyForcibly()
                outputFile.delete()
                job.status = "FAILED"
                job.error = e.message
                log.error("Render error: jobId={}", jobId, e)
            } finally {
                inputFilesToCleanup.forEach { it.delete() }
                ffmpegSemaphore.release()
                if (userId != null) {
                    val finalStatus = job.status
                    if (finalStatus == "COMPLETED" || finalStatus == "FAILED") {
                        analytics?.updateRenderJobStatus(jobId, finalStatus)
                    }
                }
            }
        }

        return jobId
    }

    // ── Audio-only pipeline (Phase 1.5) ──────────────────────────────────────
    //
    // 음원분리 파이프라인이 편집된 영상의 audio 만 필요로 한다는 관찰에서 출발.
    // 비디오 인코딩(libx264)을 통째로 건너뛰면 동일 segment list 기준으로 mp4
    // 렌더 대비 5~20x 빠르고 결과 사이즈도 ~1/30. 결과는 m4a (AAC LC).
    //
    // 처리되지 않는 input config (audio 와 무관해 의도적으로 무시):
    //   • separationDirectives (audio render 자체가 분리 단계 BEFORE 호출되므로 mix
    //     할 source 가 없음)
    //
    // 적용되는 것:
    //   • Segment.trimStartMs/trimEndMs · speedScale (atempo) · volumeScale
    //   • bgmClips (mix 대상으로 합류, adelay+volume 적용)
    private suspend fun runAudioOnlyRender(
        job: RenderJob,
        segments: List<Segment>,
        videoFiles: Map<String, File>,
        bgmAudioFiles: Map<String, File>,
        bgmClips: List<BgmClip>,
    ) {
        require(segments.isNotEmpty()) { "segments must not be empty" }
        val tempDir = File(renderDir, "tmp_${job.jobId}")
        tempDir.mkdirs()
        try {
            val sorted = segments.sortedBy { it.order }
            val totalSteps = sorted.size + 1 // per-segment + final mix
            val stepsDone = AtomicInteger(0)
            fun updateProgress() {
                job.progress = ((stepsDone.get().toDouble() / totalSteps) * 95).toInt().coerceIn(0, 95)
            }

            // Step 1: per-segment audio extract → m4a.
            val segmentSemaphore = Semaphore(MAX_PARALLEL_SEGMENTS)
            val processedFiles = coroutineScope {
                sorted.mapIndexed { idx, seg ->
                    async(Dispatchers.IO) {
                        segmentSemaphore.withPermit {
                            val vidFile = videoFiles[seg.sourceFileKey]
                                ?: throw IllegalArgumentException(
                                    "Video file not found: ${seg.sourceFileKey}"
                                )
                            val file = extractAudioSegment(vidFile, seg, tempDir, idx)
                            stepsDone.incrementAndGet()
                            updateProgress()
                            file
                        }
                    }
                }.awaitAll()
            }

            // Step 2: concat audio streams + mix bgmClips → final m4a.
            val totalDurationMs = sorted.sumOf { segmentOutputDurationMs(it) }
            val command = buildAudioConcatCommand(
                processedFiles, bgmAudioFiles, bgmClips,
                totalDurationMs, job.outputFile,
            )
            log.info("Starting audio-only ffmpeg render: jobId={}", job.jobId)
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                val durationSec = totalDurationMs / 1000.0
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parseFfmpegProgress(line, durationSec)?.let { pct ->
                            job.progress = 95 + (pct * 5 / 100)
                        }
                    }
                }
                val exitCode = proc.waitFor()
                if (exitCode == 0 && job.outputFile.exists()) {
                    job.status = "COMPLETED"
                    job.progress = 100
                    job.lastAccessedAt = System.currentTimeMillis()
                    log.info(
                        "Audio-only render completed: jobId={} size={}",
                        job.jobId, job.outputFile.length(),
                    )
                    maybeEagerPushToR2(job)
                } else {
                    job.status = "FAILED"
                    job.error = "ffmpeg audio-only step exited with code $exitCode"
                    job.outputFile.delete()
                    log.error(
                        "Audio-only render failed: jobId={} exitCode={}",
                        job.jobId, exitCode,
                    )
                }
            } catch (e: Exception) {
                proc.destroyForcibly()
                throw e
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun extractAudioSegment(
        vidFile: File,
        seg: Segment,
        tempDir: File,
        idx: Int,
    ): File {
        val outFile = File(tempDir, "seg_aud_$idx.m4a")
        val trimStart = (seg.trimStartMs ?: 0L) / 1000.0
        val trimEnd = (seg.trimEndMs?.takeIf { it > 0L } ?: seg.durationMs) / 1000.0
        val sourceDurationSec = (trimEnd - trimStart).coerceAtLeast(0.0)
        val speed = seg.speedScale
        val volume = seg.volumeScale
        // -t 는 setpts 후 길이라 speed<1 (slow-mo) 보정 위해 source / speed.
        val outputDurationSec = sourceDurationSec / speed

        // Guard: 20ms 미만 segment 는 primary extract 시도 자체를 건너뛰고 silent
        // fallback (≥20ms) 로 padding. 짧은 segment 는 STT/AAC 인코더 정밀도 한계로
        // 의미 없고, 실제 사용자 trim 거의 0 케이스에서 ffmpeg `-t` 파싱이 깨지면서
        // exit 234 로 전체 render 가 실패한 적 있음 (참고: 0.000670s 케이스).
        if (outputDurationSec < MIN_AUDIO_SEGMENT_SEC) {
            log.warn(
                "Audio segment {} duration too small ({}s) — using silent {}s padding",
                idx, outputDurationSec, MIN_AUDIO_SEGMENT_SEC,
            )
            return runSilentAudioFallback(outFile, outputDurationSec, idx)
        }

        val afParts = mutableListOf<String>()
        if (speed != 1.0f) afParts.add(atempoChain(speed))
        if (volume != 1.0f) afParts.add("volume=${volume}")
        val af = afParts.joinToString(",")

        // anullsrc 두 번째 입력은 video 트랙에 audio 가 없는 경우 fallback —
        // -map 0:a? 만으로 처리하면 audio 없는 입력에서 ffmpeg 실패. amerge 대신
        // -filter_complex 로 [0:a]||anullsrc 분기 처리는 복잡 → audio 가 없는
        // 영상은 사실상 trimVideoSegment 와 같은 문제이므로 anullsrc -shortest
        // 처리. 실제로 trim한 segment 가 audio 없으면 silent 결과가 나옴.
        val cmd = mutableListOf(
            "ffmpeg", "-y",
            "-ss", secondsToFfmpegArg(trimStart),
            "-i", vidFile.absolutePath,
            "-f", "lavfi", "-t", secondsToFfmpegArg(outputDurationSec),
            "-i", "anullsrc=r=44100:cl=stereo",
            "-t", secondsToFfmpegArg(outputDurationSec),
            "-vn",
        )
        if (af.isNotEmpty()) {
            cmd.addAll(listOf("-filter_complex", "[0:a]${af}[a]", "-map", "[a]"))
        } else {
            cmd.addAll(listOf("-map", "0:a?"))
        }
        cmd.addAll(listOf(
            "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2",
            outFile.absolutePath,
        ))

        val exit = runFfmpegBlocking(cmd)
        // audio 없는 영상이면 위 ffmpeg 가 실패. runSilentAudioFallback 으로 anullsrc
        // 만 써서 재시도해 silent 결과 보장.
        if (exit != 0 || !outFile.exists()) {
            log.warn(
                "Audio segment $idx primary extract failed (exit $exit), falling back to silent",
            )
            outFile.delete()
            return runSilentAudioFallback(outFile, outputDurationSec, idx)
        }
        return outFile
    }

    /**
     * Silent (anullsrc) m4a 를 [outFile] 에 작성. duration 은 [MIN_AUDIO_SEGMENT_SEC]
     * 이상으로 강제해 0/sub-ms 입력에서도 ffmpeg 가 안전히 동작.
     *
     * 호출자: [extractAudioSegment] 의 1) too-short guard, 2) primary extract 실패
     * fallback. 둘 다 같은 incoming `outFile` 에 결과를 채우고 동일한 정상 path 로
     * 빠져나가야 하므로 한 함수로 통합.
     */
    private suspend fun runSilentAudioFallback(outFile: File, requestedDurationSec: Double, idx: Int): File {
        val safeDurationSec = requestedDurationSec.coerceAtLeast(MIN_AUDIO_SEGMENT_SEC)
        val fallback = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-t", secondsToFfmpegArg(safeDurationSec),
            "-i", "anullsrc=r=44100:cl=stereo",
            "-c:a", "aac", "-b:a", "192k",
            outFile.absolutePath,
        )
        val exit = runFfmpegBlocking(fallback)
        if (exit != 0 || !outFile.exists()) {
            throw RuntimeException("Audio segment $idx extract failed (exit $exit)")
        }
        return outFile
    }

    internal fun buildAudioConcatCommand(
        segmentFiles: List<File>,
        bgmAudioFiles: Map<String, File>,
        bgmClips: List<BgmClip>,
        totalDurationMs: Long,
        outputFile: File,
    ): List<String> {
        val cmd = mutableListOf("ffmpeg", "-y")
        // 모든 segment audio 를 -i 로 추가 → concat filter.
        for (f in segmentFiles) {
            cmd.addAll(listOf("-i", f.absolutePath))
        }
        var inputIdx = segmentFiles.size
        val bgmInputIndices = mutableListOf<Int>()
        for (clip in bgmClips) {
            val f = bgmAudioFiles[clip.audioFileKey]
                ?: throw IllegalArgumentException(
                    "BGM audio file not found: ${clip.audioFileKey}"
                )
            cmd.addAll(listOf("-i", f.absolutePath))
            bgmInputIndices.add(inputIdx++)
        }

        val filters = mutableListOf<String>()
        // 1) concat segment audios — concat filter (v=0,a=1) 로 PTS 재계산.
        val concatRefs = segmentFiles.indices.joinToString("") { "[${it}:a]" }
        filters.add("${concatRefs}concat=n=${segmentFiles.size}:v=0:a=1[base]")

        val finalLabel: String = if (bgmClips.isEmpty()) {
            // base 만 있으면 그대로 출력.
            "[base]"
        } else {
            // 각 bgm clip 에 adelay+volume 적용 후 base 와 amix.
            val mixInputs = mutableListOf("[base]")
            for ((i, clip) in bgmClips.withIndex()) {
                filters.add(
                    audioClipFilter(
                        inputIdx = bgmInputIndices[i],
                        startMs = clip.startMs,
                        volume = clip.volume,
                        label = "bgm$i",
                        sourceTrimStartMs = clip.sourceTrimStartMs,
                        sourceTrimEndMs = clip.sourceTrimEndMs,
                    )
                )
                mixInputs.add("[bgm$i]")
            }
            // normalize=0: amix default normalize=1 은 silent input 도 N 으로 카운트해 모든 input 을
            // 1/N 으로 나눠 음량이 작아짐. base + BGM 합산이 의도된 음량으로 나오려면 0 필수.
            // alimiter: 합산이 천장(±1.0)을 초과할 때만 부드럽게 누름 — 평상시 통과, peak 만 squash.
            // limit=0.95 로 -0.4dB 헤드룸. BGM volume 슬라이더 0..2 boost 케이스의 clipping 안전망.
            filters.add(
                "${mixInputs.joinToString("")}amix=inputs=${mixInputs.size}:" +
                    "duration=first:dropout_transition=0:normalize=0[amix_out]"
            )
            filters.add("[amix_out]alimiter=limit=0.95:attack=5:release=50[aout]")
            "[aout]"
        }

        cmd.addAll(listOf("-filter_complex", filters.joinToString(";")))
        cmd.addAll(listOf(
            "-map", finalLabel,
            "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2",
            "-movflags", "+faststart",
            "-t", secondsToFfmpegArg(totalDurationMs / 1000.0),
            "-progress", "pipe:1",
            outputFile.absolutePath,
        ))
        return cmd
    }

    // ── Multi-segment pipeline (Task 3b) ─────────────────────────────────────

    private suspend fun runMultiSegmentRender(
        job: RenderJob,
        segments: List<Segment>,
        videoFiles: Map<String, File>,
        bgmAudioFiles: Map<String, File>,
        bgmClips: List<BgmClip>,
        separationDirectives: List<DirectiveWithStemFiles>,
        qualityProfile: QualityProfile,
    ) {
        require(segments.isNotEmpty()) { "segments must not be empty" }
        val tempDir = File(renderDir, "tmp_${job.jobId}")
        tempDir.mkdirs()
        try {
            val sorted = segments.sortedBy { it.order }

            val totalSteps = sorted.size + 2 // per-segment + concat + final
            val stepsDone = AtomicInteger(0)

            fun updateProgress() {
                job.progress = ((stepsDone.get().toDouble() / totalSteps) * 95).toInt().coerceIn(0, 95)
            }

            // Step 1 & 2: process each segment — bounded parallel (Semaphore(2))
            // caps concurrent ffmpeg processes so libx264 doesn't thrash on
            // low-core hosts while still parallelising cheap IMAGE segments.
            val segmentSemaphore = Semaphore(MAX_PARALLEL_SEGMENTS)
            val processedFiles = coroutineScope {
                sorted.mapIndexed { idx, seg ->
                    async(Dispatchers.IO) {
                        segmentSemaphore.withPermit {
                            val vidFile = videoFiles[seg.sourceFileKey]
                                ?: throw IllegalArgumentException("Video file not found: ${seg.sourceFileKey}")
                            val file = trimVideoSegment(vidFile, seg, tempDir, idx)
                            stepsDone.incrementAndGet()
                            updateProgress()
                            file
                        }
                    }
                }.awaitAll()
            }

            // Step 3: concat
            val concatFile = File(tempDir, "concat.mp4")
            concatSegments(processedFiles, concatFile)
            stepsDone.incrementAndGet()
            updateProgress()

            // Step 4: final mix (base audio mute window + BGM + separation stems).
            // Use output-length semantics so speed-scaled VIDEO segments contribute
            // their stretched/compressed length, matching what the final -t expects
            // after concat.
            val totalDurationMs = sorted.sumOf { segmentOutputDurationMs(it) }
            val command = buildFfmpegCommand(
                concatFile, totalDurationMs, job.outputFile,
                bgmAudioFiles, bgmClips,
                separationDirectives, qualityProfile,
            )
            log.info("Starting final ffmpeg render: jobId={}", job.jobId)
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                val durationSec = totalDurationMs / 1000.0
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parseFfmpegProgress(line, durationSec)?.let { pct ->
                            job.progress = 95 + (pct * 5 / 100)
                        }
                    }
                }
                val exitCode = proc.waitFor()
                if (exitCode == 0 && job.outputFile.exists()) {
                    job.status = "COMPLETED"
                    job.progress = 100
                    // Phase 1: see submitRender — start the TTL window at completion.
                    job.lastAccessedAt = System.currentTimeMillis()
                    log.info("Multi-segment render completed: jobId={}", job.jobId)
                    maybeEagerPushToR2(job)
                } else {
                    job.status = "FAILED"
                    job.error = "ffmpeg final step exited with code $exitCode"
                    job.outputFile.delete()
                    log.error("Multi-segment render failed: jobId={}, exitCode={}", job.jobId, exitCode)
                }
            } catch (e: Exception) {
                proc.destroyForcibly()
                throw e
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun trimVideoSegment(
        vidFile: File,
        seg: Segment,
        tempDir: File,
        idx: Int,
    ): File {
        val outFile = File(tempDir, "seg_vid_$idx.mp4")
        val trimStart = (seg.trimStartMs ?: 0L) / 1000.0
        // Mobile sends 0L as "no trim" sentinel — treat both null and <=0 as full duration.
        val trimEnd = (seg.trimEndMs?.takeIf { it > 0L } ?: seg.durationMs) / 1000.0
        val sourceDurationSec = trimEnd - trimStart
        val speed = seg.speedScale
        val volume = seg.volumeScale
        // -t is an output-side option: it caps the duration AFTER setpts
        // rescales PTS. Without this divide, speed < 1 (slow-mo) would be
        // truncated to the source window length instead of the stretched one.
        val outputDurationSec = sourceDurationSec / speed

        val afParts = mutableListOf<String>()
        if (speed != 1.0f) afParts.add(atempoChain(speed))
        if (volume != 1.0f) afParts.add("volume=${volume}")
        val af = afParts.joinToString(",")

        // -ss before -i = fast input-side seek; -t after inputs limits output
        // duration (post-setpts). anullsrc ensures an audio stream even if
        // the source has none, keeping all segments stream-compatible for
        // concat demuxer.
        val cmd = mutableListOf(
            "ffmpeg", "-y",
            "-ss", secondsToFfmpegArg(trimStart),
            "-i", vidFile.absolutePath,
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
            "-t", secondsToFfmpegArg(outputDurationSec),
        )
        if (speed != 1.0f) {
            cmd.addAll(listOf("-vf", "setpts=PTS/${speed}"))
        }
        if (af.isNotEmpty()) {
            cmd.addAll(listOf("-af", af))
        }
        cmd.addAll(listOf(
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "192k",
            outFile.absolutePath,
        ))

        val exit = runFfmpegBlocking(cmd)
        if (exit != 0 || !outFile.exists()) throw RuntimeException("Video segment $idx trim failed (exit $exit)")
        return outFile
    }

    // atempo is clamped to 0.5..2.0 per ffmpeg docs; chain filters to hit
    // speeds outside that range (e.g. 4x = atempo=2,atempo=2). Callers
    // should validate speed > 0 upstream (Segment.init enforces this), but
    // double-check here so a bad value never loops forever.
    internal fun atempoChain(speed: Float): String {
        require(speed > 0f) { "atempo speed must be > 0 (got $speed)" }
        if (speed in 0.5f..2.0f) return "atempo=${speed}"
        val parts = mutableListOf<Float>()
        var remaining = speed
        while (remaining > 2.0f) { parts.add(2.0f); remaining /= 2.0f }
        while (remaining < 0.5f) { parts.add(0.5f); remaining *= 2.0f }
        parts.add(remaining)
        return parts.joinToString(",") { "atempo=${it}" }
    }

    /**
     * BGM clip 의 ffmpeg audio filter — sourceTrimStart/End 있으면 atrim+asetpts 로 sub-range 만
     * 추출 후 adelay+volume. 모바일의 BgmTrimSheet 결과 (영상보다 긴 음원의 잘린 구간) 를 그대로
     * 렌더에 반영.
     */
    private fun audioClipFilter(
        inputIdx: Int,
        startMs: Long,
        volume: Float,
        label: String,
        sourceTrimStartMs: Long = 0L,
        sourceTrimEndMs: Long = 0L,
    ): String {
        val trim = if (sourceTrimStartMs > 0L || sourceTrimEndMs > 0L) {
            val startArg = secondsToFfmpegArg(sourceTrimStartMs / 1000.0)
            val endClause = if (sourceTrimEndMs > 0L) ":${secondsToFfmpegArg(sourceTrimEndMs / 1000.0)}" else ""
            "atrim=${startArg}${endClause},asetpts=PTS-STARTPTS,"
        } else ""
        return "[$inputIdx:a]${trim}adelay=${startMs}|${startMs},volume=${volume}[$label]"
    }

    private fun segmentOutputDurationMs(seg: Segment): Long {
        val trimStart = seg.trimStartMs ?: 0L
        // 모바일이 0L 을 "no trim" sentinel 로 보내므로 null 외에 <=0 도 durationMs 로 fallback.
        // 누락 시 모든 세그먼트의 출력 길이가 0 으로 계산됨.
        val trimEnd = seg.trimEndMs?.takeIf { it > 0L } ?: seg.durationMs
        return ((trimEnd - trimStart) / seg.speedScale).toLong().coerceAtLeast(0L)
    }

    companion object {
        /** Default ffmpeg concurrency = max(1, CPU count / 2). Override via
         * RENDER_MAX_CONCURRENT env wired in Application.kt. libx264 uses all
         * cores per ffmpeg process, so anything past CPU/2 typically thrashes. */
        fun defaultMaxConcurrent(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }

    private suspend fun concatSegments(segments: List<File>, output: File) {
        val concatList = File(output.parentFile, "concat_list.txt")
        concatList.writeText(segments.joinToString("\n") { "file '${it.absolutePath.replace("\\", "/")}'" })
        val cmd = listOf(
            "ffmpeg", "-y",
            "-f", "concat", "-safe", "0",
            "-i", concatList.absolutePath,
            "-c", "copy",
            output.absolutePath,
        )
        val exit = runFfmpegBlocking(cmd)
        concatList.delete()
        if (exit != 0 || !output.exists()) throw RuntimeException("Concat failed (exit $exit)")
    }

    private suspend fun runFfmpegBlocking(command: List<String>): Int {
        // Drains stdout on a daemon thread to avoid OS pipe-buffer deadlock
        // when ffmpeg fills the ~64KB pipe before the parent reads.
        return try {
            FfmpegRunner.run(command, "ffmpeg step", timeoutMinutes = 30)
            0
        } catch (e: FfmpegProcessException) {
            log.error("ffmpeg step failed exit={}: {}", e.exitCode, e.message)
            e.exitCode
        } catch (e: Exception) {
            log.error("ffmpeg step failed: {}", e.message)
            -1
        }
    }

    // ── FFmpeg command builder ────────────────────────────────────────────────

    internal fun buildFfmpegCommand(
        videoFile: File,
        videoDurationMs: Long,
        outputFile: File,
        bgmAudioFiles: Map<String, File> = emptyMap(),
        bgmClips: List<BgmClip> = emptyList(),
        separationDirectives: List<DirectiveWithStemFiles> = emptyList(),
        qualityProfile: QualityProfile = QualityProfile.of("medium"),
    ): List<String> {
        val cmd = mutableListOf("ffmpeg", "-y")
        cmd.addAll(listOf("-i", videoFile.absolutePath))

        var inputIdx = 1

        // BGM inputs (natural end, no loop)
        val bgmInputIndices = mutableListOf<Int>()
        for (clip in bgmClips) {
            val f = bgmAudioFiles[clip.audioFileKey]
                ?: throw IllegalArgumentException("BGM audio file not found: ${clip.audioFileKey}")
            cmd.addAll(listOf("-i", f.absolutePath))
            bgmInputIndices.add(inputIdx++)
        }

        // Separation directive stems — each directive carries N stems whose
        // audioUrl 들이 라우트 단계에서 로컬 파일로 해석되어 들어왔다. 각 stem 을
        // 별개 -i 로 추가, filter graph 에서 atrim+asetpts+adelay+volume 으로
        // directive range 에 정확히 박는다.
        val stemInputIndices: List<List<Int>> = separationDirectives.map { directive ->
            directive.stems.map { stem ->
                cmd.addAll(listOf("-i", stem.file.absolutePath))
                inputIdx++
            }
        }

        val filters = mutableListOf<String>()

        // muteOriginalSegmentAudio=true 인 directive 들의 range 동안 base audio 를
        // 0 으로. enable= expression 안에 OR 로 모든 mute window 를 합쳐 한 번의
        // volume 필터로 처리.
        val muteWindows = separationDirectives.filter { it.muteOriginalSegmentAudio }
        val baseAudioRef: String = if (muteWindows.isEmpty()) {
            "[0:a]"
        } else {
            // Invariant: rangeStartMs/rangeEndMs are Long (ms) → / 1000.0 always yields
            // multiples of 0.001 → never enters scientific notation (< 1e-3) range.
            val expr = muteWindows.joinToString("+") {
                "between(t,${it.rangeStartMs / 1000.0},${it.rangeEndMs / 1000.0})"
            }
            filters.add("[0:a]volume=enable='gt($expr,0)':volume=0[base_muted]")
            "[base_muted]"
        }

        val hasAnyAudio = bgmClips.isNotEmpty() ||
            separationDirectives.any { it.stems.isNotEmpty() } ||
            muteWindows.isNotEmpty()
        if (!hasAnyAudio) {
            filters.add("[0:a]anull[aout]")
        } else {
            val mixInputs = mutableListOf(baseAudioRef)
            for ((i, clip) in bgmClips.withIndex()) {
                filters.add(
                    audioClipFilter(
                        inputIdx = bgmInputIndices[i],
                        startMs = clip.startMs,
                        volume = clip.volume,
                        label = "bgm$i",
                        sourceTrimStartMs = clip.sourceTrimStartMs,
                        sourceTrimEndMs = clip.sourceTrimEndMs,
                    )
                )
                mixInputs.add("[bgm$i]")
            }
            // Directive stems: directive 마다 stem 들을 atrim 으로 [sourceOffset, sourceOffset+range]
            // 구간만 잘라내고, asetpts 로 PTS 리셋, adelay 로 rangeStartMs 만큼 밀고, volume.
            // sourceOffsetMs 가 0 이면 stem 처음부터 (기본), >0 이면 stem audio 의 중간부터 — 모바일
            // 클라이언트가 split 한 directive 의 뒤쪽 piece 에 해당.
            for ((dIdx, directive) in separationDirectives.withIndex()) {
                val rangeMs = (directive.rangeEndMs - directive.rangeStartMs).coerceAtLeast(0L)
                val trimStartSec = directive.sourceOffsetMs / 1000.0
                val trimEndSec = (directive.sourceOffsetMs + rangeMs) / 1000.0
                for ((sIdx, stem) in directive.stems.withIndex()) {
                    val inputIndex = stemInputIndices[dIdx][sIdx]
                    val label = "stem_${dIdx}_${sIdx}"
                    filters.add(
                        "[$inputIndex:a]atrim=$trimStartSec:$trimEndSec,asetpts=PTS-STARTPTS," +
                            "adelay=${directive.rangeStartMs}|${directive.rangeStartMs}," +
                            "volume=${stem.volume}[$label]"
                    )
                    mixInputs.add("[$label]")
                }
            }
            if (mixInputs.size == 1) {
                filters.add("${mixInputs[0]}anull[aout]")
            } else {
                // normalize=0: separation 구간에서 [base_muted] 는 silence 지만 amix default normalize=1
                // 은 그 input 까지 N 으로 카운트해 stem 들을 1/(N+1) 로 나눠버림. base 가 mute 됐는데도
                // stems 가 작아져 사용자가 "분리된 stem 이 원본보다 작게 들림" 으로 체감하는 직접 원인.
                // alimiter: stem.volume 슬라이더 boost / BGM 겹침 / 분리 모델 over-estimate 잔여 오차 등의
                // 합산이 천장을 넘을 때 부드럽게 누름. -0.4dB 헤드룸 (limit=0.95).
                filters.add("${mixInputs.joinToString("")}amix=inputs=${mixInputs.size}:duration=first:dropout_transition=0:normalize=0[amix_out]")
                filters.add("[amix_out]alimiter=limit=0.95:attack=5:release=50[aout]")
            }
        }

        // Video stream 은 이 패스에서 한 비트도 변형 없음 (BGM/separation/mute 모두 audio-only).
        // -c:v copy 로 libx264 재인코딩을 통째로 스킵 → 매 render 당 1회 패스 절감.
        // qualityProfile.preset/crf 는 이 패스에서 무시 (audioBitrate 만 audio 측에서 사용).
        cmd.addAll(listOf("-filter_complex", filters.joinToString(";")))
        cmd.addAll(listOf(
            "-map", "0:v",
            "-map", "[aout]",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", qualityProfile.audioBitrate,
            "-movflags", "+faststart",
            "-t", secondsToFfmpegArg(videoDurationMs / 1000.0),
            "-progress", "pipe:1",
            outputFile.absolutePath,
        ))

        return cmd
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    internal fun parseFfmpegProgress(line: String, totalDurationSec: Double): Int? {
        if (!line.startsWith("out_time_us=")) return null
        if (totalDurationSec <= 0) return null
        val timeUs = line.substringAfter("out_time_us=").toLongOrNull() ?: return null
        val timeSec = timeUs / 1_000_000.0
        return ((timeSec / totalDurationSec) * 100).toInt().coerceIn(0, 99)
    }

    private fun cleanupExpiredJobs() {
        val now = System.currentTimeMillis()
        jobs.entries
            .filter { (_, job) ->
                (job.status == "COMPLETED" || job.status == "FAILED") &&
                    (now - job.lastAccessedAt) > jobTtlMs
            }
            .forEach { (id, job) ->
                val deleted = synchronized(job) {
                    if ((now - job.lastAccessedAt) > jobTtlMs) {
                        job.outputFile.delete()
                        jobs.remove(id)
                        true
                    } else false
                }
                if (deleted) log.info("Cleaned up expired render job: {}", id)
            }
    }
}
