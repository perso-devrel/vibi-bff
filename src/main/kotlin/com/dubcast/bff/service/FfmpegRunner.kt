package com.dubcast.bff.service

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Shared ffmpeg/ffprobe process runner. Drains stdout in a background thread
 * to avoid OS pipe buffer (~64KB) deadlock that occurs when callers use
 * `bufferedReader().readText()` after `waitFor()` — ffmpeg can stall waiting
 * for the reader before the parent reaches the readText call.
 */
object FfmpegRunner {
    private val log = LoggerFactory.getLogger("FfmpegRunner")

    /**
     * Runs the given command with stderr merged into stdout, drains output on
     * a daemon thread, then waits up to [timeoutMinutes]. On non-zero exit or
     * timeout throws RuntimeException with the last 3KB of output.
     *
     * @return captured output (full, not truncated) for callers that want to log/parse it.
     */
    fun run(
        cmd: List<String>,
        label: String,
        timeoutMinutes: Long = 10,
    ): String {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        return drainAndAwait(process, label, timeoutMinutes)
    }

    fun drainAndAwait(
        process: Process,
        label: String,
        timeoutMinutes: Long = 10,
    ): String {
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { out.appendLine(it) }
            }
        }.also { it.isDaemon = true; it.start() }
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        reader.join(2000)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("$label timed out after ${timeoutMinutes}min\n${out.toString().takeLast(2000)}")
        }
        val exit = process.exitValue()
        if (exit != 0) {
            throw RuntimeException("$label failed (exit=$exit):\n${out.toString().takeLast(3000)}")
        }
        return out.toString()
    }
}
