package com.dubcast.bff.service

import com.dubcast.bff.model.SubtitleSpec
import com.dubcast.bff.plugins.PersoApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Mirrors SeparationService's lifecycle conventions: a brief retention window
// for FAILED so clients can fetch the error, a longer window for READY before
// the cleanup task reaps unreferenced output dirs.
private const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L
private const val READY_TTL_MS = 60 * 60 * 1000L

data class SubtitleJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var progressReason: String? = null,
    @Volatile var error: String? = null,
    val outputDir: File,
    @Volatile var originalSrtFile: File? = null,
    @Volatile var translatedSrtFile: File? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Drives the Perso STT-only flow: submit a translate job with source==target
 * language to harvest the original SRT cheaply, then optionally translate via
 * Gemini. We never use Perso's translation output here — Gemini gives us
 * faster turnaround per quota, and keeps the language-pair matrix open
 * beyond Perso's TTS catalog.
 */
class AutoSubtitleService(
    private val persoClient: PersoClient,
    private val geminiClient: GeminiClient,
    private val outputDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val pollIntervalMs: Long,
    private val maxPollMinutes: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, SubtitleJob>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "subtitle-cleanup").apply { isDaemon = true }
    }

    fun init() {
        outputDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupExpired, 10, 10, TimeUnit.MINUTES)
    }

    fun shutdown() {
        cleanup.shutdown()
        if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) cleanup.shutdownNow()
    }

    fun getJob(jobId: String): SubtitleJob? = jobs[jobId]

    fun submit(sourceFile: File, spec: SubtitleSpec): String {
        val jobId = "sub-${UUID.randomUUID()}"
        val dir = File(outputDir, jobId).apply { mkdirs() }
        val job = SubtitleJob(jobId = jobId, outputDir = dir)
        jobs[jobId] = job

        scope.launch {
            try {
                runPipeline(job, sourceFile, spec)
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
                log.error("Subtitle pipeline failed: jobId={}", jobId, e)
            }
        }
        return jobId
    }

    private suspend fun runPipeline(job: SubtitleJob, sourceFile: File, spec: SubtitleSpec) {
        val isVideo = spec.mediaType == "VIDEO"
        val mediaType = if (isVideo) PersoMediaType.VIDEO else PersoMediaType.AUDIO

        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        val registration = persoClient.uploadMedia(mediaType, sourceFile)
        sourceFile.delete()

        job.status = "SUBMITTED"
        // Echo source as the only target language. Perso refuses target list to
        // be empty, but emitting the original SRT does NOT depend on the chosen
        // target — we discard whatever translation Perso produces.
        val projectSeq = persoClient.submitTranslate(
            mediaSeq = registration.seq,
            isVideoProject = isVideo,
            sourceLanguageCode = spec.sourceLanguageCode,
            targetLanguageCodes = listOf(spec.sourceLanguageCode),
            numberOfSpeakers = spec.numberOfSpeakers,
            title = "subtitles-${job.jobId}",
        )

        job.status = "PROCESSING"
        pollPersoUntilComplete(
            persoClient, scope, projectSeq, pollIntervalMs, maxPollMinutes,
        ) { progress, reason ->
            job.progress = progress
            job.progressReason = reason
        }

        job.status = "DOWNLOADING"
        val info = persoClient.getDownloadInfo(projectSeq)
        if (!info.hasOriginalSubtitle) {
            throw PersoApiException(500, "Perso reported no original subtitle for project $projectSeq")
        }
        val links = persoClient.getDownloadLinks(projectSeq, "originalSubtitle")
        val originalLink = links.srtFile?.originalSubtitleDownloadLink
            ?: throw PersoApiException(500, "Perso did not return originalSubtitleDownloadLink")
        val originalFile = File(job.outputDir, "original.srt")
        persoClient.streamDownload(originalLink, originalFile)
        job.originalSrtFile = originalFile

        val target = spec.targetLanguageCode
        if (!target.isNullOrBlank() && target != spec.sourceLanguageCode) {
            job.progressReason = "Translating"
            val translated = geminiClient.translateSrt(originalFile.readText(Charsets.UTF_8), target)
            val translatedFile = File(job.outputDir, "translated.srt")
            translatedFile.writeText(translated, Charsets.UTF_8)
            job.translatedSrtFile = translatedFile
        }

        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info(
            "Subtitle READY: jobId={} hasTranslation={}",
            job.jobId, job.translatedSrtFile != null,
        )
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        jobs.entries
            .filter { (_, j) ->
                val age = now - j.createdAt
                val readyExpired = j.status == "READY" && age > READY_TTL_MS
                val failedExpired = j.status == "FAILED" && age > FAILED_JOB_TTL_MS
                readyExpired || failedExpired
            }
            .forEach { (id, _) ->
                dispose(id)
                log.info("Cleaned up subtitle job: {}", id)
            }
    }

    fun dispose(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        job.outputDir.deleteRecursively()
    }
}
