package com.dubcast.bff.service

import com.dubcast.bff.model.AutoDubSpec
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

private const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L
private const val READY_TTL_MS = 60 * 60 * 1000L

data class AutoDubJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var progressReason: String? = null,
    @Volatile var error: String? = null,
    val outputDir: File,
    @Volatile var dubbedAudioFile: File? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Drives Perso's translate-with-TTS flow without lip-sync. The dubbed audio
 * is downloaded as `translatedAudio` and exposed via a signed URL so the
 * client can either drop it on the timeline or render it through the BFF
 * render pipeline.
 */
class AutoDubService(
    private val persoClient: PersoClient,
    private val outputDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val pollIntervalMs: Long,
    private val maxPollMinutes: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, AutoDubJob>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "autodub-cleanup").apply { isDaemon = true }
    }

    fun init() {
        outputDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupExpired, 10, 10, TimeUnit.MINUTES)
    }

    fun shutdown() {
        cleanup.shutdown()
        if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) cleanup.shutdownNow()
    }

    fun getJob(jobId: String): AutoDubJob? = jobs[jobId]

    fun submit(sourceFile: File, spec: AutoDubSpec): String {
        val jobId = "dub-${UUID.randomUUID()}"
        val dir = File(outputDir, jobId).apply { mkdirs() }
        val job = AutoDubJob(jobId = jobId, outputDir = dir)
        jobs[jobId] = job

        scope.launch {
            try {
                runPipeline(job, sourceFile, spec)
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
                log.error("AutoDub pipeline failed: jobId={}", jobId, e)
            }
        }
        return jobId
    }

    private suspend fun runPipeline(job: AutoDubJob, sourceFile: File, spec: AutoDubSpec) {
        val isVideo = spec.mediaType == "VIDEO"
        val mediaType = if (isVideo) PersoMediaType.VIDEO else PersoMediaType.AUDIO

        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        val registration = persoClient.uploadMedia(mediaType, sourceFile)
        sourceFile.delete()

        job.status = "SUBMITTED"
        val projectSeq = persoClient.submitTranslate(
            mediaSeq = registration.seq,
            isVideoProject = isVideo,
            sourceLanguageCode = spec.sourceLanguageCode,
            targetLanguageCodes = listOf(spec.targetLanguageCode),
            numberOfSpeakers = spec.numberOfSpeakers,
            title = "autodub-${job.jobId}",
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
        // `hasTranslateAudio` (typo intentional — that's the upstream field name)
        // is the most reliable signal that the dubbed audio asset exists. Some
        // builds also report `hasTranslatedVoice` — accept either.
        val hasAudio = info.hasTranslateAudio == true || info.hasTranslatedVoice
        if (!hasAudio) {
            throw PersoApiException(500, "Perso reported no translated audio for project $projectSeq")
        }
        val links = persoClient.getDownloadLinks(projectSeq, "translatedAudio")
        val dubLink = links.audioFile?.translatedAudioDownloadLink
            ?: links.audioFile?.voiceWithBackgroundAudioDownloadLink
            ?: links.audioFile?.voiceAudioDownloadLink
            ?: throw PersoApiException(500, "Perso did not return a translated audio link")

        val dubFile = File(job.outputDir, "dubbed.mp3")
        persoClient.streamDownload(dubLink, dubFile)
        job.dubbedAudioFile = dubFile

        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("AutoDub READY: jobId={} file={}", job.jobId, dubFile.name)
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
                log.info("Cleaned up autodub job: {}", id)
            }
    }

    fun dispose(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        job.outputDir.deleteRecursively()
    }
}
