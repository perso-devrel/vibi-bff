package com.dubcast.bff

import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.subtitleRoutes
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaSourceResolver
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SignedUrlService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import java.io.File
import kotlin.test.*

class SubtitleRoutesTest {

    private val testDir = File("C:/tmp/test-storage-subtitles")
    private val appConfig = testAppConfig(storagePath = testDir.path)
    private lateinit var subtitleService: AutoSubtitleService
    private lateinit var signer: SignedUrlService
    private lateinit var fileStorage: FileStorageService
    private lateinit var renderService: RenderService
    private lateinit var mediaSourceResolver: MediaSourceResolver

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        fileStorage = FileStorageService(appConfig.storage)
        subtitleService = mockk(relaxed = true)
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
                subtitleRoutes(subtitleService, signer, fileStorage, appConfig, mediaSourceResolver)
            }
        }
        block()
    }

    /** Legacy multipart `file` 케이스 — 기존 동작 유지. */
    @Test
    fun `POST subtitles with file part submits via storage upload`() = testApp {
        every { subtitleService.submit(any(), any()) } returns "sub-from-file"

        val response = client.post("/api/v2/subtitles") {
            setBody(MultiPartFormDataContent(formData {
                append("file", "fake-bytes".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"v.mp4\"")
                })
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCodes":["ko"]}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify(exactly = 0) { renderService.acquireRenderOutputCopy(any(), any()) }
        verify(exactly = 1) { subtitleService.submit(any(), any()) }
    }

    /** spec.editedRenderJobId 가 valid → owned copy 가 subtitleService 로 전달. */
    @Test
    fun `POST subtitles with editedRenderJobId uses render output copy`() = testApp {
        val copy = File(fileStorage.editedSourceDir, "source-fixture.mp4").apply {
            parentFile.mkdirs()
            writeText("fake-mp4")
        }
        every { renderService.acquireRenderOutputCopy("render-ok", any()) } returns copy
        every { subtitleService.submit(any(), any()) } returns "sub-from-render"

        val response = client.post("/api/v2/subtitles") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","targetLanguageCodes":["ko"],"editedRenderJobId":"render-ok"}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        verify(exactly = 1) {
            renderService.acquireRenderOutputCopy("render-ok", fileStorage.editedSourceDir)
        }
        verify(exactly = 1) { subtitleService.submit(copy, any()) }
    }

    /** unknown editedRenderJobId → 400. */
    @Test
    fun `POST subtitles with unknown editedRenderJobId returns 400`() = testApp {
        every { renderService.acquireRenderOutputCopy("missing", any()) } returns null

        val response = client.post("/api/v2/subtitles") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en","editedRenderJobId":"missing"}""",
                )
            }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { subtitleService.submit(any(), any()) }
    }

    /** file 도 editedRenderJobId 도 없으면 400. */
    @Test
    fun `POST subtitles without file or editedRenderJobId returns 400`() = testApp {
        val response = client.post("/api/v2/subtitles") {
            setBody(MultiPartFormDataContent(formData {
                append(
                    "spec",
                    """{"mediaType":"VIDEO","sourceLanguageCode":"en"}""",
                )
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { subtitleService.submit(any(), any()) }
    }
}
