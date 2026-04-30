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
    /** 원본 video 의 audio 트랙을 mute 하고 더빙 audio 를 합성한 mp4. mobile 미리보기용. */
    @Volatile var dubbedVideoFile: File? = null,
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

    init {
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
        // 원본 영상은 ffmpeg mux 단계에서 사용해야 하므로 outputDir 로 이동 후 보관
        // (Perso 업로드 직후 dispose 하던 기존 동작 변경).
        val keptSource = File(job.outputDir, "source.${sourceFile.extension.ifBlank { "mp4" }}")
        sourceFile.copyTo(keptSource, overwrite = true)
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
        // target=dubbingVideo → Perso 가 더빙 영상(mp4) 직접 반환. 자체 ffmpeg mux 불필요.
        // VIDEO 프로젝트만 dubbingVideo 가능. AUDIO 프로젝트는 translatedAudio 로 fallback.
        val dubFile: File
        if (isVideo) {
            val links = persoClient.getDownloadLinks(projectSeq, "dubbingVideo")
            val videoLink = links.videoFile?.videoDownloadLink
                ?: throw PersoApiException(500, "Perso did not return a dubbingVideo link")
            val dubVideoFile = File(job.outputDir, "dubbed_video.mp4")
            // dubbingVideo link 는 `/perso-storage/...` 상대 경로 — Perso 인증 헤더 필요.
            persoClient.streamDownloadAuthorized(videoLink, dubVideoFile)
            job.dubbedVideoFile = dubVideoFile
            // /audio endpoint 호환을 위해 mp4 에서 audio 트랙만 분리 추출.
            dubFile = File(job.outputDir, "dubbed.mp3")
            val ok = ffmpegExtractAudio(dubVideoFile, dubFile)
            if (!ok) {
                log.warn("AutoDub audio extract failed: jobId={} — /audio endpoint will be unavailable", job.jobId)
            } else {
                job.dubbedAudioFile = dubFile
            }
            log.info("AutoDub dubbingVideo downloaded: jobId={} mp4={}", job.jobId, dubVideoFile.name)
        } else {
            val links = persoClient.getDownloadLinks(projectSeq, "translatedAudio")
            val dubLink = links.audioFile?.translatedVoiceDownloadLink
                ?: links.audioFile?.translatedAudioDownloadLink
                ?: links.audioFile?.voiceWithBackgroundAudioDownloadLink
                ?: links.audioFile?.voiceAudioDownloadLink
                ?: throw PersoApiException(500, "Perso did not return a translated audio link")
            dubFile = File(job.outputDir, "dubbed.mp3")
            persoClient.streamDownload(dubLink, dubFile)
            job.dubbedAudioFile = dubFile
        }
        keptSource.delete()

        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("AutoDub READY: jobId={} audio={} video={}", job.jobId, dubFile.name, job.dubbedVideoFile?.name)
    }

    /**
     * 영상의 video 트랙 + 더빙 audio 트랙만 결합 — 영상의 원본 audio 는 -map 0:v 로만 가져오므로
     * 자연스럽게 빠진다. video 는 stream copy (-c:v copy) 로 빠르게.
     */
    private fun ffmpegMux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c:v", "copy",
            "-c:a", "aac",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-shortest",
            outputFile.absolutePath
        )
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(2, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0 && outputFile.exists() && outputFile.length() > 0
            }
        } catch (e: Exception) {
            log.error("ffmpeg mux exception", e)
            false
        }
    }

    /**
     * 더빙 mp4 의 audio 트랙만 mp3 로 추출 — re-encode 로 호환성 보장.
     */
    private fun ffmpegExtractAudio(videoFile: File, outputFile: File): Boolean {
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", videoFile.absolutePath,
            "-vn",
            "-acodec", "libmp3lame",
            "-q:a", "4",
            outputFile.absolutePath
        )
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(2, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0 && outputFile.exists() && outputFile.length() > 0
            }
        } catch (e: Exception) {
            log.error("ffmpeg extract-audio exception", e)
            false
        }
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
