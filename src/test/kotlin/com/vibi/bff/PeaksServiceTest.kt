package com.vibi.bff

import com.vibi.bff.service.PeaksService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * f32le PCM → 정규화 peak 축약(util/peaks.ts 포팅) 단위 검증. ffmpeg/DB 불필요 — 알고리즘만.
 */
class PeaksServiceTest {

    private fun writePcm(samples: FloatArray): File {
        val pcm = File.createTempFile("vibi-peaks-test-", ".pcm")
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putFloat(it) }
        pcm.writeBytes(bb.array())
        return pcm
    }

    @Test
    fun `reducePeaks buckets by max abs and normalizes peak to 1`() {
        // 8 샘플, barCount=4 → bucket=2. 버킷별 max|amp|: 0.5, 0.8, 0.9, 0.4. 전체 max=0.9 로 정규화.
        val pcm = writePcm(floatArrayOf(0.1f, -0.5f, 0.2f, 0.8f, -0.3f, 0.9f, 0.0f, 0.4f))
        try {
            val r = PeaksService.reducePeaks(pcm, barCount = 4)
            assertEquals(4, r.peaks.size)
            assertEquals(0.5f / 0.9f, r.peaks[0], 1e-5f)
            assertEquals(0.8f / 0.9f, r.peaks[1], 1e-5f)
            assertEquals(1.0f, r.peaks[2], 1e-6f)
            assertEquals(0.4f / 0.9f, r.peaks[3], 1e-5f)
            assertEquals(8.0 / PeaksService.SR, r.durationSec, 1e-9)
        } finally {
            pcm.delete()
        }
    }

    @Test
    fun `reducePeaks handles silence without NaN`() {
        val pcm = writePcm(FloatArray(8)) // 모두 0
        try {
            val r = PeaksService.reducePeaks(pcm, barCount = 4)
            assertTrue(r.peaks.all { it == 0f }, "maxOverall=0 이면 나눗셈 없이 0 유지(NaN 금지)")
        } finally {
            pcm.delete()
        }
    }

    @Test
    fun `reducePeaks on empty pcm returns zeros and zero duration`() {
        val pcm = File.createTempFile("vibi-peaks-empty-", ".pcm")
        try {
            val r = PeaksService.reducePeaks(pcm, barCount = 4)
            assertEquals(4, r.peaks.size)
            assertTrue(r.peaks.all { it == 0f })
            assertEquals(0.0, r.durationSec, 1e-12)
        } finally {
            pcm.delete()
        }
    }
}
