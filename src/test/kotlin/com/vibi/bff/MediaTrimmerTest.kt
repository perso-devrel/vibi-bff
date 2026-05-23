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
        // ffmpeg 미설치 환경 (예: 일부 CI runner) 에서는 skip — fail 대신 assume false.
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH")
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
    fun `probeDurationMs reports ~5s for 5s source`() = runBlocking {
        val dur = MediaTrimmer.probeDurationMs(sourceMp3)
        assertNotNull(dur)
        // MP3 frame boundaries can add ~50ms padding at either end.
        assertTrue(dur!! in 4800..5300, "expected ~5000ms, got $dur")
    }

    @Test
    fun `probeDurationMs returns null for non-media input`() = runBlocking {
        val bogus = File(tmpDir, "bogus.mp3").apply { writeText("not audio") }
        assertNull(MediaTrimmer.probeDurationMs(bogus))
    }

    @Test
    fun `trim produces a sample-accurate wav cut whose duration matches window size`() = runBlocking {
        val out = File(tmpDir, "trimmed.${MediaTrimmer.OUTPUT_EXTENSION}")
        val ok = MediaTrimmer.trim(sourceMp3, 1000, 3500, out)
        assertTrue(ok)
        assertTrue(out.exists() && out.length() > 0)
        val dur = MediaTrimmer.probeDurationMs(out)
        assertNotNull(dur)
        // Target 2500ms. PCM WAV + output-side -ss/-t 는 sample-accurate (≤ 1 sample @ source rate).
        // 다만 source 가 MP3 라 입력 측 디코드에서 LAME encoder delay (~26ms) 가 흡수되며 정수
        // ms 환산 반올림이 약간 더해질 수 있어 ±50ms 정도로 잡는다.
        assertTrue(dur!! in 2450..2550, "expected ~2500ms, got $dur")
    }

    @Test
    fun `trim rejects non-positive window`(): Unit = runBlocking {
        val out = File(tmpDir, "invalid.${MediaTrimmer.OUTPUT_EXTENSION}")
        assertFailsWith<IllegalArgumentException> {
            MediaTrimmer.trim(sourceMp3, 3000, 1000, out)
        }
    }
}
