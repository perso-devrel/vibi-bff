package com.vibi.bff

import com.vibi.bff.model.BgmClip
import com.vibi.bff.model.FrameConfig
import com.vibi.bff.model.RenderConfig
import com.vibi.bff.model.RenderInputCacheResponse
import com.vibi.bff.model.Segment
import com.vibi.bff.plugins.AppJson
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
        // 구버전 클라이언트 호환: dubClips/imageClips/audioOverrideKey 같은 제거된 필드는
        // ignoreUnknownKeys=true 로 묵시 무시.
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

        assertEquals(1.5f, cfg.segments!!.first().speedScale)
        assertEquals(1080, cfg.frame!!.width)
        assertEquals("#123456", cfg.frame!!.backgroundColorHex)
        assertEquals(1, cfg.bgmClips.size)
        assertEquals("bgm_0", cfg.bgmClips.first().audioFileKey)
    }

    @Test
    fun `RenderConfig defaults frame and bgmClips for backwards compat`() {
        val raw = """{}"""
        val cfg: RenderConfig = json.decodeFromString(raw)

        assertNull(cfg.frame)
        assertTrue(cfg.bgmClips.isEmpty())
    }

    // ── separationDirectives wire format (mobile sends selections[]) ────────

    @Test
    fun `RenderConfig decodes separationDirectives with selections from mobile`() {
        val raw = """
            {
              "separationDirectives":[
                {
                  "id":"d1",
                  "rangeStartMs":1000,
                  "rangeEndMs":4000,
                  "numberOfSpeakers":2,
                  "muteOriginalSegmentAudio":true,
                  "selections":[
                    {"stemId":"speaker_0","audioUrl":"/api/v2/separate/sep-x/stem/speaker_0?token=t.s","volume":0.8},
                    {"stemId":"speaker_1","audioUrl":"https://cdn.example/extern.mp3","volume":1.0}
                  ]
                }
              ]
            }
        """.trimIndent()

        val cfg: RenderConfig = json.decodeFromString(raw)
        assertEquals(1, cfg.separationDirectives.size)
        val d = cfg.separationDirectives.first()
        assertEquals("d1", d.id)
        assertEquals(1000L, d.rangeStartMs)
        assertEquals(4000L, d.rangeEndMs)
        assertTrue(d.muteOriginalSegmentAudio)
        assertEquals(2, d.selections.size)
        assertEquals("speaker_0", d.selections[0].stemId)
        assertEquals(0.8f, d.selections[0].volume)
        assertEquals("https://cdn.example/extern.mp3", d.selections[1].audioUrl)
    }

    @Test
    fun `RenderConfig separationDirectives defaults to empty`() {
        val raw = """{}"""
        val cfg: RenderConfig = json.decodeFromString(raw)
        assertTrue(cfg.separationDirectives.isEmpty())
    }

    @Test
    fun `RenderConfig decodes separationDirective sourceOffsetMs (split piece)`() {
        // 모바일 클라이언트가 directive 를 split 했을 때 보내는 추가 필드. 기본 0 = 신규 분리.
        val raw = """
            {
              "separationDirectives":[
                {
                  "id":"d1-back",
                  "rangeStartMs":4000,
                  "rangeEndMs":7000,
                  "numberOfSpeakers":2,
                  "muteOriginalSegmentAudio":true,
                  "selections":[],
                  "sourceOffsetMs":10000
                },
                {
                  "id":"d2-original",
                  "rangeStartMs":0,
                  "rangeEndMs":3000,
                  "numberOfSpeakers":1,
                  "muteOriginalSegmentAudio":false,
                  "selections":[]
                }
              ]
            }
        """.trimIndent()
        val cfg: RenderConfig = json.decodeFromString(raw)
        assertEquals(10_000L, cfg.separationDirectives[0].sourceOffsetMs)
        assertEquals(0L, cfg.separationDirectives[1].sourceOffsetMs)  // default
    }

    // ── outputKind — Phase 1.5 audio render path opt-in ─────────────────────

    @Test
    fun `RenderConfig defaults outputKind to video`() {
        val raw = """{}"""
        val cfg: RenderConfig = json.decodeFromString(raw)
        assertEquals("video", cfg.outputKind)
    }

    @Test
    fun `RenderConfig accepts outputKind audio`() {
        val raw = """{"outputKind":"audio"}"""
        val cfg: RenderConfig = json.decodeFromString(raw)
        assertEquals("audio", cfg.outputKind)
    }

    @Test
    fun `RenderConfig rejects invalid outputKind`() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<RenderConfig>("""{"outputKind":"banana"}""")
        }
    }

    // ── RenderInputCacheResponse — multipart-cache wire shape ────────────────

    @Test
    fun `RenderInputCacheResponse encodes all fields with camelCase`() {
        val original = RenderInputCacheResponse(
            inputId = "0123456789abcdef0123456789abcdef",
            expiresAt = 1714502400000L,
            videoSizeBytes = 12_345_678L,
        )
        val encoded = json.encodeToString(original)
        // Mobile parses by camelCase field names — snake/camel mix would break it.
        assertTrue(encoded.contains("\"inputId\""), "inputId should be camelCase: $encoded")
        assertTrue(encoded.contains("\"expiresAt\""), "expiresAt should be camelCase: $encoded")
        assertTrue(encoded.contains("\"videoSizeBytes\""), "videoSizeBytes should be camelCase: $encoded")
        val restored: RenderInputCacheResponse = json.decodeFromString(encoded)
        assertEquals(original, restored)
    }
}
