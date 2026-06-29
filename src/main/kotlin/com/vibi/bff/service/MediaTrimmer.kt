package com.vibi.bff.service

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Media probe utility. 라우트가 받는 입력의 stream 종류 / 길이를 검증·크레딧 KPI 용으로 측정.
 * Trim 자체는 클라이언트가 담당 — BFF 는 받은 파일을 그대로 Perso 로 forward.
 */
object MediaTrimmer {
    private val log = LoggerFactory.getLogger(MediaTrimmer::class.java)
    private val probeJson = Json { ignoreUnknownKeys = true }

    /** [probe] 결과 — stream 종류 집합 + format 길이(ms, 측정 실패 시 null). */
    data class MediaProbe(
        val streamKinds: Set<String>,
        val durationMs: Long?,
    )

    /**
     * 한 번의 ffprobe 호출로 stream 종류 + 길이를 동시에 측정 (이전엔 [probeStreamKinds] /
     * [probeDurationMs] 가 같은 파일에 ffprobe 를 따로 spawn 했음). probe 실패는 null.
     *
     * [MediaProbe.streamKinds] 예: {"audio"} / {"video", "audio"} / {"video"}.
     * `/api/v2/separate` 의 화이트리스트가 multipart filename 만 신뢰하는 회귀 방지용 — 같은 `.m4a`
     * 라벨이라도 mp4 container 는 video track 도 담을 수 있어 클라/공격자가 video bytes 를 부적절한
     * audio 확장자로 ren-upload 하면 Perso 가 silent fail 한다 (CLAUDE.md FLAC 회귀와 동일 클래스).
     * stream kind 까지 검증해 audio track 이 있는 파일만 통과시킨다.
     */
    suspend fun probe(file: File): MediaProbe? {
        val cmd = listOf(
            "ffprobe", "-v", "quiet",
            "-print_format", "json",
            "-show_entries", "stream=codec_type:format=duration",
            file.absolutePath,
        )
        return try {
            val output = FfmpegRunner.run(cmd, "ffprobe ${file.name}", timeoutMinutes = 1).trim()
            if (output.isBlank()) return null
            val root = probeJson.parseToJsonElement(output).jsonObject
            val streamKinds = root["streams"]?.jsonArray
                ?.mapNotNull { it.jsonObject["codec_type"]?.jsonPrimitive?.contentOrNull }
                ?.toSet()
                ?: emptySet()
            val durationMs = root["format"]?.jsonObject
                ?.get("duration")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()
                ?.let { (it * 1000).toLong() }
            MediaProbe(streamKinds = streamKinds, durationMs = durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ffprobe failed for {}: {}", file.name, e.message)
            null
        }
    }

    /** format 길이(ms). probe 실패 / 길이 미측정은 null. 단일 값만 필요한 caller 용 ([probe] 위임). */
    suspend fun probeDurationMs(file: File): Long? = probe(file)?.durationMs

    /** 파일 안의 stream 종류 집합. probe 실패는 null. 단일 값만 필요한 caller 용 ([probe] 위임). */
    suspend fun probeStreamKinds(file: File): Set<String>? = probe(file)?.streamKinds
}
