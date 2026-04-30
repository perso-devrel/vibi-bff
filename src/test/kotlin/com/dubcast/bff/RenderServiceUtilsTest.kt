package com.dubcast.bff

import com.dubcast.bff.service.DirectiveStem
import com.dubcast.bff.service.DirectiveWithStemFiles
import com.dubcast.bff.service.RenderService
import java.io.File
import kotlin.test.*

class RenderServiceUtilsTest {

    private val service = RenderService(File(System.getProperty("java.io.tmpdir"), "renderservice-utils-test"))

    // ── ffmpegColor ────────────────────────────────────────────────────────────

    @Test
    fun `ffmpegColor normalises hash prefix`() {
        assertEquals("0xff0000", service.ffmpegColor("#ff0000"))
        assertEquals("0xABCDEF", service.ffmpegColor("#ABCDEF"))
    }

    @Test
    fun `ffmpegColor accepts bare hex without prefix`() {
        assertEquals("0x123456", service.ffmpegColor("123456"))
    }

    @Test
    fun `ffmpegColor trims surrounding whitespace`() {
        assertEquals("0xff0000", service.ffmpegColor("  #ff0000  "))
    }

    @Test
    fun `ffmpegColor falls back to black on malformed input`() {
        assertEquals("0x000000", service.ffmpegColor("not-a-color"))
        assertEquals("0x000000", service.ffmpegColor("#GGGGGG"))
        assertEquals("0x000000", service.ffmpegColor("#fff"))       // 3-char shorthand not supported
        assertEquals("0x000000", service.ffmpegColor("#00000000"))  // RRGGBBAA not supported
    }

    @Test
    fun `ffmpegColor rejects filter injection attempts`() {
        // Commas and quotes would escape the pad= color argument into other
        // filters — the regex must reject anything outside 6 hex digits.
        assertEquals("0x000000", service.ffmpegColor("#000000,drawtext='x'"))
        assertEquals("0x000000", service.ffmpegColor("black:x=0"))
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
            audioFiles = emptyMap(),
            imageFiles = emptyMap(),
            subtitlesFile = null,
            dubClips = emptyList(),
            imageClips = emptyList(),
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
        // stem filters with atrim+adelay+volume
        assertTrue(
            filter.contains("atrim=0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=1.0[stem_0_0]"),
            "expected stem_0_0 filter not found in: $filter",
        )
        assertTrue(
            filter.contains("atrim=0:3.0,asetpts=PTS-STARTPTS,adelay=1000|1000,volume=0.5[stem_0_1]"),
            "expected stem_0_1 filter not found in: $filter",
        )
        // stems and base_muted go into the final amix
        assertTrue(filter.contains("[base_muted][stem_0_0][stem_0_1]amix="), "amix wiring missing in: $filter")
    }
}
