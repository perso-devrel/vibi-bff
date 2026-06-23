package com.vibi.bff.service

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.slf4j.LoggerFactory

data class PeaksResult(val peaks: List<Float>, val durationSec: Double)

/**
 * 입력 오디오(mp3/m4a/wav)를 ffmpeg 로 8kHz mono f32 PCM 으로 디코드해 정규화된 파형 peak 묶음으로
 * 줄인다. UXP 패널이 mp3/AAC 를 직접 디코드 못 해 입력 미리보기 파형을 서버에 요청한다(크레딧 무차감).
 * plugin server/ 의 util/peaks.ts 포팅.
 */
object PeaksService {
    private val log = LoggerFactory.getLogger(javaClass)

    const val SR = 8000 // 파형 overview 엔 충분, PCM 버퍼를 작게 유지
    const val DEFAULT_BAR_COUNT = 200

    suspend fun computePeaks(input: File, barCount: Int = DEFAULT_BAR_COUNT): PeaksResult {
        val pcm = File.createTempFile("vibi-peaks-", ".pcm")
        try {
            // f32le mono PCM 을 임시 파일로 — FfmpegRunner 는 stdout 을 텍스트로 drain 하므로
            // 바이너리 PCM 을 stdout 으로 받으면 깨진다. 파일로 받아 스트리밍 reduce.
            FfmpegRunner.run(
                listOf(
                    "ffmpeg", "-y", "-v", "error",
                    "-i", input.absolutePath,
                    "-ac", "1", "-ar", "$SR", "-f", "f32le",
                    pcm.absolutePath,
                ),
                label = "peaks ${input.name}",
                timeoutMinutes = 2,
            )
            return reducePeaks(pcm, barCount)
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /**
     * f32le PCM 파일 → barCount 개 정규화 peak(0..1) + durationSec. 64KB 청크 스트리밍이라 긴
     * 오디오도 메모리 bounded. plugin 동일: 버킷별 max|amp|, 전체 max 로 정규화, trailing 샘플 버림.
     */
    internal fun reducePeaks(pcm: File, barCount: Int = DEFAULT_BAR_COUNT): PeaksResult {
        val floatCount = pcm.length() / 4L
        val durationSec = floatCount.toDouble() / SR
        val peaks = FloatArray(barCount)
        if (floatCount == 0L) return PeaksResult(peaks.toList(), 0.0)

        val bucket = maxOf(1L, floatCount / barCount)
        val limit = bucket * barCount // 이후 trailing 샘플은 버림(plugin reducePeaks 동일)
        var maxOverall = 0f
        BufferedInputStream(FileInputStream(pcm)).use { ins ->
            val chunk = ByteArray(64 * 1024) // 4의 배수
            var idx = 0L
            loop@ while (idx < limit) {
                val read = ins.readNBytes(chunk, 0, chunk.size)
                if (read <= 0) break
                val usable = read - (read % 4)
                val bb = ByteBuffer.wrap(chunk, 0, usable).order(ByteOrder.LITTLE_ENDIAN)
                var off = 0
                while (off < usable) {
                    if (idx >= limit) break@loop
                    val a = abs(bb.getFloat(off))
                    val b = (idx / bucket).toInt()
                    if (a > peaks[b]) peaks[b] = a
                    if (a > maxOverall) maxOverall = a
                    idx++
                    off += 4
                }
            }
        }
        if (maxOverall > 0f) {
            for (i in 0 until barCount) peaks[i] = peaks[i] / maxOverall
        }
        return PeaksResult(peaks.toList(), durationSec)
    }
}
