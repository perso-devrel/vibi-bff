package com.vibi.bff

import com.vibi.bff.model.BgmClip
import com.vibi.bff.service.RenderService
import java.io.File
import kotlin.test.*

/**
 * Pure 인자 build 검증 — `buildAudioConcatCommand` 가 segment 파일 + bgmClips 를
 * 받아 만드는 ffmpeg command 의 구조 회귀 가드. ffmpeg spawn 없이 list 비교만.
 */
class RenderServiceAudioCommandTest {

    private val service = RenderService(File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac"))

    @Test
    fun `buildAudioConcatCommand emits concat filter for segments without bgm`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-concat").apply { mkdirs() }
        val a = File(tmp, "a.m4a").apply { writeText("x") }
        val b = File(tmp, "b.m4a").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        val cmd = service.buildAudioConcatCommand(
            segmentFiles = listOf(a, b),
            bgmAudioFiles = emptyMap(),
            bgmClips = emptyList(),
            totalDurationMs = 4_000,
            outputFile = out,
        )

        // -i 입력 두 개
        assertEquals(2, cmd.count { it == "-i" })
        // concat filter 가 segment 두 개 amix 없이 출력 — base 만 mapping.
        val filterIdx = cmd.indexOf("-filter_complex")
        assertTrue(filterIdx >= 0)
        val filter = cmd[filterIdx + 1]
        assertTrue(filter.contains("concat=n=2:v=0:a=1[base]"), "expected concat filter, got: $filter")
        // bgm 없으면 amix 없이 [base] 직접 출력.
        assertFalse(filter.contains("amix"), "no amix expected without bgm: $filter")
        // 출력 코덱 / movflags 검증
        assertTrue(cmd.contains("aac"))
        assertTrue(cmd.contains("+faststart"))
    }

    @Test
    fun `buildAudioConcatCommand mixes bgm clips on top of base`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-bgm").apply { mkdirs() }
        val seg = File(tmp, "seg.m4a").apply { writeText("x") }
        val bgm = File(tmp, "bgm.mp3").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        val cmd = service.buildAudioConcatCommand(
            segmentFiles = listOf(seg),
            bgmAudioFiles = mapOf("bgm_0" to bgm),
            bgmClips = listOf(BgmClip(audioFileKey = "bgm_0", startMs = 500, volume = 0.7f)),
            totalDurationMs = 3_000,
            outputFile = out,
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        // bgm filter (adelay+volume) 와 amix 표현 모두 있어야 함.
        assertTrue(
            filter.contains("adelay=500|500,volume=0.7[bgm0]"),
            "expected bgm clip filter, got: $filter",
        )
        assertTrue(filter.contains("[base][bgm0]amix="), "expected amix wiring: $filter")
    }

    @Test
    fun `buildAudioConcatCommand applies atempo for bgm speed before adelay`() {
        // 회귀 가드: BGM 속도 변경(BgmClip.speed)이 ffmpeg atempo 로 적용돼야 함.
        // atempo 는 source 추출 후·위치(adelay) 전에 와야 startMs(최종 타임라인 위치)와 정합.
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-bgmspeed").apply { mkdirs() }
        val seg = File(tmp, "seg.m4a").apply { writeText("x") }
        val bgm = File(tmp, "bgm.mp3").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        val cmd = service.buildAudioConcatCommand(
            segmentFiles = listOf(seg),
            bgmAudioFiles = mapOf("bgm_0" to bgm),
            bgmClips = listOf(BgmClip(audioFileKey = "bgm_0", startMs = 500, volume = 0.7f, speed = 2.0f)),
            totalDurationMs = 3_000,
            outputFile = out,
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        assertTrue(
            filter.contains("atempo=2.0,adelay=500|500,volume=0.7[bgm0]"),
            "expected atempo before adelay for bgm speed, got: $filter",
        )
    }

    @Test
    fun `buildAudioConcatCommand omits atempo when bgm speed is 1`() {
        // speed=1.0 (default) 은 atempo 미삽입 — 기존 출력과 byte-identical 보장.
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-bgmspeed1").apply { mkdirs() }
        val seg = File(tmp, "seg.m4a").apply { writeText("x") }
        val bgm = File(tmp, "bgm.mp3").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        val cmd = service.buildAudioConcatCommand(
            segmentFiles = listOf(seg),
            bgmAudioFiles = mapOf("bgm_0" to bgm),
            bgmClips = listOf(BgmClip(audioFileKey = "bgm_0", startMs = 500, volume = 0.7f)),
            totalDurationMs = 3_000,
            outputFile = out,
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        assertFalse(filter.contains("atempo"), "no atempo expected at speed 1.0: $filter")
    }

    @Test
    fun `buildAudioConcatCommand amix uses normalize=0 and alimiter`() {
        // Regression guard: default amix normalize=1 은 silent input 도 N 으로 카운트해 base 와
        // BGM 을 1/N 으로 나눠 의도 대비 −6 dB(2개) ~ −9.5 dB(3개) 작아진다. normalize=0 합산 후
        // alimiter 로 천장만 squash 하는 패턴이 운영 정책.
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-norm").apply { mkdirs() }
        val seg = File(tmp, "seg.m4a").apply { writeText("x") }
        val bgm = File(tmp, "bgm.mp3").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        val cmd = service.buildAudioConcatCommand(
            segmentFiles = listOf(seg),
            bgmAudioFiles = mapOf("bgm_0" to bgm),
            bgmClips = listOf(BgmClip(audioFileKey = "bgm_0", startMs = 0, volume = 1.0f)),
            totalDurationMs = 3_000,
            outputFile = out,
        )

        val filter = cmd[cmd.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("normalize=0"), "amix must specify normalize=0: $filter")
        assertTrue(filter.contains("alimiter=limit="), "alimiter must follow amix: $filter")
        // output map 은 limiter 출력을 가리켜야 함 (intermediate label 이 -map 으로 새지 않게).
        assertTrue(cmd.containsAll(listOf("-map", "[aout]")), "-map must point to [aout]: $cmd")
    }

    @Test
    fun `buildAudioConcatCommand throws on unknown bgm key`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "vibi-test-rsac-bad").apply { mkdirs() }
        val seg = File(tmp, "seg.m4a").apply { writeText("x") }
        val out = File(tmp, "out.m4a")

        assertFailsWith<IllegalArgumentException> {
            service.buildAudioConcatCommand(
                segmentFiles = listOf(seg),
                bgmAudioFiles = emptyMap(),
                bgmClips = listOf(BgmClip(audioFileKey = "missing", startMs = 0, volume = 1f)),
                totalDurationMs = 1_000,
                outputFile = out,
            )
        }
    }
}
