package com.vibi.bff

import com.vibi.bff.service.MediaTrimmer
import kotlinx.coroutines.runBlocking
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
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        sourceMp3 = File(tmpDir, "source.mp3")
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
    fun `probeDurationMs reports ~5s for 5s source`() = runBlocking {
        val dur = MediaTrimmer.probeDurationMs(sourceMp3)
        assertNotNull(dur)
        assertTrue(dur!! in 4800..5300, "expected ~5000ms, got $dur")
    }

    @Test
    fun `probeDurationMs returns null for non-media input`() = runBlocking {
        val bogus = File(tmpDir, "bogus.mp3").apply { writeText("not audio") }
        assertNull(MediaTrimmer.probeDurationMs(bogus))
    }
}
