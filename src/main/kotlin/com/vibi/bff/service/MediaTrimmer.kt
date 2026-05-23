package com.vibi.bff.service

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Pre-separation trim utility. `probeDurationMs` is used to validate
 * `trimEndMs <= fileDuration` before we pay for upstream compute; `trim`
 * produces a **sample-accurate FLAC** audio cut (video stream dropped)
 * that is then uploaded to Perso in place of the original. Both
 * operations shell out to ffmpeg/ffprobe on PATH.
 */
object MediaTrimmer {
    private val log = LoggerFactory.getLogger(MediaTrimmer::class.java)

    /** [trim] 출력 확장자. caller 가 같은 값을 outFile 에 써야 ffmpeg 가 FLAC muxer 를 선택. */
    const val OUTPUT_EXTENSION = "flac"

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
        } catch (e: CancellationException) {
            // coroutine cancel 은 client disconnect / shutdown 신호 — 일반 ffmpeg
            // 실패로 위장하지 않고 상위로 전달.
            throw e
        } catch (e: Exception) {
            log.warn("ffprobe failed for {}: {}", file.name, e.message)
            null
        }
    }

    /**
     * Sample-accurate audio cut from [startMs, endMs) into [outFile] as **FLAC**.
     *
     * `-ss` 는 `-i` *뒤* 에 위치 — input-side `-ss` 는 video keyframe 으로 fast-seek snap 해
     * 시작 지점이 사용자 선택보다 ~2초 일찍 잘리는 버그가 관측됐다. output-side `-ss` 는 demuxer
     * 가 처음부터 읽되 -ss 이전 프레임은 디스카드 → sample 정확도.
     *
     * FLAC lossless — Perso 가 어차피 PCM 으로 decode 하므로 quality 손실 0. AAC stream-copy 로
     * 잘랐을 때의 frame boundary snap (~21ms) 을 피하기 위해 디코드 경로 필수.
     */
    suspend fun trim(src: File, startMs: Long, endMs: Long, outFile: File): Boolean {
        require(endMs > startMs) { "endMs must be > startMs" }
        val startSec = startMs / 1000.0
        val durationSec = (endMs - startMs) / 1000.0
        // secondsToFfmpegArg: Double.toString 의 scientific notation 변환 회피.
        // 예: 0.000670 → "6.7E-4" 가 ffmpeg `-t` 파서에서 "Invalid duration" 으로 거부됨.
        // -avoid_negative_ts make_zero: iOS 카메라 등 edit-list 로 audio 트랙 첫 PTS 가
        // 음수인 컨테이너 → mux-level 정규화. FLAC 출력 자체는 PTS 안 가져 no-op 에 가깝지만
        // 향후 컨테이너 변경 시에도 부작용 없는 default. 실측 PTS rebase 가 필요해지면
        // `-af "aresample=async=1:first_pts=0"` 같은 sample-level 필터를 별도 검증 후 도입.
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", src.absolutePath,
            "-ss", secondsToFfmpegArg(startSec),
            "-t", secondsToFfmpegArg(durationSec),
            "-vn",
            "-c:a", "flac",
            "-compression_level", "5",
            "-avoid_negative_ts", "make_zero",
            outFile.absolutePath,
        )
        return try {
            FfmpegRunner.run(cmd, "ffmpeg flac extract+trim ${src.name}", timeoutMinutes = 10)
            if (!outFile.exists() || outFile.length() == 0L) {
                log.error("ffmpeg flac extract produced empty output for {}", src.name)
                outFile.delete()
                false
            } else true
        } catch (e: CancellationException) {
            // coroutine cancel 은 client disconnect / shutdown 신호 — 'trim failed'
            // 로 위장해 500 으로 응답하지 말고 상위로 전달. outFile 은 정리.
            outFile.delete()
            throw e
        } catch (e: Exception) {
            log.error("ffmpeg flac extract exception for {}: {}", src.name, e.message)
            outFile.delete()
            false
        }
    }
}
