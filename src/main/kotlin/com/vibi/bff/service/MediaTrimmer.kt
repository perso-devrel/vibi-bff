package com.vibi.bff.service

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Audio duration probe utility. 라우트가 받는 audio 의 길이를 분석/크레딧 KPI 용으로 측정.
 * Trim 자체는 모바일 측 `AudioExtractor` 가 담당 — BFF 는 받은 audio 를 그대로 Perso 로 forward.
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ffprobe failed for {}: {}", file.name, e.message)
            null
        }
    }

    /**
     * 파일 안의 stream 종류 집합 (예: {"audio"} / {"video", "audio"} / {"video"}). probe 실패는 null.
     *
     * `/api/v2/separate` 의 audio-only 화이트리스트가 multipart filename 만 신뢰하는 회귀 방지용 — 같은
     * `.m4a` 라벨이라도 mp4 container 자체는 video track 도 담을 수 있어 클라/공격자가 video bytes 를
     * 부적절한 audio 확장자로 ren-upload 하면 Perso 가 silent fail 한다 (CLAUDE.md FLAC 회귀와 동일 클래스).
     * 본 helper 로 stream kind 까지 검증해 audio 만 있는 파일만 통과시킨다.
     */
    suspend fun probeStreamKinds(file: File): Set<String>? {
        val cmd = listOf(
            "ffprobe", "-v", "quiet",
            "-show_entries", "stream=codec_type",
            "-of", "csv=p=0",
            file.absolutePath,
        )
        return try {
            val output = FfmpegRunner.run(cmd, "ffprobe streams ${file.name}", timeoutMinutes = 1).trim()
            if (output.isBlank()) emptySet()
            else output.lines().mapNotNull { it.trim().takeIf { line -> line.isNotEmpty() } }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ffprobe streams failed for {}: {}", file.name, e.message)
            null
        }
    }
}
