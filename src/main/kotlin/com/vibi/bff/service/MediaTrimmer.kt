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
     * Audio-accurate cut from [startMs, endMs) into [outFile]. `-ss` 는 input
     * 보다 *뒤* 에 위치 — input-side `-ss` 는 mp4 의 video keyframe 으로 fast-seek
     * snap 해 실제 시작 지점이 사용자 선택보다 ~2초 일찍 잘리는 버그 (구간 분리에서
     * 관측됨). output-side `-ss` 는 demuxer 가 처음부터 읽되 -ss 이전 프레임은
     * 디스카드 → sample 정확도. downstream 은 audio 만 소비하므로 `-vn` 으로
     * video stream 을 dropping해 헤더 부분 깨지는 video 영향 없음. `-c:a copy` 로
     * audio 는 re-encode 없이 그대로 (mp4 의 AAC 는 frame 단위 ~21ms 정밀도라
     * 충분).
     */
    suspend fun trim(src: File, startMs: Long, endMs: Long, outFile: File): Boolean {
        require(endMs > startMs) { "endMs must be > startMs" }
        val startSec = startMs / 1000.0
        val durationSec = (endMs - startMs) / 1000.0
        // secondsToFfmpegArg: Double.toString 의 scientific notation 변환 회피.
        // 예: 0.000670 → "6.7E-4" 가 ffmpeg `-t` 파서에서 "Invalid duration" 으로 거부됨.
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", src.absolutePath,
            "-ss", secondsToFfmpegArg(startSec),
            "-t", secondsToFfmpegArg(durationSec),
            "-vn",
            "-c:a", "copy",
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
