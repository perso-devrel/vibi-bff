package com.vibi.bff

import com.vibi.bff.service.DirectiveStem
import com.vibi.bff.service.DirectiveWithStemFiles
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.secondsToFfmpegArg
import java.io.File
import kotlin.test.*

class RenderServiceUtilsTest {

    private val service = RenderService(File(System.getProperty("java.io.tmpdir"), "renderservice-utils-test"))

    // ── secondsToFfmpegArg ─────────────────────────────────────────────────────

    @Test
    fun `secondsToFfmpegArg formats sub-millisecond as fixed decimal not scientific`() {
        // Regression guard: 0.000670 used to render as "6.7E-4" via Double.toString,
        // which ffmpeg rejected with "Invalid duration for option t" (exit 234).
        val arg = secondsToFfmpegArg(0.000670)
        assertEquals("0.000670", arg)
        assertFalse(arg.contains("E", ignoreCase = true), "must not contain scientific notation: $arg")
    }

    @Test
    fun `secondsToFfmpegArg rounds to microsecond precision`() {
        // ffmpeg AV_TIME_BASE is 1e6 — anything beyond 6 decimals is noise.
        assertEquals("1.500000", secondsToFfmpegArg(1.5))
        assertEquals("0.000000", secondsToFfmpegArg(0.0))
        assertEquals("3.000000", secondsToFfmpegArg(3.0))
    }

    @Test
    fun `secondsToFfmpegArg uses US locale for decimal separator`() {
        // Locale-sensitive String.format would output "1,500000" on de-DE / fr-FR
        // and ffmpeg rejects that. Locale.US fixes it regardless of JVM default.
        val arg = secondsToFfmpegArg(1.5)
        assertTrue(arg.contains("."), "expected dot decimal: $arg")
        assertFalse(arg.contains(","), "must not use comma decimal: $arg")
    }

    @Test
    fun `secondsToFfmpegArg handles very small and very large values`() {
        // Tiny — would otherwise be "6.702596628521888E-4" from Double.toString.
        val tiny = secondsToFfmpegArg(6.702596628521888E-4)
        assertEquals("0.000670", tiny)
        // Large — well within long-form range.
        assertEquals("3600.000000", secondsToFfmpegArg(3600.0))
    }

    // ── atempoChain ────────────────────────────────────────────────────────────

    @Test
    fun `atempoChain emits single filter for in-range speeds`() {
        assertEquals("atempo=0.5", service.atempoChain(0.5f))
        assertEquals("atempo=1.0", service.atempoChain(1.0f))
        assertEquals("atempo=2.0", service.atempoChain(2.0f))
    }

    @Test
    fun `atempoChain chains filters for fast speeds above 2x`() {
        // 4x = 2x * 2x
        assertEquals("atempo=2.0,atempo=2.0", service.atempoChain(4.0f))
    }

    @Test
    fun `atempoChain chains filters for slow speeds below half`() {
        // 0.25x = 0.5x * 0.5x
        assertEquals("atempo=0.5,atempo=0.5", service.atempoChain(0.25f))
    }

    @Test
    fun `atempoChain rejects zero and negative speed`() {
        // Regression guard — pre-fix this looped forever and hung the render
        // coroutine indefinitely.
        assertFailsWith<IllegalArgumentException> { service.atempoChain(0.0f) }
        assertFailsWith<IllegalArgumentException> { service.atempoChain(-1.0f) }
    }

    // ── separationDirectives → ffmpeg command ────────────────────────────────

