package com.dubcast.bff

import com.dubcast.bff.model.BgmClip
import com.dubcast.bff.model.DubClip
import com.dubcast.bff.model.FrameConfig
import com.dubcast.bff.model.RenderConfig
import com.dubcast.bff.model.Segment
import com.dubcast.bff.plugins.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class RenderSerializationTest {

    private val json: Json = AppJson

    // ── Segment ───────────────────────────────────────────────────────────────

    @Test
    fun `Segment defaults volumeScale and speedScale to 1_0`() {
        val decoded: Segment = json.decodeFromString(
            """{"sourceFileKey":"video_0","type":"VIDEO","order":0,"durationMs":5000}"""
        )
        assertEquals(1.0f, decoded.volumeScale)
        assertEquals(1.0f, decoded.speedScale)
    }

    @Test
    fun `Segment accepts custom volumeScale and speedScale`() {
        val decoded: Segment = json.decodeFromString(
            """{"sourceFileKey":"video_0","type":"VIDEO","order":0,"durationMs":5000,"volumeScale":0.5,"speedScale":2.0}"""
        )
        assertEquals(0.5f, decoded.volumeScale)
        assertEquals(2.0f, decoded.speedScale)
    }

    @Test
    fun `Segment round-trips through JSON`() {
        val original = Segment(
            sourceFileKey = "video_0",
            type = "VIDEO",
            order = 0,
            durationMs = 5000,
            volumeScale = 0.8f,
            speedScale = 1.5f,
        )
        val restored: Segment = json.decodeFromString(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `Segment rejects non-positive speedScale`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Segment>(
                """{"sourceFileKey":"v","type":"VIDEO","order":0,"durationMs":1000,"speedScale":0}"""
            )
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Segment>(
                """{"sourceFileKey":"v","type":"VIDEO","order":0,"durationMs":1000,"speedScale":-1}"""
            )
        }
    }

    @Test
    fun `Segment rejects negative volumeScale`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Segment>(
                """{"sourceFileKey":"v","type":"VIDEO","order":0,"durationMs":1000,"volumeScale":-0.1}"""
            )
        }
    }

    // ── FrameConfig ───────────────────────────────────────────────────────────

    @Test
    fun `FrameConfig defaults backgroundColorHex`() {
        val decoded: FrameConfig = json.decodeFromString("""{"width":1920,"height":1080}""")
        assertEquals("#000000", decoded.backgroundColorHex)
    }

    @Test
    fun `FrameConfig round-trips`() {
        val original = FrameConfig(width = 1080, height = 1920, backgroundColorHex = "#ff00aa")
        val restored: FrameConfig = json.decodeFromString(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `FrameConfig rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<FrameConfig>("""{"width":0,"height":1080}""")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<FrameConfig>("""{"width":1920,"height":-1}""")
        }
    }

    // ── BgmClip ───────────────────────────────────────────────────────────────

    @Test
    fun `BgmClip defaults volume to 1_0`() {
        val decoded: BgmClip = json.decodeFromString("""{"audioFileKey":"bgm_0","startMs":0}""")
        assertEquals(1.0f, decoded.volume)
    }

    @Test
    fun `BgmClip round-trips`() {
        val original = BgmClip(audioFileKey = "bgm_1", startMs = 1500, volume = 0.3f)
        val restored: BgmClip = json.decodeFromString(json.encodeToString(original))
        assertEquals(original, restored)
    }

    // ── RenderConfig composition ──────────────────────────────────────────────

    @Test
    fun `RenderConfig decodes frame and bgmClips alongside existing fields`() {
        val raw = """
            {
              "dubClips":[{"audioFileKey":"audio_0","startMs":0,"durationMs":3000,"volume":1.0}],
              "segments":[{"sourceFileKey":"video_0","type":"VIDEO","order":0,"durationMs":5000,"speedScale":1.5}],
              "imageClips":[],
              "frame":{"width":1080,"height":1920,"backgroundColorHex":"#123456"},
              "bgmClips":[{"audioFileKey":"bgm_0","startMs":500,"volume":0.4}]
            }
        """.trimIndent()

        val cfg: RenderConfig = json.decodeFromString(raw)

        assertEquals(1, cfg.dubClips.size)
        assertEquals(1.5f, cfg.segments!!.first().speedScale)
        assertEquals(1080, cfg.frame!!.width)
        assertEquals("#123456", cfg.frame!!.backgroundColorHex)
        assertEquals(1, cfg.bgmClips.size)
        assertEquals("bgm_0", cfg.bgmClips.first().audioFileKey)
    }

    @Test
    fun `RenderConfig defaults frame and bgmClips for backwards compat`() {
        val raw = """{"dubClips":[],"imageClips":[]}"""
        val cfg: RenderConfig = json.decodeFromString(raw)

        assertNull(cfg.frame)
        assertTrue(cfg.bgmClips.isEmpty())
    }
}
