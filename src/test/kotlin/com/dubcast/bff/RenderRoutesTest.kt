package com.dubcast.bff

import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.renderRoutes
import com.dubcast.bff.service.AudioPart
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.RenderInputCacheService
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

/**
 * Coverage for the render input-cache endpoint and inputId option on /render.
 *
 * The actual ffmpeg-spawning render path is NOT exercised here — RenderService
 * has no unit tests by policy (real ffmpeg). We mock it.
 */
class RenderRoutesTest {

    private val testDir = File("C:/tmp/test-storage-render-routes")
    private val appConfig = testAppConfig(storagePath = testDir.path)
    private lateinit var fileStorage: FileStorageService
    private lateinit var renderService: RenderService
    private lateinit var stemMixService: StemMixService
    private lateinit var separationService: SeparationService
    private lateinit var signer: SignedUrlService
    private lateinit var inputCache: RenderInputCacheService
    private lateinit var httpClient: HttpClient

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        fileStorage = FileStorageService(appConfig.storage)
        renderService = mockk(relaxed = true)
        stemMixService = mockk(relaxed = true)
        separationService = mockk(relaxed = true)
        signer = SignedUrlService(appConfig.separation.signingSecret)
        inputCache = RenderInputCacheService(
            baseDir = File(testDir, "render-input-cache"),
            ttlMs = 24 * 60 * 60 * 1000L,
        )
        httpClient = HttpClient(CIO)
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
        unmockkAll()
        httpClient.close()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
        }
        routing {
            route("/api/v2") {
                renderRoutes(
                    renderService, fileStorage, stemMixService,
                    separationService, signer, httpClient, inputCache,
                )
            }
        }
        block()
    }

    // ── /render/inputs ────────────────────────────────────────────────────────

    @Test
    fun `POST inputs returns inputId derived from sha256 prefix`() = testApp {
        val videoBytes = ByteArray(1024) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(videoBytes)
            .take(16).joinToString("") { "%02x".format(it) }

        val response = client.post("/api/v2/render/inputs") {
            setBody(MultiPartFormDataContent(formData {
                append("video", videoBytes, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"src.mp4\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(expected, body["inputId"]!!.jsonPrimitive.content)
        assertEquals(1024L, body["videoSizeBytes"]!!.jsonPrimitive.long)
        assertEquals(0, body["audioCount"]!!.jsonPrimitive.int)
        assertTrue(body["expiresAt"]!!.jsonPrimitive.long > System.currentTimeMillis())
    }

    @Test
    fun `POST inputs is idempotent on identical video bytes (dedup)`() = testApp {
        val videoBytes = ByteArray(2048) { (it % 17).toByte() }

        val first = client.post("/api/v2/render/inputs") {
            setBody(MultiPartFormDataContent(formData {
                append("video", videoBytes, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"a.mp4\"")
                })
            }))
        }
        val second = client.post("/api/v2/render/inputs") {
            setBody(MultiPartFormDataContent(formData {
                append("video", videoBytes, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"b.mp4\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        val a = AppJson.parseToJsonElement(first.bodyAsText()).jsonObject
        val b = AppJson.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(
            a["inputId"]!!.jsonPrimitive.content,
            b["inputId"]!!.jsonPrimitive.content,
            "same bytes must dedup to the same inputId",
        )
    }

    @Test
    fun `POST inputs without video part returns 400`() = testApp {
        val response = client.post("/api/v2/render/inputs") {
            setBody(MultiPartFormDataContent(formData {
                append("audio_0", "fake".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "audio/mpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"a.mp3\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST inputs persists segment audios under form-field name`() = testApp {
        val videoBytes = ByteArray(512) { 1 }
        val audioBytes = "audio-bytes".toByteArray()
        val response = client.post("/api/v2/render/inputs") {
            setBody(MultiPartFormDataContent(formData {
                append("video", videoBytes, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"v.mp4\"")
                })
                append("audio_0", audioBytes, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"voice.mp3\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val inputId = body["inputId"]!!.jsonPrimitive.content
        assertEquals(1, body["audioCount"]!!.jsonPrimitive.int)

        val cached = inputCache.resolve(inputId)
        assertNotNull(cached)
        assertTrue(cached!!.audioFilesByFormField.containsKey("audio_0"))
        assertEquals("audio-bytes", cached.audioFilesByFormField["audio_0"]!!.readText())
    }

    // ── /render with inputId form field ──────────────────────────────────────

    @Test
    fun `POST render with unknown inputId returns 400 with clear error`() = testApp {
        val response = client.post("/api/v2/render") {
            setBody(MultiPartFormDataContent(formData {
                append("inputId", "deadbeef".repeat(4)) // 32 hex chars but not in cache
                append("config", """{"dubClips":[],"videoDurationMs":3000}""")
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(
            body["error"]!!.jsonPrimitive.content.contains("inputId"),
            "error must mention inputId so mobile knows to re-upload: $body",
        )
    }

    @Test
    fun `POST render with valid inputId resolves video from cache`() = testApp {
        // Seed the cache directly with a known video.
        val videoBytes = ByteArray(64) { it.toByte() }
        val cached = inputCache.save(
            videoFileName = "test.mp4",
            videoStream = ByteArrayInputStream(videoBytes),
            audios = emptyList(),
            maxVideoBytes = 10_000,
        )

        every {
            renderService.submitRender(
                legacyVideoFile = any(),
                videoFiles = any(),
                segmentImageFiles = any(),
                audioFiles = any(),
                imageFiles = any(),
                subtitlesFile = any(),
                dubClips = any(),
                imageClips = any(),
                videoDurationMs = any(),
                segments = any(),
                bgmAudioFiles = any(),
                bgmClips = any(),
                frame = any(),
                audioOverrideFile = any(),
                separationDirectives = any(),
                inputFilesToCleanup = any(),
            )
        } returns "render-cached-1"

        val response = client.post("/api/v2/render") {
            setBody(MultiPartFormDataContent(formData {
                append("inputId", cached.inputId)
                append("config", """{"dubClips":[],"videoDurationMs":3000}""")
            }))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("render-cached-1", body["jobId"]!!.jsonPrimitive.content)

        // Verify the resolved video file was forwarded to the render service.
        verify(exactly = 1) {
            renderService.submitRender(
                legacyVideoFile = matchNullable<File> { it != null && it.absolutePath == cached.videoFile.absolutePath },
                videoFiles = any(),
                segmentImageFiles = any(),
                audioFiles = any(),
                imageFiles = any(),
                subtitlesFile = any(),
                dubClips = any(),
                imageClips = any(),
                videoDurationMs = any(),
                segments = any(),
                bgmAudioFiles = any(),
                bgmClips = any(),
                frame = any(),
                audioOverrideFile = any(),
                separationDirectives = any(),
                // Cache-resident files must NOT be in cleanup list — other variants
                // are still using them.
                inputFilesToCleanup = match { list ->
                    list.none { f -> f.absolutePath == cached.videoFile.absolutePath }
                },
            )
        }
    }

    @Test
    fun `POST render with inputId resolves cached audio for dubClip`() = testApp {
        val cached = inputCache.save(
            videoFileName = "v.mp4",
            videoStream = ByteArrayInputStream(ByteArray(32)),
            audios = listOf(
                AudioPart(
                    formFieldName = "audio_0",
                    originalFileName = "vo.mp3",
                    stream = ByteArrayInputStream("voice".toByteArray()),
                ),
            ),
            maxVideoBytes = 10_000,
        )

        every {
            renderService.submitRender(
                any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns "render-cached-2"

        val cfg = """
            {
              "dubClips":[{"audioFileKey":"audio_0","startMs":0,"durationMs":2000,"volume":1.0}],
              "videoDurationMs":3000
            }
        """.trimIndent()

        val response = client.post("/api/v2/render") {
            setBody(MultiPartFormDataContent(formData {
                append("inputId", cached.inputId)
                append("config", cfg)
            }))
        }

        // Validation passes only if the cached audio_0 was injected. A 200
        // here proves audio key resolution went through the cache.
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