    @Test
    fun `buildFfmpegCommand wires directive stems into amix and mutes base`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stems").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem0 = File(tmp, "s0.mp3").apply { writeText("x") }
        val stem1 = File(tmp, "s1.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 10_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 1000,
                    rangeEndMs = 4000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(
                        DirectiveStem(file = stem0, volume = 1.0f),
                        DirectiveStem(file = stem1, volume = 0.5f),
                    ),
                ),
            ),
        )

        // stem 파일들이 -i 입력으로 추가됐는지
        assertTrue(cmd.contains(stem0.absolutePath), "stem0 not in inputs: $cmd")
        assertTrue(cmd.contains(stem1.absolutePath), "stem1 not in inputs: $cmd")

        // filter_complex 내용 점검
        val filterIdx = cmd.indexOf("-filter_complex")
        assertTrue(filterIdx >= 0)
        val filter = cmd[filterIdx + 1]

        // base mute (rangeStartMs=1000 → 1.0s, rangeEndMs=4000 → 4.0s)
        assertTrue(
            filter.contains("[0:a]volume=enable='gt(between(t,1.0,4.0),0)':volume=0[base_muted]"),
            "expected base mute filter not found in: $filter",
        )
        // stem filters with atrim+adelay+volume — sourceOffsetMs=0 default → atrim=0.0:3.0
        assertTrue(
            filter.contains("atrim=0.0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=1.0[stem_0_0]"),
            "expected stem_0_0 filter not found in: $filter",
        )
        assertTrue(
            filter.contains("atrim=0.0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=0.5[stem_0_1]"),
            "expected stem_0_1 filter not found in: $filter",
        )
        // stems and base_muted go into the final amix
        assertTrue(filter.contains("[base_muted][stem_0_0][stem_0_1]amix="), "amix wiring missing in: $filter")
    }

    /**
     * 모바일이 영상 range delete 로 directive 를 split 한 경우, 뒤쪽 piece 는 `sourceOffsetMs > 0` 으로
     * 같은 stem audio 파일의 중간부터 재생되어야 한다. ffmpeg `atrim` 의 시작점이 sourceOffsetMs/1000
     * 으로 옮겨졌는지 검증.
     */
    @Test
    fun `buildFfmpegCommand atrim uses sourceOffsetMs for split directive`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-stems-split").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "s.mp3").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        // split 뒤쪽 piece — composition 의 [4s, 7s] 를 차지하지만 stem audio 의 [10s, 13s] 를 재생.
        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 20_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 4_000,
                    rangeEndMs = 7_000,
                    muteOriginalSegmentAudio = true,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                    sourceOffsetMs = 10_000,
                ),
            ),
        )

        val filterIdx = cmd.indexOf("-filter_complex")
        val filter = cmd[filterIdx + 1]
        // atrim 의 시작점 = sourceOffsetMs/1000 = 10.0, 끝점 = (sourceOffsetMs+rangeMs)/1000 = 13.0.
        // adelay 는 composition 좌표라 rangeStartMs (4000) 그대로.
        assertTrue(
            filter.contains("atrim=10.0:13.0,asetpts=PTS-STARTPTS,adelay=4000|4000,volume=1.0[stem_0_0]"),
            "expected split-piece atrim with sourceOffsetMs not found in: $filter",
        )
    }

    /**
     * Regression: 한글 파일명을 가진 stem 이 ffmpeg 입력 인자로 그대로 전달되는지
     * (escape 안 깨지는지). ProcessBuilder 는 인자 array 형태로 spawn 하므로 shell
     * escape 가 필요 없음.
     */
    @Test
    fun `buildFfmpegCommand passes Korean filename as -i input unchanged`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "rsut-korean").apply { mkdirs() }
        val video = File(tmp, "v.mp4").apply { writeText("x") }
        val stem = File(tmp, "화자 1.wav").apply { writeText("x") }
        val out = File(tmp, "out.mp4")

        val cmd = service.buildFfmpegCommand(
            videoFile = video,
            videoDurationMs = 5_000,
            outputFile = out,
            separationDirectives = listOf(
                DirectiveWithStemFiles(
                    rangeStartMs = 0,
                    rangeEndMs = 5_000,
                    muteOriginalSegmentAudio = false,
                    stems = listOf(DirectiveStem(file = stem, volume = 1.0f)),
                ),
            ),
        )
        // 한글 파일명이 그대로 -i 인자에 포함되어야 함 (ProcessBuilder array 인자라
        // shell escape 불필요).
        assertTrue(
            cmd.contains(stem.absolutePath),
            "Korean filename must be passed verbatim, got: $cmd",
        )
        assertTrue(stem.absolutePath.contains("화자"), "test fixture must include Korean")
    }
}
