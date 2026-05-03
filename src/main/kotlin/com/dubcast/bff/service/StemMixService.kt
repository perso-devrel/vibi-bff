package com.dubcast.bff.service

import com.dubcast.bff.model.StemMixSelection
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
        onCompleted: (StemMixJob) -> Unit = {},
    ): String {
        require(stemFiles.isNotEmpty()) { "stemFiles must not be empty" }
        for ((_, f) in stemFiles) require(f.exists()) { "Stem file missing: ${f.absolutePath}" }

        val outputFile = File(mixDir, "$mixJobId.mp3")
        val job = StemMixJob(mixJobId = mixJobId, outputFile = outputFile)
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
                    onCompleted(job)
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
            val filters = stemFiles.mapIndexed { i, (sel, _) ->
                "[$i:a]volume=${sel.volume}[a$i]"
            } + run {
                val amixInputs = stemFiles.indices.joinToString("") { "[a$it]" }
                // duration=longest so shorter stems get padded with silence
                // instead of truncating the full mix.
                "${amixInputs}amix=inputs=${stemFiles.size}:duration=longest:dropout_transition=0[aout]"
            }
            cmd.addAll(listOf("-filter_complex", filters.joinToString(";")))
            cmd.addAll(listOf(
                "-map", "[aout]",
                "-c:a", "libmp3lame",
                "-b:a", "192k",
                outputFile.absolutePath,
            ))
            return cmd
        }
    }
}
