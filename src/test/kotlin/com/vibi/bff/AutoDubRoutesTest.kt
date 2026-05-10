package com.vibi.bff

import com.vibi.bff.plugins.configureErrorHandling
import com.vibi.bff.plugins.configureSerialization
import com.vibi.bff.routes.autoDubRoutes
import com.vibi.bff.service.AutoDubService
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SignedUrlService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import java.io.File
import kotlin.test.*

class AutoDubRoutesTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "vibi-test-storage-autodub").apply { mkdirs() }
    private val appConfig = testAppConfig(storagePath = testDir.path)
    private lateinit var autoDubService: AutoDubService
    private lateinit var signer: SignedUrlService
    private lateinit var fileStorage: FileStorageService
    private lateinit var renderService: RenderService
    private lateinit var mediaSourceResolver: MediaSourceResolver

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        fileStorage = FileStorageService(appConfig.storage)
        autoDubService = mockk(relaxed = true)
        signer = SignedUrlService(appConfig.separation.signingSecret)
        renderService = mockk(relaxed = true)
        mediaSourceResolver = MediaSourceResolver(renderService, fileStorage.editedSourceDir)
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
                autoDubRoutes(autoDubService, signer, fileStorage, appConfig, mediaSourceResolver)
            }
        }
        block()
    }

    /** Legacy multipart `file` 케이스. */
    @Test
    fun `POST autodub with file part submits via storage upload`() = testApp {
        every { autoDubService.submit(any(), any()) } returns "ad-from-file"

        val response = client.post("/api/v2/autodub") {
            setBody(MultiPartFormDataContent(formData {
                append("file", "fake-bytes".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"v.mp4\"")
                })
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCode":"ko"}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify(exactly = 0) { renderService.acquireRenderOutputCopy(any(), any()) }
        verify(exactly = 1) { autoDubService.submit(any(), any()) }
    }

    /** spec.editedRenderJobId valid → owned copy 가 autoDubService 로 전달. */
    @Test
    fun `POST autodub with editedRenderJobId uses render output copy`() = testApp {
        val copy = File(fileStorage.editedSourceDir, "source-fixture.mp4").apply {
            parentFile.mkdirs()
            writeText("fake-mp4")
        }
        every { renderService.acquireRenderOutputCopy("render-ok", any()) } returns copy
        every { autoDubService.submit(any(), any()) } returns "ad-from-render"

        val response = client.post("/api/v2/autodub") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCode":"ko","editedRenderJobId":"render-ok"}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify(exactly = 1) {
            renderService.acquireRenderOutputCopy("render-ok", fileStorage.editedSourceDir)
        }
        verify(exactly = 1) { autoDubService.submit(copy, any()) }
    }

    /** unknown editedRenderJobId → 400. */
    @Test
    fun `POST autodub with unknown editedRenderJobId returns 400`() = testApp {
        every { renderService.acquireRenderOutputCopy("missing", any()) } returns null

        val response = client.post("/api/v2/autodub") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCode":"ko","editedRenderJobId":"missing"}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { autoDubService.submit(any(), any()) }
    }

    /** file 도 editedRenderJobId 도 없으면 400. */
    @Test
    fun `POST autodub without file or editedRenderJobId returns 400`() = testApp {
        val response = client.post("/api/v2/autodub") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCode":"ko"}""",
                )
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { autoDubService.submit(any(), any()) }
    }
}
