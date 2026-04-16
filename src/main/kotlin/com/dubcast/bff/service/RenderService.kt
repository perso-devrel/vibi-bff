package com.dubcast.bff.service

import com.dubcast.bff.model.DubClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RenderJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var error: String? = null,
    val outputFile: File,
    val createdAt: Long = System.currentTimeMillis(),
)

class RenderService(
    private val renderDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val jobTtlMs: Long = 3600_000, // 1 hour
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, RenderJob>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "render-cleanup").apply { isDaemon = true }
    }

    fun init() {
        renderDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupExpiredJobs, 10, 10, TimeUnit.MINUTES)
        log.info("Render directory initialized at {}", renderDir.absolutePath)
    }

    fun getJob(jobId: String): RenderJob? = jobs[jobId]

    fun submitRender(
        videoFile: File,
        audioFiles: Map<String, File>,
        subtitlesFile: File?,
        dubClips: List<DubClip>,
        videoDurationMs: Long,
        inputFilesToCleanup: List<File> = emptyList(),
    ): String {
        require(videoDurationMs > 0) { "videoDurationMs must be positive" }

        val jobId = "render-${UUID.randomUUID()}"
        val outputFile = File(renderDir, "$jobId.mp4")
        val job = RenderJob(jobId = jobId, outputFile = outputFile)
        jobs[jobId] = job

        scope.launch {
            var process: Process? = null
            try {
                job.status = "PROCESSING"
                job.progress = 0

                val command = buildFfmpegCommand(
                    videoFile, audioFiles, subtitlesFile, dubClips, videoDurationMs, outputFile
                )

                log.info("Starting ffmpeg render: jobId={}", jobId)

                process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                val durationSec = videoDurationMs / 1000.0

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parseFfmpegProgress(line, durationSec)?.let { pct ->
                            job.progress = pct
                        }
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
            } catch (e: Exception) {
                process?.destroyForcibly()
                outputFile.delete()
                job.status = "FAILED"
                job.error = e.message
                log.error("Render error: jobId={}", jobId, e)
            } finally {
                inputFilesToCleanup.forEach { it.delete() }
            }
        }

        return jobId
    }

    internal fun buildFfmpegCommand(
        videoFile: File,
        audioFiles: Map<String, File>,
        subtitlesFile: File?,
        dubClips: List<DubClip>,
        videoDurationMs: Long,
        outputFile: File,
    ): List<String> {
        val cmd = mutableListOf("ffmpeg", "-y")

        // Input 0: original video
        cmd.addAll(listOf("-i", videoFile.absolutePath))

        // Inputs 1..N: audio files for each dub clip
        val clipInputIndices = mutableListOf<Int>()
        var inputIdx = 1
        for (clip in dubClips) {
            val audioFile = audioFiles[clip.audioFileKey]
                ?: throw IllegalArgumentException("Audio file not found for key: ${clip.audioFileKey}")
            cmd.addAll(listOf("-i", audioFile.absolutePath))
            clipInputIndices.add(inputIdx)
            inputIdx++
        }

        // Build filter_complex
        val filters = mutableListOf<String>()
        val mixInputs = mutableListOf<String>()

        // Original audio from video
        mixInputs.add("[0:a]")

        // Each dub clip: apply delay and volume
        for ((i, clip) in dubClips.withIndex()) {
            val idx = clipInputIndices[i]
            val delayMs = clip.startMs
            val label = "dub$i"
            filters.add("[$idx:a]adelay=$delayMs|$delayMs,volume=${clip.volume}[$label]")
            mixInputs.add("[$label]")
        }

        // Mix all audio streams
        val mixCount = mixInputs.size
        val mixFilter = "${mixInputs.joinToString("")}amix=inputs=$mixCount:duration=first:dropout_transition=0[aout]"
        filters.add(mixFilter)

        // Subtitle burn-in via ASS filter, or null (no-op) filter for passthrough
        if (subtitlesFile != null) {
            // Use -i for subtitle input to avoid path escaping issues in filter strings
            cmd.addAll(listOf("-i", subtitlesFile.absolutePath))
            val subIdx = inputIdx
            filters.add("[0:v][$subIdx:s]overlay=eof_action=pass[vout]".let {
                // ASS subtitles are text-based, use subtitles filter with input index
                "[0:v]ass='${escapeFilterPath(subtitlesFile.absolutePath)}'[vout]"
            })
        } else {
            filters.add("[0:v]null[vout]")
        }

        val filterComplex = filters.joinToString(";")
        cmd.addAll(listOf("-filter_complex", filterComplex))

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

    private fun escapeFilterPath(path: String): String {
        return path
            .replace("\\", "/")
            .replace(":", "\\:")
            .replace("'", "'\\''")
    }

    internal fun parseFfmpegProgress(line: String, totalDurationSec: Double): Int? {
        if (!line.startsWith("out_time_us=")) return null
        if (totalDurationSec <= 0) return null
        val timeUs = line.substringAfter("out_time_us=").toLongOrNull() ?: return null
        val timeSec = timeUs / 1_000_000.0
        val pct = ((timeSec / totalDurationSec) * 100).toInt().coerceIn(0, 99)
        return pct
    }

    private fun cleanupExpiredJobs() {
        val now = System.currentTimeMillis()
        val expired = jobs.entries.filter { (_, job) ->
            (job.status == "COMPLETED" || job.status == "FAILED") &&
                (now - job.createdAt) > jobTtlMs
        }
        for ((id, job) in expired) {
            job.outputFile.delete()
            jobs.remove(id)
            log.info("Cleaned up expired render job: {}", id)
        }
    }
}
