package com.dubcast.bff.service

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Estimates MP3 audio duration from frame headers.
 */
object AudioUtils {

    private val MP3_BITRATES_V1_L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    )

    private val MP3_SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000, 0)

    // Frame sync lives at offset 0 for raw MP3 or just past the ID3v2 tag
    // (bounded by spec). 128 KiB is a safe upper bound for the scan — past
    // that we give up rather than linear-scan a multi-MB file.
    private const val MAX_SYNC_SCAN_BYTES = 128 * 1024

    fun estimateMp3DurationMs(file: File): Long? {
        if (!file.exists() || file.length() < 4) return null
        val headerBytes = ByteArray(min(file.length(), MAX_SYNC_SCAN_BYTES.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(headerBytes) }

        val frameStart = findFirstFrameSync(headerBytes) ?: return null

        val b2 = headerBytes[frameStart + 2].toInt() and 0xFF
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03

        val bitrate = MP3_BITRATES_V1_L3[bitrateIndex]
        val sampleRate = MP3_SAMPLE_RATES_V1[sampleRateIndex]

        if (bitrate == 0 || sampleRate == 0) return null

        // For CBR MP3: durationMs = fileSizeBytes * 8 / bitrateKbps
        // (bits / kbps = milliseconds, since kbps = 1000 bits/sec)
        return (file.length() * 8) / bitrate
    }

    private fun findFirstFrameSync(data: ByteArray): Int? {
        val scanEnd = min(data.size - 3, MAX_SYNC_SCAN_BYTES)
        for (i in 0 until scanEnd) {
            if ((data[i].toInt() and 0xFF) == 0xFF &&
                (data[i + 1].toInt() and 0xE0) == 0xE0
            ) {
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF

                val version = (b1 shr 3) and 0x03
                val layer = (b1 shr 1) and 0x03
                val bitrateIndex = (b2 shr 4) and 0x0F
                val sampleRateIndex = (b2 shr 2) and 0x03

                // MPEG version 1, Layer 3
                if (version == 3 && layer == 1 && bitrateIndex in 1..14 && sampleRateIndex in 0..2) {
                    return i
                }
            }
        }
        return null
    }
}
