package com.dubcast.bff.service

/**
 * Estimates MP3 audio duration from raw bytes by parsing frame headers.
 */
object AudioUtils {

    private val MP3_BITRATES_V1_L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    )

    private val MP3_SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000, 0)

    fun estimateMp3DurationMs(data: ByteArray): Long? {
        val frameStart = findFirstFrameSync(data) ?: return null

        val b1 = data[frameStart + 1].toInt() and 0xFF
        val b2 = data[frameStart + 2].toInt() and 0xFF

        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03

        val bitrate = MP3_BITRATES_V1_L3[bitrateIndex]
        val sampleRate = MP3_SAMPLE_RATES_V1[sampleRateIndex]

        if (bitrate == 0 || sampleRate == 0) return null

        // For CBR MP3: durationMs = fileSizeBytes * 8 / bitrateKbps
        // (bits / kbps = milliseconds, since kbps = 1000 bits/sec)
        return (data.size.toLong() * 8) / bitrate
    }

    private fun findFirstFrameSync(data: ByteArray): Int? {
        for (i in 0 until data.size - 3) {
            if ((data[i].toInt() and 0xFF) == 0xFF &&
                (data[i + 1].toInt() and 0xE0) == 0xE0
            ) {
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF

                // MPEG version 1, Layer 3
                val version = (b1 shr 3) and 0x03
                val layer = (b1 shr 1) and 0x03
                val bitrateIndex = (b2 shr 4) and 0x0F
                val sampleRateIndex = (b2 shr 2) and 0x03

                if (version == 3 && layer == 1 && bitrateIndex in 1..14 && sampleRateIndex in 0..2) {
                    return i
                }
            }
        }
        return null
    }
}
