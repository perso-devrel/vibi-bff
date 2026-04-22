package com.dubcast.bff

import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.separationRoutes
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
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
}
