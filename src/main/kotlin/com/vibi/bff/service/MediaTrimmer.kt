package com.vibi.bff.service

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

    suspend fun probeDurationMs(file: File): Long? {
        val cmd = listOf(
            "ffprobe", "-v", "quiet",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            file.absolutePath,
        )
        return try {
            val output = FfmpegRunner.run(cmd, "ffprobe ${file.name}", timeoutMinutes = 1).trim()
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
    suspend fun trim(src: File, startMs: Long, endMs: Long, outFile: File): Boolean {
        require(endMs > startMs) { "endMs must be > startMs" }
        val startSec = startMs / 1000.0
        val durationSec = (endMs - startMs) / 1000.0
        // secondsToFfmpegArg: Double.toString 의 scientific notation 변환 회피.
        // 예: 0.000670 → "6.7E-4" 가 ffmpeg `-t` 파서에서 "Invalid duration" 으로 거부됨.
        val cmd = listOf(
            "ffmpeg", "-y",
            "-ss", secondsToFfmpegArg(startSec),
            "-i", src.absolutePath,
            "-t", secondsToFfmpegArg(durationSec),
            "-c", "copy",
            "-avoid_negative_ts", "make_non_negative",
            outFile.absolutePath,
        )
        return try {
            FfmpegRunner.run(cmd, "ffmpeg trim ${src.name}", timeoutMinutes = 5)
            if (!outFile.exists() || outFile.length() == 0L) {
                log.error("ffmpeg trim produced empty output for {}", src.name)
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
