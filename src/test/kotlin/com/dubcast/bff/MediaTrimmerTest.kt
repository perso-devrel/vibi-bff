package com.dubcast.bff

import com.dubcast.bff.service.MediaTrimmer
import java.io.File
import kotlin.test.*

/**
 * Integration-level tests: shell out to ffmpeg/ffprobe on PATH. Generates
 * a short synthetic mp3 with `ffmpeg -f lavfi -i sine=...` so the suite
 * doesn't depend on committed media fixtures.
 */
class MediaTrimmerTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "media-trimmer-test")
    private lateinit var sourceMp3: File

    @BeforeTest
    fun setup() {
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        sourceMp3 = File(tmpDir, "source.mp3")
        // 5s sine tone → predictable duration for assertions.
        val genCmd = listOf(
            "ffmpeg", "-y", "-f", "lavfi",
            "-i", "sine=frequency=440:duration=5",
            "-c:a", "libmp3lame", "-b:a", "128k",
            sourceMp3.absolutePath,
        )
        val exit = ProcessBuilder(genCmd).redirectErrorStream(true).start().waitFor()
        check(exit == 0 && sourceMp3.exists()) { "ffmpeg fixture generation failed (exit $exit)" }
    }

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `probeDurationMs reports ~5s for 5s source`() {
        val dur = MediaTrimmer.probeDurationMs(sourceMp3)
        assertNotNull(dur)
        // MP3 frame boundaries can add ~50ms padding at either end.
        assertTrue(dur!! in 4800..5300, "expected ~5000ms, got $dur")
    }

    @Test
    fun `probeDurationMs returns null for non-media input`() {
        val bogus = File(tmpDir, "bogus.mp3").apply { writeText("not audio") }
        assertNull(MediaTrimmer.probeDurationMs(bogus))
    }

    @Test
    fun `trim produces a cut whose duration matches window size`() {
        val out = File(tmpDir, "trimmed.mp3")
        val ok = MediaTrimmer.trim(sourceMp3, 1000, 3500, out)
        assertTrue(ok)
        assertTrue(out.exists() && out.length() > 0)
        val dur = MediaTrimmer.probeDurationMs(out)
        assertNotNull(dur)
        // Target 2500ms; allow wider slack because stream-copy snaps to the
        // nearest frame boundary at both ends.
        assertTrue(dur!! in 2000..3000, "expected ~2500ms, got $dur")
    }

    @Test
    fun `trim rejects non-positive window`() {
        val out = File(tmpDir, "invalid.mp3")
        assertFailsWith<IllegalArgumentException> {
            MediaTrimmer.trim(sourceMp3, 3000, 1000, out)
        }
    }
}
