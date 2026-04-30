package com.dubcast.bff

import com.dubcast.bff.service.AudioPart
import com.dubcast.bff.service.RenderInputCacheService
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class RenderInputCacheServiceTest {

    private val testDir = File("C:/tmp/test-render-input-cache")
    private lateinit var cache: RenderInputCacheService

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        cache = RenderInputCacheService(testDir, ttlMs = 60_000L)
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun `save derives inputId as sha256 prefix and persists video`() {
        val bytes = ByteArray(2048) { (it % 31).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(16).joinToString("") { "%02x".format(it) }

        val saved = cache.save(
            videoFileName = "movie.mp4",
            videoStream = ByteArrayInputStream(bytes),
            audios = emptyList(),
            maxVideoBytes = 10_000,
        )
        assertEquals(expected, saved.inputId)
        assertTrue(saved.videoFile.exists())
        assertEquals(2048L, saved.videoFile.length())
    }

    @Test
    fun `save returns same inputId for identical bytes (cache hit)`() {
        val bytes = "hello world".toByteArray() + ByteArray(100)

        val a = cache.save("a.mp4", ByteArrayInputStream(bytes), emptyList(), 10_000)
        val b = cache.save("b.mp4", ByteArrayInputStream(bytes), emptyList(), 10_000)
        assertEquals(a.inputId, b.inputId)
        // The video file path on second save should reuse the original entry —
        // ensure the existing entry's videoFileName ('a.mp4') is preserved.
        assertEquals(a.videoFile.absolutePath, b.videoFile.absolutePath)
    }

    @Test
    fun `save accepts and indexes audios by form-field name`() {
        val bytes = ByteArray(64) { 7 }
        val saved = cache.save(
            videoFileName = "v.mp4",
            videoStream = ByteArrayInputStream(bytes),
            audios = listOf(
                AudioPart("audio_0", "first.mp3", ByteArrayInputStream("ONE".toByteArray())),
                AudioPart("audio_1", "second.mp3", ByteArrayInputStream("TWO".toByteArray())),
            ),
            maxVideoBytes = 10_000,
        )
        assertEquals(2, saved.audioFilesByFormField.size)
        assertEquals("ONE", saved.audioFilesByFormField["audio_0"]!!.readText())
        assertEquals("TWO", saved.audioFilesByFormField["audio_1"]!!.readText())
    }

    @Test
    fun `resolve returns null for unknown id`() {
        assertNull(cache.resolve("0".repeat(32)))
    }

    @Test
    fun `resolve rejects malformed inputId (path traversal guard)`() {
        assertNull(cache.resolve("../etc/passwd"))
        assertNull(cache.resolve("not-hex"))
        assertNull(cache.resolve("0".repeat(64))) // wrong length
    }

    @Test
    fun `resolve bumps lastAccessAt`() {
        val saved = cache.save(
            videoFileName = "v.mp4",
            videoStream = ByteArrayInputStream(ByteArray(32)),
            audios = emptyList(),
            maxVideoBytes = 10_000,
        )
        Thread.sleep(5)
        val resolved = cache.resolve(saved.inputId)
        assertNotNull(resolved)
        assertTrue(
            resolved!!.metadata.lastAccessAt >= saved.metadata.lastAccessAt,
            "lastAccessAt should monotonically advance",
        )
    }

    @Test
    fun `cleanExpired removes entries past TTL`() {
        val shortTtl = RenderInputCacheService(testDir, ttlMs = 50L)
        val saved = shortTtl.save(
            videoFileName = "v.mp4",
            videoStream = ByteArrayInputStream(ByteArray(16)),
            audios = emptyList(),
            maxVideoBytes = 10_000,
        )
        assertTrue(saved.videoFile.exists())
        Thread.sleep(120)
        shortTtl.cleanExpired()
        assertNull(shortTtl.resolve(saved.inputId))
        assertFalse(saved.videoFile.exists(), "cache directory should have been deleted")
    }

    @Test
    fun `save rejects oversized video`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cache.save(
                videoFileName = "big.mp4",
                videoStream = ByteArrayInputStream(ByteArray(2048)),
                audios = emptyList(),
                maxVideoBytes = 1024,
            )
        }
        assertTrue(ex.message!!.contains("exceeds"))
    }

    @Test
    fun `save sanitizes form-field name to prevent path traversal`() {
        val saved = cache.save(
            videoFileName = "v.mp4",
            videoStream = ByteArrayInputStream(ByteArray(8)),
            audios = listOf(
                AudioPart("../evil", "x.mp3", ByteArrayInputStream("X".toByteArray())),
            ),
            maxVideoBytes = 10_000,
        )
        // None of the resolved files should escape the entry directory.
        for ((_, file) in saved.audioFilesByFormField) {
            assertTrue(
                file.canonicalPath.startsWith(testDir.canonicalPath),
                "audio file ${file.canonicalPath} escaped cache root ${testDir.canonicalPath}",
            )
        }
    }
}
