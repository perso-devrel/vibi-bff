package com.vibi.bff.service

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Pre-separation trim utility. `probeDurationMs` is used to validate
 * `trimEndMs <= fileDuration` before we pay for upstream compute; `trim`
 * produces a **sample-accurate PCM WAV** audio cut (video stream dropped)
 * that is then uploaded to Perso in place of the original. Both
 * operations shell out to ffmpeg/ffprobe on PATH.
 *
 * 출력 컨테이너 선택 — Perso audio-separation 파이프라인이 **FLAC 업로드는 받지만 처리에서
 * "Failed" 로 종료**한다 (2026-05 live IT 확인: upload+register OK, progress=20% Uploading →
 * 100% Failed). WAV PCM 은 동일 입력에서 progress=40% Transcribing → 100% Completed.
 * 압축률 대신 호환성을 우선. lossless PCM 이라 quality 도 손실 없음.
 */
object MediaTrimmer {
    private val log = LoggerFactory.getLogger(MediaTrimmer::class.java)

    /** [trim] 출력 확장자. caller 가 같은 값을 outFile 에 써야 ffmpeg 가 WAV muxer 를 선택. */
    const val OUTPUT_EXTENSION = "wav"

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
     * Sample-accurate audio cut from [startMs, endMs) into [outFile] as **PCM WAV (s16le)**.
     *
     * `-ss` 는 `-i` *뒤* 에 위치 — input-side `-ss` 는 video keyframe 으로 fast-seek snap 해
     * 시작 지점이 사용자 선택보다 ~2초 일찍 잘리는 버그가 관측됐다. output-side `-ss` 는 demuxer
     * 가 처음부터 읽되 -ss 이전 프레임은 디스카드 → sample 정확도.
     *
     * 컨테이너 = WAV PCM s16le. lossless (decode 후 그대로 sample) + Perso 호환. FLAC 으로 시도했을
     * 때 Perso 분리 잡이 "Failed" 로 종료되는 회귀 발견 후 PCM 으로 복귀.
     */
    suspend fun trim(src: File, startMs: Long, endMs: Long, outFile: File): Boolean {
        require(endMs > startMs) { "endMs must be > startMs" }
        val startSec = startMs / 1000.0
        val durationSec = (endMs - startMs) / 1000.0
        // secondsToFfmpegArg: Double.toString 의 scientific notation 변환 회피.
        // 예: 0.000670 → "6.7E-4" 가 ffmpeg `-t` 파서에서 "Invalid duration" 으로 거부됨.
        // -avoid_negative_ts make_zero: iOS 카메라 등 edit-list 로 audio 트랙 첫 PTS 가
        // 음수인 컨테이너 → mux-level 정규화. PCM WAV 자체는 PTS 안 가져 no-op 에 가깝지만
        // 향후 컨테이너 변경 시에도 부작용 없는 default.
        val cmd = listOf(
            "ffmpeg", "-y",
            "-i", src.absolutePath,
            "-ss", secondsToFfmpegArg(startSec),
            "-t", secondsToFfmpegArg(durationSec),
            "-vn",
            "-c:a", "pcm_s16le",
            "-avoid_negative_ts", "make_zero",
            outFile.absolutePath,
        )
        return try {
            FfmpegRunner.run(cmd, "ffmpeg pcm extract+trim ${src.name}", timeoutMinutes = 10)
            if (!outFile.exists() || outFile.length() == 0L) {
                log.error("ffmpeg pcm extract produced empty output for {}", src.name)
                outFile.delete()
                false
            } else true
        } catch (e: CancellationException) {
            // coroutine cancel 은 client disconnect / shutdown 신호 — 'trim failed'
            // 로 위장해 500 으로 응답하지 말고 상위로 전달. outFile 은 정리.
            outFile.delete()
            throw e
        } catch (e: Exception) {
            log.error("ffmpeg pcm extract exception for {}: {}", src.name, e.message)
            outFile.delete()
            false
        }
    }
}
