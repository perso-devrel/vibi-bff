package com.dubcast.bff

import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.separationRoutes
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaTrimmer
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class SeparationRoutesTest {

    private val testDir = File("C:/tmp/test-storage-separation")
    private val appConfig = testAppConfig(storagePath = testDir.path)
    private lateinit var separationService: SeparationService
    private lateinit var stemMixService: StemMixService
    private lateinit var signer: SignedUrlService
    private lateinit var fileStorage: FileStorageService

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        fileStorage = FileStorageService(appConfig.storage).also { it.init() }
        separationService = mockk(relaxed = true)
        stemMixService = mockk(relaxed = true)
        signer = SignedUrlService(appConfig.separation.signingSecret)
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
        unmockkAll()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
        }
        routing {
            route("/api/v2") {
                separationRoutes(separationService, stemMixService, signer, fileStorage, appConfig)
            }
        }
        block()
    }

    // GET /separate/{jobId} with unknown id → 404
    @Test
    fun `GET unknown separation job returns 404`() = testApp {
        every { separationService.getJob("no-such") } returns null
        val response = client.get("/api/v2/separate/no-such")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // GET stem with missing token → 400 (IllegalArgumentException)
    @Test
    fun `GET stem without token returns 400`() = testApp {
        val response = client.get("/api/v2/separate/sep-x/stem/background")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // GET stem with invalid token → 403
    @Test
    fun `GET stem with invalid token returns 403`() = testApp {
        val response = client.get("/api/v2/separate/sep-x/stem/background?token=bogus.sig")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // GET stem with valid token but missing job → 404
    @Test
    fun `GET stem with valid token but missing job returns 404`() = testApp {
        val token = signer.sign("sep-y", "background", 60)
        every { separationService.getJob("sep-y") } returns null
        val response = client.get("/api/v2/separate/sep-y/stem/background?token=$token")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // POST mix when separation is not READY → 409
    @Test
    fun `POST mix on non-ready job returns 409`() = testApp {
        every { separationService.reserveForMix("sep-z", any()) } returns null
        every { stemMixService.newJobId() } returns "mix-abcd"

        val response = client.post("/api/v2/separate/sep-z/mix") {
            contentType(ContentType.Application.Json)
            setBody("""{"stems":[{"stemId":"background","volume":1.0}]}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // SeparationSpec 생성자 검증 — mediaType 오류
    @Test
    fun `SeparationSpec rejects invalid mediaType`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(mediaType = "AUDIOVIDEO", numberOfSpeakers = 1)
        }
        assertTrue(ex.message!!.contains("mediaType"))
    }

    // SeparationSpec 생성자 검증 — numberOfSpeakers 범위
    @Test
    fun `SeparationSpec rejects out-of-range numberOfSpeakers`() {
        assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(mediaType = "VIDEO", numberOfSpeakers = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(mediaType = "VIDEO", numberOfSpeakers = 11)
        }
    }

    // StemMixRequest 검증 — volume 음수
    @Test
    fun `StemMixRequest rejects negative volume`() {
        assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.StemMixRequest(
                stems = listOf(com.dubcast.bff.model.StemMixSelection("background", -0.1f))
            )
        }
    }

    // StemMixRequest 검증 — 빈 stems
    @Test
    fun `StemMixRequest rejects empty stems`() {
        assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.StemMixRequest(stems = emptyList())
        }
    }

    // SeparationSpec trim 검증 — 한쪽만 지정하면 partial_trim_range
    @Test
    fun `SeparationSpec rejects partial trim (only start)`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(
                mediaType = "VIDEO", numberOfSpeakers = 1, trimStartMs = 1000
            )
        }
        assertEquals("partial_trim_range", ex.message)
    }

    @Test
    fun `SeparationSpec rejects partial trim (only end)`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(
                mediaType = "VIDEO", numberOfSpeakers = 1, trimEndMs = 1000
            )
        }
        assertEquals("partial_trim_range", ex.message)
    }

    // SeparationSpec trim 검증 — 역순 / 너무 짧음 / 음수
    @Test
    fun `SeparationSpec rejects reversed trim range`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(
                mediaType = "VIDEO", numberOfSpeakers = 1,
                trimStartMs = 5000, trimEndMs = 2000,
            )
        }
        assertEquals("trim_range_invalid", ex.message)
    }

    @Test
    fun `SeparationSpec rejects trim range shorter than 500ms`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(
                mediaType = "VIDEO", numberOfSpeakers = 1,
                trimStartMs = 1000, trimEndMs = 1200,
            )
        }
        assertEquals("trim_range_too_short", ex.message)
    }

    @Test
    fun `SeparationSpec rejects negative trimStartMs`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            com.dubcast.bff.model.SeparationSpec(
                mediaType = "VIDEO", numberOfSpeakers = 1,
                trimStartMs = -1, trimEndMs = 1000,
            )
        }
        assertEquals("trim_start_negative", ex.message)
    }

    @Test
    fun `SeparationSpec accepts valid trim range`() {
        val spec = com.dubcast.bff.model.SeparationSpec(
            mediaType = "VIDEO", numberOfSpeakers = 2,
            trimStartMs = 2000, trimEndMs = 8500,
        )
        assertEquals(2000L, spec.trimStartMs)
        assertEquals(8500L, spec.trimEndMs)
    }

    // SeparationSpec — trim 필드 없으면 기존과 동일 (backward compatible)
    @Test
    fun `SeparationSpec without trim fields is valid`() {
        val spec = com.dubcast.bff.model.SeparationSpec(
            mediaType = "VIDEO", numberOfSpeakers = 1,
        )
        assertNull(spec.trimStartMs)
        assertNull(spec.trimEndMs)
    }

    // ── POST /separate trim error wire format ────────────────────────────────

    private suspend fun postSeparate(
        client: io.ktor.client.HttpClient,
        specJson: String,
    ) = client.post("/api/v2/separate") {
        setBody(MultiPartFormDataContent(formData {
            append("file", "fake".toByteArray(), Headers.build {
                append(HttpHeaders.ContentType, "audio/mpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"t.mp3\"")
            })
            append("spec", specJson)
        }))
    }

    // trimEndMs > probed duration → 400 trim_end_exceeds_duration with detail
    @Test
    fun `POST separate with trim exceeding duration returns structured 400`() = testApp {
        mockkObject(MediaTrimmer)
        every { MediaTrimmer.probeDurationMs(any()) } returns 5_000L

        val response = postSeparate(
            client,
            """{"mediaType":"AUDIO","numberOfSpeakers":1,"trimStartMs":1000,"trimEndMs":10000}""",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("trim_end_exceeds_duration", body["error"]!!.jsonPrimitive.content)
        val detail = body["detail"]!!.jsonPrimitive.content
        assertTrue(detail.contains("trimEndMs=10000"), "detail should echo trimEndMs")
        assertTrue(detail.contains("duration=5000"), "detail should echo probed duration")
        verify(exactly = 0) { separationService.submit(any(), any()) }
    }

    // probe returns null (ffprobe unavailable / corrupt file) → 500 ffmpeg_error
    @Test
    fun `POST separate with probe failure returns 500 ffmpeg_error`() = testApp {
        mockkObject(MediaTrimmer)
        every { MediaTrimmer.probeDurationMs(any()) } returns null

        val response = postSeparate(
            client,
            """{"mediaType":"AUDIO","numberOfSpeakers":1,"trimStartMs":0,"trimEndMs":2000}""",
        )

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ffmpeg_error", body["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { separationService.submit(any(), any()) }
    }

    // ffmpeg trim itself fails → 500 ffmpeg_error
    @Test
    fun `POST separate with trim failure returns 500 ffmpeg_error`() = testApp {
        mockkObject(MediaTrimmer)
        every { MediaTrimmer.probeDurationMs(any()) } returns 10_000L
        every { MediaTrimmer.trim(any(), any(), any(), any()) } returns false

        val response = postSeparate(
            client,
            """{"mediaType":"AUDIO","numberOfSpeakers":1,"trimStartMs":1000,"trimEndMs":3000}""",
        )

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ffmpeg_error", body["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { separationService.submit(any(), any()) }
    }

    // No trim fields → MediaTrimmer never consulted, submit called directly
    @Test
    fun `POST separate without trim bypasses MediaTrimmer`() = testApp {
        mockkObject(MediaTrimmer)
        every { separationService.submit(any(), any()) } returns "sep-ok"

        val response = postSeparate(
            client,
            """{"mediaType":"AUDIO","numberOfSpeakers":1}""",
        )

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify(exactly = 0) { MediaTrimmer.probeDurationMs(any()) }
        verify(exactly = 0) { MediaTrimmer.trim(any(), any(), any(), any()) }
        verify(exactly = 1) { separationService.submit(any(), any()) }
    }
}
