package com.dubcast.bff.service

import com.dubcast.bff.config.SeparationConfig
import com.dubcast.bff.model.SeparationSpec
import com.dubcast.bff.model.StemInfo
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
import java.util.zip.ZipInputStream

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
        val mediaType = if (isVideo) PersoMediaType.VIDEO else PersoMediaType.AUDIO

        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        val registration = persoClient.uploadMedia(mediaType, sourceFile)
        // Perso's Blob now owns the bytes — our local copy is redundant.
        sourceFile.delete()

        job.status = "SUBMITTED"
        // targetLanguageCodes is required by Perso even when we only want
        // separation artifacts; echo the source language so nothing is
        // actually translated-for-output in the critical path.
        val projectSeq = persoClient.submitTranslate(
            mediaSeq = registration.seq,
            isVideoProject = isVideo,
            sourceLanguageCode = spec.sourceLanguageCode,
            targetLanguageCodes = listOf(if (spec.sourceLanguageCode == "auto") "en" else spec.sourceLanguageCode),
            numberOfSpeakers = spec.numberOfSpeakers,
            title = "separation-${job.jobId}",
        )

        job.status = "PROCESSING"
        pollPersoUntilComplete(
            persoClient, scope, projectSeq, pollIntervalMs, maxPollMinutes,
        ) { progress, reason ->
            job.progress = progress
            job.progressReason = reason
        }

        job.status = "DOWNLOADING"
        val plans = buildStemPlans(spec.numberOfSpeakers)
        val info = persoClient.getDownloadInfo(projectSeq)
        val availablePlans = plans.filter { it.isAvailable(info) }

        val local = mutableListOf<LocalStem>()
        for (plan in availablePlans) {
            val links = persoClient.getDownloadLinks(projectSeq, plan.persoTarget)
            val url = plan.linkSelector(links) ?: continue

            if (plan.stemId == "speakers_zip") {
                // Speaker collection arrives as ZIP → unzip into speaker_0.mp3,
                // speaker_1.mp3, ... under outputDir.
                val zipFile = File(job.outputDir, "speakers.zip")
                persoClient.streamDownload(url, zipFile)
                local.addAll(extractSpeakerZip(zipFile, job.outputDir))
                zipFile.delete()
            } else {
                val target = File(job.outputDir, "${plan.stemId}.mp3")
                persoClient.streamDownload(url, target)
                local.add(LocalStem(plan.stemId, plan.label, target))
            }
        }

        if (local.isEmpty()) {
            throw PersoApiException(500, "No stems available from Perso for project $projectSeq")
        }

        job.stems = local
        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info("Separation READY: jobId={} stems={}", job.jobId, local.map { it.stemId })
    }

    // Intentionally ignores entry.name — we rename to speaker_{idx}.mp3 under
    // outputDir. DO NOT derive the target File from entry.name without a
    // canonical-path check against outputDir: a crafted archive could use
    // "../../etc/passwd" to escape (ZIP Slip / CVE-2018-1002200).
    private fun extractSpeakerZip(zipFile: File, outputDir: File): List<LocalStem> {
        val stems = mutableListOf<LocalStem>()
        val outputCanonical = outputDir.canonicalPath
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var idx = 0
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".mp3")) {
                    val stemId = "speaker_$idx"
                    val target = File(outputDir, "$stemId.mp3")
                    // Defense-in-depth: even though the filename is ours, assert
                    // the resolved path is still inside outputDir.
                    check(target.canonicalPath.startsWith(outputCanonical)) {
                        "Zip extraction escaped outputDir: ${target.canonicalPath}"
                    }
                    target.outputStream().use { out -> zip.copyTo(out) }
                    stems.add(LocalStem(stemId, "화자 ${idx + 1}", target))
                    idx++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return stems
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

    // ── Stem plan definitions ────────────────────────────────────────────────
    private fun buildStemPlans(numberOfSpeakers: Int): List<PlanInternal> {
        val plans = mutableListOf(
            PlanInternal(
                stemId = "background",
                label = "배경음",
                persoTarget = "backgroundAudio",
                isAvailable = { it.hasOriginalBackground },
                linkSelector = { it.audioFile?.backgroundAudioDownloadLink },
            ),
            PlanInternal(
                stemId = "voice_all",
                label = "모든 화자",
                persoTarget = "originalVoiceAudio",
                isAvailable = { it.hasOriginalVoiceOnly },
                linkSelector = { it.audioFile?.originalVoiceAudioDownloadLink },
            ),
        )
        if (numberOfSpeakers >= 2) {
            plans.add(
                PlanInternal(
                    stemId = "speakers_zip",
                    label = "화자별",
                    persoTarget = "originalVoiceSpeakers",
                    isAvailable = { it.hasOriginalSpeakerAudioCollection },
                    linkSelector = {
                        it.audioFile?.originalVoiceSpeakersDownloadLink
                            ?: it.zippedFileDownloadLink
                    },
                )
            )
        }
        return plans
    }

    private data class PlanInternal(
        val stemId: String,
        val label: String,
        val persoTarget: String,
        val isAvailable: (com.dubcast.bff.model.PersoDownloadInfo) -> Boolean,
        val linkSelector: (com.dubcast.bff.model.PersoDownloadLinksResult) -> String?,
    )
}
