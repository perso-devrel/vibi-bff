package com.dubcast.bff.service

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Pre-separation trim utility. `probeDurationMs` is used to validate
 * `trimEndMs <= fileDuration` before we pay for upstream compute; `trim`
 * produces a stream-copy cut that is then uploaded to Perso in place of
 * the original. Both operations shell out to ffmpeg/ffprobe on PATH.
 */
object MediaTrimmer {
    private val log = LoggerFactory.getLogger(MediaTrimmer::class.java)

    fun probeDurationMs(file: File): Long? {
        val cmd = listOf(
            "ffprobe", "-v", "quiet",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            file.absolutePath,
        )
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            if (exit != 0) {
                log.warn("ffprobe exit={} for {}: {}", exit, file.name, output.takeLast(200))
                return null
            }
            val seconds = output.lines().firstOrNull()?.toDoubleOrNull() ?: return null
            (seconds * 1000).toLong()
        } catch (e: Exception) {
            log.warn("ffprobe failed for {}: {}", file.name, e.message)
            null
        }
    }

    /**
     * Stream-copy cut from [startMs, endMs) into [outFile]. Uses
     * input-side `-ss` + `-t` to avoid re-encoding; video may snap to the
     * nearest keyframe, acceptable here since downstream only consumes the
     * audio track. Returns true on success.
     */
    fun trim(src: File, startMs: Long, endMs: Long, outFile: File): Boolean {
        require(endMs > startMs) { "endMs must be > startMs" }
        val startSec = startMs / 1000.0
        val durationSec = (endMs - startMs) / 1000.0
        val cmd = listOf(
            "ffmpeg", "-y",
            "-ss", startSec.toString(),
            "-i", src.absolutePath,
            "-t", durationSec.toString(),
            "-c", "copy",
            "-avoid_negative_ts", "make_non_negative",
            outFile.absolutePath,
        )
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0 || !outFile.exists() || outFile.length() == 0L) {
                log.error("ffmpeg trim failed exit={}: {}", exit, output.takeLast(500))
                outFile.delete()
                false
            } else true
        } catch (e: Exception) {
            log.error("ffmpeg trim exception for {}: {}", src.name, e.message)
            outFile.delete()
            false
        }
    }
}
