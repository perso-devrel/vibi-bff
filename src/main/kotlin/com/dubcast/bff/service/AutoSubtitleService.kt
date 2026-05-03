package com.dubcast.bff.service

import com.dubcast.bff.FAILED_JOB_TTL_MS
import com.dubcast.bff.READY_JOB_TTL_MS as READY_TTL_MS
import com.dubcast.bff.model.PersoScriptSentence
import com.dubcast.bff.model.SubtitleSpec
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

data class SubtitleJob(
    val jobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var progressReason: String? = null,
    @Volatile var error: String? = null,
    val outputDir: File,
    @Volatile var originalSrtFile: File? = null,
    /** 언어 코드 → 번역된 SRT 파일. 1 STT + N 번역 패턴의 결과. */
    @Volatile var translatedSrtFiles: Map<String, File> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 자막 STT — Perso 의 전용 STT API (`/stt`) 사용. translate 우회 트릭 폐기.
 *
 * 흐름:
 *   1. Perso video/audio 업로드 → mediaSeq
 *   2. `submitStt(mediaSeq, isVideoProject, title)` → projectSeq
 *   3. `getProgress` 폴링
 *   4. `getSttScript(projectSeq, cursor)` paginated 수집 → sentences
 *   5. sentences → SRT 빌드 (offsetMs/durationMs/originalText) → original.srt
 *   6. targetLanguageCodes 각각 Gemini 번역 → translated_<lang>.srt
 *
 * 번역은 Gemini 일임 (사용자 정책). Perso 의 자체 번역 엔진 안 씀.
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

    init {
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

    /**
     * 사용자가 수정한 SRT 를 source 로 다른 언어 자막 재생성. STT 단계 스킵 — Perso 호출 없이 Gemini 만.
     * source==target 인 언어는 입력 SRT 를 그대로 복사.
     */
    fun submitRegenerate(srtFile: File, spec: SubtitleSpec): String {
        val jobId = "sub-${UUID.randomUUID()}"
        val dir = File(outputDir, jobId).apply { mkdirs() }
        // 입력 SRT 를 outputDir 로 복사해 originalSrtFile 로 노출.
        val originalDest = File(dir, "original.srt")
        srtFile.copyTo(originalDest, overwrite = true)
        srtFile.delete()
        val job = SubtitleJob(
            jobId = jobId,
            outputDir = dir,
            originalSrtFile = originalDest,
        )
        jobs[jobId] = job

        scope.launch {
            try {
                runRegeneratePipeline(job, spec)
            } catch (e: Exception) {
                job.status = "FAILED"
                job.error = e.message
                log.error("Subtitle regenerate pipeline failed: jobId={}", jobId, e)
            }
        }
        return jobId
    }

    private suspend fun runRegeneratePipeline(job: SubtitleJob, spec: SubtitleSpec) {
        val original = job.originalSrtFile
            ?: throw IllegalStateException("regenerate job missing original SRT")
        val srtBody = original.readText(Charsets.UTF_8)
        val sourceLang = spec.sourceLanguageCode.takeIf { it.isNotBlank() } ?: "auto"

        job.status = "PROCESSING"
        job.progressReason = "Translating"

        val targets = spec.targetLanguageCodes
            .filter { it.isNotBlank() }
            .distinct()
        if (targets.isEmpty()) {
            // 번역할 언어 0개라도 originalSrt 는 그대로 노출. 흔한 시나리오는 아님.
            job.progress = 100
            job.progressReason = "Completed"
            job.status = "READY"
            return
        }

        val results = mutableMapOf<String, File>()
        targets.forEachIndexed { idx, lang ->
            val file = File(job.outputDir, "translated_$lang.srt")
            if (lang == sourceLang) {
                file.writeText(srtBody, Charsets.UTF_8)
                log.info("Regenerate skipped Gemini for lang={} (same as source)", lang)
            } else {
                val translated = geminiClient.translateSrt(srtBody, lang)
                file.writeText(translated, Charsets.UTF_8)
            }
            results[lang] = file
            job.progress = ((idx + 1) * 100 / targets.size)
        }
        job.translatedSrtFiles = results
        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info(
            "Subtitle REGENERATE READY: jobId={} sourceLang={} translatedLangs={}",
            job.jobId, sourceLang, results.keys,
        )
    }

    private suspend fun runPipeline(job: SubtitleJob, sourceFile: File, spec: SubtitleSpec) {
        val isVideo = spec.mediaType == "VIDEO"

        // 항상 audio 추출 후 audio project 로 업로드 — Perso STT 는 video/audio project 모두
        // 동일한 sentences 결과를 주므로 video 트랙 업로드는 낭비. mp4 압축 (~10MB) 대신 mp3 추출
        // (~1MB) 로 업로드 size 대폭 감소.
        job.status = "PROCESSING"
        val uploadFile = if (isVideo) {
            job.progressReason = "Extracting audio"
            val mp3 = File(job.outputDir, "audio.mp3")
            extractAudioForStt(sourceFile, mp3)
            sourceFile.delete()
            mp3
        } else {
            sourceFile
        }

        // 1) Perso audio project 등록.
        job.status = "UPLOADING_UPSTREAM"
        job.progressReason = "Uploading"
        val registration = persoClient.uploadMedia(PersoMediaType.AUDIO, uploadFile)
        uploadFile.delete()

        // Perso STT 는 source lang 인자를 받지 않음 — 자동 감지. spec.sourceLanguageCode 는 Gemini
        // 번역 시 source lang 컨텍스트로만 쓰임.
        val sourceLang = spec.sourceLanguageCode.takeIf { it.isNotBlank() } ?: "auto"

        // 2) Perso 전용 STT 프로젝트 생성. audio-only 업로드라 isVideoProject=false.
        job.status = "SUBMITTED"
        val projectSeq = persoClient.submitStt(
            mediaSeq = registration.seq,
            isVideoProject = false,
            title = "subtitle-${job.jobId}",
        )

        // 3) 진행 상황 폴링.
        job.status = "PROCESSING"
        delay(15_000L)
        pollPersoUntilComplete(
            persoClient, scope, projectSeq,
            pollIntervalMs = pollIntervalMs,
            maxPollMinutes = maxPollMinutes,
        ) { progress, reason ->
            job.progress = progress
            job.progressReason = reason
        }

        // 4) STT script paginated 수집 → SRT 빌드.
        job.status = "DOWNLOADING"
        job.progressReason = "Fetching script"
        val sentences = collectAllSentences(projectSeq)
        if (sentences.isEmpty()) {
            throw RuntimeException("Perso STT returned 0 sentences for project $projectSeq")
        }
        val srtBody = sentencesToSrt(sentences)
        val originalFile = File(job.outputDir, "original.srt")
        originalFile.writeText(srtBody, Charsets.UTF_8)
        job.originalSrtFile = originalFile
        job.progress = 70

        // STT 결과 텍스트로 source lang 휴리스틱 감지 — target 이 source 와 동일하면 Gemini 호출
        // 생략 (original SRT 그대로 복사). source 가 명시 lang 이면 그것 우선.
        // 앞 8 sentences 만 검사 — CJK/Latin 판별엔 충분하고 긴 영상의 메모리/CPU 낭비 회피.
        val detectedLang = detectLanguage(
            sentences.asSequence()
                .take(8)
                .map { it.originalText ?: it.originalDraftText ?: "" }
                .joinToString(" ")
        )
        val effectiveSource = if (sourceLang.isNotBlank() && sourceLang != "auto") sourceLang else detectedLang
        log.info("STT lang resolution: explicit={} detected={} effective={}", sourceLang, detectedLang, effectiveSource)

        // 5) targetLanguageCodes 각각에 대해 Gemini 번역 또는 source==target 시 original 복사.
        val targets = spec.targetLanguageCodes
            .filter { it.isNotBlank() }
            .distinct()
        if (targets.isNotEmpty()) {
            job.progressReason = "Translating"
            val results = mutableMapOf<String, File>()
            targets.forEachIndexed { idx, lang ->
                val file = File(job.outputDir, "translated_$lang.srt")
                if (lang == effectiveSource) {
                    // source 와 동일 lang — Gemini 번역 불필요. original 복사.
                    file.writeText(srtBody, Charsets.UTF_8)
                    log.info("Skipped Gemini for lang={} (same as source) — copied original SRT", lang)
                } else {
                    val translated = geminiClient.translateSrt(srtBody, lang)
                    file.writeText(translated, Charsets.UTF_8)
                }
                results[lang] = file
                job.progress = 70 + ((idx + 1) * 30 / targets.size)
            }
            job.translatedSrtFiles = results
        }

        job.progress = 100
        job.progressReason = "Completed"
        job.status = "READY"
        log.info(
            "Subtitle READY: jobId={} translatedLangs={}",
            job.jobId, job.translatedSrtFiles.keys,
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

    /**
     * STT script paginated 전부 수집. `hasNext=false` 까지 cursorId 따라가며 누적.
     * 무한 루프 방지 cap=200 페이지.
     */
    private suspend fun collectAllSentences(projectSeq: Long): List<PersoScriptSentence> {
        val all = mutableListOf<PersoScriptSentence>()
        var cursor: Long? = null
        var pages = 0
        while (true) {
            val page = persoClient.getSttScript(projectSeq, cursor)
            all += page.sentences
            pages += 1
            if (!page.hasNext || page.nextCursorId == null || pages >= 200) break
            cursor = page.nextCursorId
        }
        log.info("Collected STT sentences: projectSeq={} count={} pages={}", projectSeq, all.size, pages)
        return all.sortedBy { it.offsetMs }
    }

    /**
     * Perso STT sentences → SRT body. 빈 텍스트 라인은 skip (Perso 가 가끔 보냄).
     */
    private fun sentencesToSrt(sentences: List<PersoScriptSentence>): String {
        val sb = StringBuilder()
        var idx = 1
        for (s in sentences) {
            val text = (s.originalText ?: s.originalDraftText ?: "").trim()
            if (text.isEmpty()) continue
            val end = s.offsetMs + s.durationMs.coerceAtLeast(1L)
            sb.append(idx).append('\n')
            sb.append(formatSrtTime(s.offsetMs)).append(" --> ").append(formatSrtTime(end)).append('\n')
            sb.append(text).append("\n\n")
            idx++
        }
        return sb.toString()
    }

    /**
     * 텍스트에서 주요 언어 코드 휴리스틱 감지. CJK 우선 (한글/일본어 가나/한자), 그 외는 라틴 기반으로
     * `en` 추정. 감지 실패 시 null. 정확도는 낮지만 source==target 케이스 회피용으로는 충분.
     */
    private fun detectLanguage(text: String): String? {
        if (text.isBlank()) return null
        var hangul = 0
        var hiragana = 0
        var katakana = 0
        var cjk = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0xAC00..0xD7AF -> hangul++
                c in 0x3040..0x309F -> hiragana++
                c in 0x30A0..0x30FF -> katakana++
                c in 0x4E00..0x9FFF -> cjk++
                ch.isLetter() && c < 0x0250 -> latin++
            }
        }
        return when {
            hangul > 0 -> "ko"
            hiragana > 0 || katakana > 0 -> "ja"
            cjk > 0 -> "zh"
            latin > 0 -> "en"
            else -> null
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = (total % 3_600_000) / 60_000
        val seconds = (total % 60_000) / 1000
        val millis = total % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * 영상 → mp3 audio 추출. STT 만 필요하므로 video 트랙은 통째로 버림. 변환 자체도 video 압축보다
     * 훨씬 빠르고 결과 size 도 1/10 수준 (영상 70MB → mp3 ~1-2MB).
     */
    private fun extractAudioForStt(input: File, output: File) {
        log.info("ffmpeg extract audio: input={} ({} bytes) output={}", input.name, input.length(), output.absolutePath)
        FfmpegRunner.run(
            listOf(
                "ffmpeg", "-y",
                "-i", input.absolutePath,
                "-vn",
                "-c:a", "libmp3lame",
                "-q:a", "5",
                output.absolutePath,
            ),
            "ffmpeg audio extract",
            timeoutMinutes = 5,
        )
        log.info("ffmpeg audio extract done: output={} ({} bytes)", output.name, output.length())
    }
}
