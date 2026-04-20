package com.dubcast.bff

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
}
