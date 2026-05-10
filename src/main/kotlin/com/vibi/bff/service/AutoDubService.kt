package com.vibi.bff.service

import com.vibi.bff.FAILED_JOB_TTL_MS
import com.vibi.bff.READY_JOB_TTL_MS as READY_TTL_MS
import com.vibi.bff.model.AutoDubSpec
import com.vibi.bff.plugins.PersoApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /** VIDEO 분기에서 dubbed mp4 → mp3 추출 실패 시 true. job 자체는 READY 로 진행. */
    @Volatile var audioExtractFailed: Boolean = false,
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
        // renameTo 는 cross-filesystem 일 경우 false 반환 — 실패 시 copy 로 fallback.
        // 큰 영상 (수백 MB) 일 때 disk I/O 절반 절감.
        val keptSource = File(job.outputDir, "source.${sourceFile.extension.ifBlank { "mp4" }}")
        if (keptSource.exists()) keptSource.delete()
        if (!sourceFile.renameTo(keptSource)) {
            sourceFile.copyTo(keptSource, overwrite = true)
            sourceFile.delete()
        }

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
                job.audioExtractFailed = true
            } else {
                job.dubbedAudioFile = dubFile
            }
            log.info("AutoDub dubbingVideo downloaded: jobId={} mp4={}", job.jobId, dubVideoFile.name)
        } else {
            // AUDIO 분기는 원본 video mux 가 없어 keptSource 를 더 들고 있을 이유가 없음 —
            // 다운로드 시작 전 즉시 디스크 회수.
            keptSource.delete()
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
        // VIDEO 분기는 mux 가능성 위해 위에서 유지했고 여기서 회수. 이미 AUDIO 분기에서
        // delete 된 경우 idempotent (delete 가 false 반환할 뿐).
        keptSource.delete()

        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("AutoDub READY: jobId={} audio={} video={}", job.jobId, dubFile.name, job.dubbedVideoFile?.name)
    }

    /**
     * 더빙 mp4 의 audio 트랙만 mp3 로 추출 — re-encode 로 호환성 보장.
     */
    private suspend fun ffmpegExtractAudio(videoFile: File, outputFile: File): Boolean {
        return try {
            extractMp3(
                input = videoFile,
                output = outputFile,
                quality = 4,
                timeoutMinutes = 2,
                label = "ffmpeg extract-audio ${videoFile.name}",
            )
            outputFile.exists() && outputFile.length() > 0
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
