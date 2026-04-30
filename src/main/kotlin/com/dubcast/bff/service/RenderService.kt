package com.dubcast.bff.service

import com.dubcast.bff.model.BgmClip
import com.dubcast.bff.model.DubClip
import com.dubcast.bff.model.FrameConfig
import com.dubcast.bff.model.ImageClip
import com.dubcast.bff.model.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RenderJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var error: String? = null,
    val outputFile: File,
    val createdAt: Long = System.currentTimeMillis(),
    /** "queued" while waiting for an ffmpeg permit; null once acquired or done. */
    @Volatile var progressReason: String? = null,
)

/** 한 separationDirective 의 ffmpeg 입력용 형태. selections 의 audioUrl 은 라우트 단계에서
 * 로컬 파일로 해석되어 본 구조체로 들어온다. range 는 mute 윈도우 + adelay 의 base 이다. */
data class DirectiveStem(val file: File, val volume: Float)
data class DirectiveWithStemFiles(
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val muteOriginalSegmentAudio: Boolean,
    val stems: List<DirectiveStem>,
)

private const val MAX_PARALLEL_SEGMENTS = 2

class RenderService(
    private val renderDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val jobTtlMs: Long = 3600_000,
    /** Hard cap on concurrent top-level render jobs that can spawn ffmpeg.
     * 5+ variant fan-out otherwise saturates CPU + memory. submitRender
     * returns immediately; the launched coroutine acquires the permit
     * before doing any ffmpeg work, so queued jobs report status=PROCESSING
     * with progressReason="queued" until a slot frees up. */
    maxConcurrentRenders: Int = defaultMaxConcurrent(),
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

    fun submitRender(
        legacyVideoFile: File?,
        videoFiles: Map<String, File>,
        segmentImageFiles: Map<String, File>,
        audioFiles: Map<String, File>,
        imageFiles: Map<String, File>,
        subtitlesFile: File?,
        dubClips: List<DubClip>,
        imageClips: List<ImageClip>,
        videoDurationMs: Long?,
        segments: List<Segment>?,
        bgmAudioFiles: Map<String, File> = emptyMap(),
        bgmClips: List<BgmClip> = emptyList(),
        frame: FrameConfig? = null,
        audioOverrideFile: File? = null,
        separationDirectives: List<DirectiveWithStemFiles> = emptyList(),
        inputFilesToCleanup: List<File> = emptyList(),
    ): String {
        val jobId = "render-${UUID.randomUUID()}"
        val outputFile = File(renderDir, "$jobId.mp4")
        val job = RenderJob(jobId = jobId, outputFile = outputFile)
        jobs[jobId] = job

        scope.launch {
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
                if (segments != null) {
                    runMultiSegmentRender(
                        job, segments, videoFiles, segmentImageFiles,
                        audioFiles, imageFiles, subtitlesFile, dubClips, imageClips,
                        frame, bgmAudioFiles, bgmClips, audioOverrideFile,
                        separationDirectives,
                    )
                } else {
                    // Legacy path: single video
                    val videoFile = legacyVideoFile
                        ?: videoFiles["video_0"]
                        ?: throw IllegalArgumentException("No video file provided")
                    val duration = videoDurationMs
                        ?: throw IllegalArgumentException("videoDurationMs is required for legacy render")
                    require(duration > 0) { "videoDurationMs must be positive" }

                    val (outW, outH) = if (imageClips.isNotEmpty()) getVideoSize(videoFile) else Pair(0, 0)
                    val command = buildFfmpegCommand(
                        videoFile, audioFiles, imageFiles, subtitlesFile,
                        dubClips, imageClips, duration, outputFile, outW, outH,
                        bgmAudioFiles, bgmClips, audioOverrideFile,
                        separationDirectives,
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
                        log.info("Render completed: jobId={}, size={}", jobId, outputFile.length())
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
            }
        }

        return jobId
    }

    // ── Multi-segment pipeline (Task 3b) ─────────────────────────────────────

    private fun runMultiSegmentRender(
        job: RenderJob,
        segments: List<Segment>,
        videoFiles: Map<String, File>,
        segmentImageFiles: Map<String, File>,
        audioFiles: Map<String, File>,
        imageFiles: Map<String, File>,
        subtitlesFile: File?,
        dubClips: List<DubClip>,
        imageClips: List<ImageClip>,
        frame: FrameConfig?,
        bgmAudioFiles: Map<String, File>,
        bgmClips: List<BgmClip>,
        audioOverrideFile: File?,
        separationDirectives: List<DirectiveWithStemFiles>,
    ) {
        require(segments.isNotEmpty()) { "segments must not be empty" }
        val tempDir = File(renderDir, "tmp_${job.jobId}")
        tempDir.mkdirs()
        try {
            val sorted = segments.sortedBy { it.order }
            val firstSeg = sorted.first()
            val outW = frame?.width ?: firstSeg.width ?: 1920
            val outH = frame?.height ?: firstSeg.height ?: 1080
            val bgColor = ffmpegColor(frame?.backgroundColorHex ?: "#000000")

            val totalSteps = sorted.size + 2 // per-segment + concat + final
            val stepsDone = AtomicInteger(0)

            fun updateProgress() {
                job.progress = ((stepsDone.get().toDouble() / totalSteps) * 95).toInt().coerceIn(0, 95)
            }

            // Step 1 & 2: process each segment — bounded parallel (Semaphore(2))
            // caps concurrent ffmpeg processes so libx264 doesn't thrash on
            // low-core hosts while still parallelising cheap IMAGE segments.
            val segmentSemaphore = Semaphore(MAX_PARALLEL_SEGMENTS)
            val processedFiles = runBlocking {
                coroutineScope {
                    sorted.mapIndexed { idx, seg ->
                        async(Dispatchers.IO) {
                            segmentSemaphore.withPermit {
                                val file = when (seg.type) {
                                    "IMAGE" -> {
                                        val imgFile = segmentImageFiles[seg.sourceFileKey]
                                            ?: throw IllegalArgumentException("Segment image not found: ${seg.sourceFileKey}")
                                        convertImageSegment(imgFile, seg, outW, outH, bgColor, tempDir, idx)
                                    }
                                    "VIDEO" -> {
                                        val vidFile = videoFiles[seg.sourceFileKey]
                                            ?: throw IllegalArgumentException("Video file not found: ${seg.sourceFileKey}")
                                        trimVideoSegment(vidFile, seg, outW, outH, bgColor, tempDir, idx)
                                    }
                                    else -> throw IllegalArgumentException("Unknown segment type: ${seg.type}")
                                }
                                stepsDone.incrementAndGet()
                                updateProgress()
                                file
                            }
                        }
                    }.awaitAll()
                }
            }

            // Step 3: concat
            val concatFile = File(tempDir, "concat.mp4")
            concatSegments(processedFiles, concatFile)
            stepsDone.incrementAndGet()
            updateProgress()

            // Step 4 & 5: stickers + dub audio + subtitles
            // Use output-length semantics so speed-scaled VIDEO segments
            // contribute their stretched/compressed length, matching what
            // the final -t expects after concat.
            val totalDurationMs = sorted.sumOf { segmentOutputDurationMs(it) }
            val command = buildFfmpegCommand(
                concatFile, audioFiles, imageFiles, subtitlesFile,
                dubClips, imageClips, totalDurationMs, job.outputFile, outW, outH,
                bgmAudioFiles, bgmClips, audioOverrideFile,
                separationDirectives,
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
                    log.info("Multi-segment render completed: jobId={}", job.jobId)
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

    private fun convertImageSegment(
        imgFile: File,
        seg: Segment,
        outW: Int,
        outH: Int,
        bgColor: String,
        tempDir: File,
        idx: Int,
    ): File {
        val outFile = File(tempDir, "seg_img_$idx.mp4")
        val durationSec = seg.durationMs / 1000.0
        val wPct = seg.imageWidthPct ?: 100f
        val hPct = seg.imageHeightPct ?: 100f
        val scaleW = ((wPct / 100f) * outW).toInt().coerceAtLeast(2)
        val scaleH = ((hPct / 100f) * outH).toInt().coerceAtLeast(2)

        val vf = "scale=${scaleW}:${scaleH}:force_original_aspect_ratio=decrease," +
                 "pad=${outW}:${outH}:(ow-iw)/2:(oh-ih)/2:color=${bgColor}," +
                 "setsar=1"

        val cmd = listOf(
            "ffmpeg", "-y",
            "-loop", "1", "-i", imgFile.absolutePath,
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
            "-vf", vf,
            "-c:v", "libx264", "-tune", "stillimage", "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "128k",
            "-t", durationSec.toString(),
            outFile.absolutePath,
        )
        val exit = runFfmpegBlocking(cmd)
        if (exit != 0 || !outFile.exists()) throw RuntimeException("Image segment $idx conversion failed (exit $exit)")
        return outFile
    }

    private fun trimVideoSegment(
        vidFile: File,
        seg: Segment,
        outW: Int,
        outH: Int,
        bgColor: String,
        tempDir: File,
        idx: Int,
    ): File {
        val outFile = File(tempDir, "seg_vid_$idx.mp4")
        val trimStart = (seg.trimStartMs ?: 0L) / 1000.0
        val trimEnd = (seg.trimEndMs ?: seg.durationMs) / 1000.0
        val sourceDurationSec = trimEnd - trimStart
        val speed = seg.speedScale
        val volume = seg.volumeScale
        // -t is an output-side option: it caps the duration AFTER setpts
        // rescales PTS. Without this divide, speed < 1 (slow-mo) would be
        // truncated to the source window length instead of the stretched one.
        val outputDurationSec = sourceDurationSec / speed

        val vfBase = "scale=${outW}:${outH}:force_original_aspect_ratio=decrease," +
                     "pad=${outW}:${outH}:(ow-iw)/2:(oh-ih)/2:color=${bgColor}," +
                     "setsar=1"
        val vf = if (speed != 1.0f) "$vfBase,setpts=PTS/${speed}" else vfBase

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
            "-ss", trimStart.toString(),
            "-i", vidFile.absolutePath,
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
            "-t", outputDurationSec.toString(),
            "-vf", vf,
        )
        if (af.isNotEmpty()) {
            cmd.addAll(listOf("-af", af))
        }
        cmd.addAll(listOf(
            "-c:v", "libx264", "-preset", "fast", "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "192k",
            outFile.absolutePath,
        ))

        val exit = runFfmpegBlocking(cmd)
        if (exit != 0 || !outFile.exists()) throw RuntimeException("Video segment $idx trim failed (exit $exit)")
        return outFile
    }

    // ffmpeg color syntax accepts "#RRGGBB" or "0xRRGGBB". Validate against
    // a strict hex pattern so the string can't smuggle commas or quotes into
    // a filter_complex expression (filter injection); fall back to opaque
    // black when the input is malformed.
    internal fun ffmpegColor(hex: String): String {
        val trimmed = hex.trim()
        val match = HEX_COLOR_REGEX.matchEntire(trimmed) ?: return "0x000000"
        return "0x" + match.groupValues[1]
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

    private fun audioClipFilter(inputIdx: Int, startMs: Long, volume: Float, label: String) =
        "[$inputIdx:a]adelay=${startMs}|${startMs},volume=${volume}[$label]"

    private fun segmentOutputDurationMs(seg: Segment): Long = when (seg.type) {
        "VIDEO" -> {
            val trimStart = seg.trimStartMs ?: 0L
            val trimEnd = seg.trimEndMs ?: seg.durationMs
            ((trimEnd - trimStart) / seg.speedScale).toLong().coerceAtLeast(0L)
        }
        else -> seg.durationMs
    }

    companion object {
        private val HEX_COLOR_REGEX = Regex("^#?([0-9a-fA-F]{6})$")

        /** Default ffmpeg concurrency = max(1, CPU count / 2). Override via
         * RENDER_MAX_CONCURRENT env wired in Application.kt. libx264 uses all
         * cores per ffmpeg process, so anything past CPU/2 typically thrashes. */
        fun defaultMaxConcurrent(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }

    private fun concatSegments(segments: List<File>, output: File) {
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

    private fun runFfmpegBlocking(command: List<String>): Int {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) log.error("ffmpeg step failed (exit {}): {}", exit, out.takeLast(500))
        return exit
    }

    // ── FFmpeg command builder ────────────────────────────────────────────────

    internal fun buildFfmpegCommand(
        videoFile: File,
        audioFiles: Map<String, File>,
        imageFiles: Map<String, File>,
        subtitlesFile: File?,
        dubClips: List<DubClip>,
        imageClips: List<ImageClip>,
        videoDurationMs: Long,
        outputFile: File,
        outWidth: Int = 0,
        outHeight: Int = 0,
        bgmAudioFiles: Map<String, File> = emptyMap(),
        bgmClips: List<BgmClip> = emptyList(),
        audioOverrideFile: File? = null,
        separationDirectives: List<DirectiveWithStemFiles> = emptyList(),
    ): List<String> {
        val cmd = mutableListOf("ffmpeg", "-y")
        cmd.addAll(listOf("-i", videoFile.absolutePath))

        // Audio override (Phase 3 auto-dub) becomes the new "base" audio
        // track. We still consume the source video's stream slot, but its
        // [0:a] is dropped from the mix in favor of [overrideIdx:a].
        var inputIdx = 1
        var overrideAudioIdx: Int? = null
        if (audioOverrideFile != null) {
            cmd.addAll(listOf("-i", audioOverrideFile.absolutePath))
            overrideAudioIdx = inputIdx++
        }

        // Audio inputs
        val clipInputIndices = mutableListOf<Int>()
        for (clip in dubClips) {
            val f = audioFiles[clip.audioFileKey]
                ?: throw IllegalArgumentException("Audio file not found: ${clip.audioFileKey}")
            cmd.addAll(listOf("-i", f.absolutePath))
            clipInputIndices.add(inputIdx++)
        }

        // BGM inputs (separate from dubClips; natural end, no loop)
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

        // Sticker image inputs (deduplicated, order preserved)
        val imageInputIndices = mutableMapOf<String, Int>()
        for (clip in imageClips) {
            if (clip.imageFileKey !in imageInputIndices) {
                val f = imageFiles[clip.imageFileKey]
                    ?: throw IllegalArgumentException("Image file not found: ${clip.imageFileKey}")
                cmd.addAll(listOf("-i", f.absolutePath))
                imageInputIndices[clip.imageFileKey] = inputIdx++
            }
        }

        // Determine output resolution for sticker pixel math
        val (outW, outH) = if (imageClips.isNotEmpty()) {
            if (outWidth > 0 && outHeight > 0) Pair(outWidth, outHeight)
            else getVideoSize(videoFile)
        } else Pair(outWidth, outHeight)

        val filters = mutableListOf<String>()

        // Audio mixing: pass-through when there's nothing to mix; otherwise
        // build adelay+volume chains for each dub/bgm clip and amix them
        // together with the base audio. amix duration=first caps output to
        // the first input's length (the video's, when present), so BGM
        // shorter than the video ends naturally and longer BGM is truncated.
        // Auto-dub: when audioOverrideFile is set, the override REPLACES
        // [0:a] as the base track — the source video's audio is dropped.
        val initialBaseRef = if (overrideAudioIdx != null) "[$overrideAudioIdx:a]" else "[0:a]"

        // muteOriginalSegmentAudio=true 인 directive 들의 range 동안 base audio 를
        // 0 으로. enable= expression 안에 OR 로 모든 mute window 를 합쳐 한 번의
        // volume 필터로 처리. (분리된 enable= 윈도우끼리 chain 해도 의미는 같지만
        // 단일 expression 이 더 깔끔하고 race 없음.)
        val muteWindows = separationDirectives.filter { it.muteOriginalSegmentAudio }
        val baseAudioRef: String = if (muteWindows.isEmpty()) {
            initialBaseRef
        } else {
            val expr = muteWindows.joinToString("+") {
                "between(t,${it.rangeStartMs / 1000.0},${it.rangeEndMs / 1000.0})"
            }
            // volume= enable= 'expr' 에서 expr 가 nonzero (true) 면 volume 적용.
            // 여러 윈도우를 OR 로 합치려면 + 로 충분 (between 결과 0/1 이라 합 ≥ 1 이면
            // nonzero → mute). 'gt(...,0)' 로 명시해도 동일.
            filters.add("${initialBaseRef}volume=enable='gt($expr,0)':volume=0[base_muted]")
            "[base_muted]"
        }

        val hasAnyAudio = dubClips.isNotEmpty() || bgmClips.isNotEmpty() ||
            overrideAudioIdx != null || separationDirectives.any { it.stems.isNotEmpty() } ||
            muteWindows.isNotEmpty()
        if (!hasAnyAudio) {
            filters.add("[0:a]anull[aout]")
        } else {
            val mixInputs = mutableListOf(baseAudioRef)
            for ((i, clip) in dubClips.withIndex()) {
                filters.add(audioClipFilter(clipInputIndices[i], clip.startMs, clip.volume, "dub$i"))
                mixInputs.add("[dub$i]")
            }
            for ((i, clip) in bgmClips.withIndex()) {
                filters.add(audioClipFilter(bgmInputIndices[i], clip.startMs, clip.volume, "bgm$i"))
                mixInputs.add("[bgm$i]")
            }
            // Directive stems: directive 마다 stem 들을 atrim 으로 range 길이만큼
            // 자르고, asetpts 로 PTS 리셋, adelay 로 rangeStartMs 만큼 밀고, volume.
            // stem 본체 길이가 directive 길이보다 길면 truncate, 짧으면 amix
            // duration=first 로 video 길이까지 패딩됨.
            for ((dIdx, directive) in separationDirectives.withIndex()) {
                val rangeMs = (directive.rangeEndMs - directive.rangeStartMs).coerceAtLeast(0L)
                val rangeSec = rangeMs / 1000.0
                for ((sIdx, stem) in directive.stems.withIndex()) {
                    val inputIndex = stemInputIndices[dIdx][sIdx]
                    val label = "stem_${dIdx}_${sIdx}"
                    filters.add(
                        "[$inputIndex:a]atrim=0:$rangeSec,asetpts=PTS-STARTPTS," +
                            "adelay=${directive.rangeStartMs}|${directive.rangeStartMs}," +
                            "volume=${stem.volume}[$label]"
                    )
                    mixInputs.add("[$label]")
                }
            }
            if (mixInputs.size == 1) {
                // Only base track present (e.g., override + no clips, no stems).
                filters.add("${mixInputs[0]}anull[aout]")
            } else {
                filters.add("${mixInputs.joinToString("")}amix=inputs=${mixInputs.size}:duration=first:dropout_transition=0[aout]")
            }
        }

        // Video chain: subtitle → sticker overlays
        var vLabel = "v0"
        if (subtitlesFile != null) {
            cmd.addAll(listOf("-i", subtitlesFile.absolutePath))
            filters.add("[0:v]ass='${escapeFilterPath(subtitlesFile.absolutePath)}'[$vLabel]")
        } else {
            filters.add("[0:v]null[$vLabel]")
        }

        for ((i, clip) in imageClips.withIndex()) {
            val imgIdx = imageInputIndices[clip.imageFileKey]!!
            val wPx = ((clip.widthPct / 100f) * outW).toInt().coerceAtLeast(2)
            val hPx = ((clip.heightPct / 100f) * outH).toInt().coerceAtLeast(2)
            val xPx = ((clip.xPct / 100f) * outW).toInt() - wPx / 2
            val yPx = ((clip.yPct / 100f) * outH).toInt() - hPx / 2
            val startSec = clip.startMs / 1000.0
            val endSec = clip.endMs / 1000.0
            val nextLabel = "v${i + 1}"
            filters.add("[$imgIdx:v]scale=${wPx}:${hPx}[simg$i]")
            filters.add("[$vLabel][simg$i]overlay=x=$xPx:y=$yPx:enable='between(t,$startSec,$endSec)'[$nextLabel]")
            vLabel = nextLabel
        }

        // Rename final video label to [vout]
        val finalLabel = "[$vLabel]"
        val lastIdx = filters.indexOfLast { it.endsWith(finalLabel) }
        if (lastIdx >= 0) {
            filters[lastIdx] = filters[lastIdx].dropLast(finalLabel.length) + "[vout]"
        }

        cmd.addAll(listOf("-filter_complex", filters.joinToString(";")))
        cmd.addAll(listOf(
            "-map", "[vout]",
            "-map", "[aout]",
            "-c:v", "libx264",
            "-preset", "fast",
            "-c:a", "aac",
            "-b:a", "192k",
            "-movflags", "+faststart",
            "-t", (videoDurationMs / 1000.0).toString(),
            "-progress", "pipe:1",
            outputFile.absolutePath,
        ))

        return cmd
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun getVideoSize(videoFile: File): Pair<Int, Int> {
        return try {
            val proc = ProcessBuilder(
                "ffprobe", "-v", "quiet",
                "-show_entries", "stream=width,height",
                "-select_streams", "v:0",
                "-of", "csv=p=0",
                videoFile.absolutePath,
            ).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            val parts = output.lines().first().split(",")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 1920
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 1080
            Pair(w, h)
        } catch (e: Exception) {
            log.warn("ffprobe failed, defaulting to 1920x1080: {}", e.message)
            Pair(1920, 1080)
        }
    }

    private fun escapeFilterPath(path: String): String {
        val fwd = path.replace("\\", "/")
        // On Windows, preserve the drive-letter colon (e.g. "C:") and only escape remaining colons
        return if (fwd.length >= 2 && fwd[1] == ':') {
            fwd.substring(0, 2) + fwd.substring(2).replace(":", "\\:").replace("'", "\\'")
        } else {
            fwd.replace(":", "\\:").replace("'", "\\'")
        }
    }

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
                    (now - job.createdAt) > jobTtlMs
            }
            .forEach { (id, job) ->
                job.outputFile.delete()
                jobs.remove(id)
                log.info("Cleaned up expired render job: {}", id)
            }
    }
}
