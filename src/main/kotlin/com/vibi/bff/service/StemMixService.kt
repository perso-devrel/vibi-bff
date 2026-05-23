package com.vibi.bff.service

import com.vibi.bff.model.StemMixSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class StemMixJob(
    val mixJobId: String,
    @Volatile var status: String = "PENDING",
    @Volatile var progress: Int = 0,
    @Volatile var error: String? = null,
    val outputFile: File,
    val createdAt: Long = System.currentTimeMillis(),
    /** /mix 요청을 submit 한 사용자. 라우트가 status/download 호출 시 caller 와
     * 매칭해 다른 사용자가 mixJobId 만으로 fresh download token 을 발급받는 IDOR
     * 차단. null 이면 미인증 잡 (테스트 / jwtSecret 미주입). */
    val ownerUserId: UUID? = null,
)

class StemMixService(
    private val mixDir: File,
    private val mixTtlMs: Long,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, StemMixJob>()
    private val cleanup = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stem-mix-cleanup").apply { isDaemon = true }
    }

    init {
        mixDir.mkdirs()
        cleanup.scheduleAtFixedRate(::cleanupExpired, 5, 5, TimeUnit.MINUTES)
    }

    fun shutdown() {
        cleanup.shutdown()
        if (!cleanup.awaitTermination(2, TimeUnit.SECONDS)) cleanup.shutdownNow()
    }

    fun getJob(mixJobId: String): StemMixJob? = jobs[mixJobId]

    fun newJobId(): String = "mix-${UUID.randomUUID()}"

    fun submit(
        mixJobId: String,
        stemFiles: List<Pair<StemMixSelection, File>>,
        ownerUserId: UUID? = null,
        onCompleted: (StemMixJob) -> Unit = {},
    ): String {
        require(stemFiles.isNotEmpty()) { "stemFiles must not be empty" }
        for ((_, f) in stemFiles) require(f.exists()) { "Stem file missing: ${f.absolutePath}" }

        val outputFile = File(mixDir, "$mixJobId.mp3")
        val job = StemMixJob(mixJobId = mixJobId, outputFile = outputFile, ownerUserId = ownerUserId)
        jobs[mixJobId] = job

        scope.launch {
            try {
                job.status = "PROCESSING"
                val command = buildStemMixCommand(stemFiles, outputFile)
                log.info("Starting stem mix: mixJobId={} inputs={}", mixJobId, stemFiles.size)
                FfmpegRunner.run(command, "stem mix $mixJobId", timeoutMinutes = 10)
                if (outputFile.exists()) {
                    job.status = "COMPLETED"
                    job.progress = 100
                    log.info("Stem mix completed: mixJobId={} size={}", mixJobId, outputFile.length())
                } else {
                    job.status = "FAILED"
                    job.error = "ffmpeg produced no output"
                    log.error("Stem mix failed: mixJobId={} no output", mixJobId)
                }
            } catch (e: Exception) {
                outputFile.delete()
                job.status = "FAILED"
                job.error = e.message
                log.error("Stem mix error: mixJobId={}", mixJobId, e)
            } finally {
                // FAILED 도 callback 부르도록 finally — caller (SeparationRoutes) 가
                // reserve 풀어서 separation job 자체를 dispose / release 가능. 이전엔
                // COMPLETED 만 callback 호출 → FAILED 시 separation 의 reserve 가 영구
                // stuck (cleanupAbandoned 의 stuckReserved 가 fallback).
                runCatching { onCompleted(job) }
            }
        }

        return mixJobId
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        jobs.entries
            .filter { (_, j) ->
                (j.status == "COMPLETED" || j.status == "FAILED") &&
                    (now - j.createdAt) > mixTtlMs
            }
            .forEach { (id, j) ->
                j.outputFile.delete()
                jobs.remove(id)
                log.info("Cleaned up expired stem mix: {}", id)
            }
    }

    companion object {
        // Pure function — kept on the companion so it can be unit-tested
        // without spawning ffmpeg. Builds an amix filter graph where each
        // input gets its own volume node, then all are summed.
        fun buildStemMixCommand(
            stemFiles: List<Pair<StemMixSelection, File>>,
            outputFile: File,
        ): List<String> {
            val cmd = mutableListOf("ffmpeg", "-y")
            for ((_, f) in stemFiles) {
                cmd.addAll(listOf("-i", f.absolutePath))
            }
            // 단일 stem 케이스: amix=inputs=1 은 일부 ffmpeg 빌드에서 'Cannot allocate
            // memory' / silence 출력 회귀가 알려져 있어 anull passthrough 로 우회
            // (RenderService.buildAudioConcatCommand 와 동일 패턴). alimiter 는 그대로
            // 적용해 사용자가 volume boost 했을 때 clipping 안전망 유지.
            val filters = stemFiles.mapIndexed { i, (sel, _) ->
                "[$i:a]volume=${sel.volume}[a$i]"
            } + run {
                if (stemFiles.size == 1) {
                    "[a0]anull[amix_out]"
                } else {
                    val amixInputs = stemFiles.indices.joinToString("") { "[a$it]" }
                    // normalize=0: 입력을 1/N 로 나누지 않고 그대로 합산. Perso 의 stem 들은
                    // 원본 = sum(stems) 관계라 normalize=1 (default) 로 두면 N 개 합쳐도
                    // 평균이 되어 원본 대비 -6dB(N=2) ~ -9.5dB(N=3) 만큼 작아짐.
                    // duration=longest so shorter stems get padded with silence
                    // instead of truncating the full mix.
                    "${amixInputs}amix=inputs=${stemFiles.size}:duration=longest:dropout_transition=0:normalize=0[amix_out]"
                }
            } + "[amix_out]alimiter=limit=0.95:attack=5:release=50[aout]"
            cmd.addAll(listOf("-filter_complex", filters.joinToString(";")))
            cmd.addAll(listOf(
                "-map", "[aout]",
                "-c:a", "libmp3lame",
                "-b:a", "192k",
                // RenderService.parseFfmpegProgress 와 동일 포맷 (`out_time_us=`) 으로
                // 진행률 추출 — 외부 caller (모바일) 가 stem mix 도 같은 progress shape
                // 으로 폴링할 수 있게 통일.
                "-progress", "pipe:1",
                outputFile.absolutePath,
            ))
            return cmd
        }
    }
}
