package com.vibi.bff

import com.vibi.bff.config.PersoConfig
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.PersoApiException
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.PersoMediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class PersoClientTest {

    private val persoConfig = PersoConfig(
        apiKey = "pk_test_abc",
        baseUrl = "https://api.perso.ai",
        storageBaseUrl = "https://portal-media.perso.ai",
        spaceSeq = 42,
        pollIntervalMs = 1000,
        maxPollMinutes = 5,
        downloadAllowedHosts = setOf("portal-media.perso.ai", "download"),
    )

    private fun clientWith(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): PersoClient {
        val engine = MockEngine { req -> handler(req) }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(AppJson) }
        }
        return PersoClient(persoConfig, http)
    }

    @Test
    fun `getSasToken sends XP-API-KEY and url-encodes filename`() = runBlocking {
        var capturedUrl = ""
        var capturedKey: String? = null
        val client = clientWith { req ->
            capturedUrl = req.url.toString()
            capturedKey = req.headers["XP-API-KEY"]
            respond(
                content = """{"blobSasUrl":"https://blob/x?sig=1","expirationDatetime":"2030-01-01T00:00:00"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sas = client.getSasToken("my file.mp4")
        assertEquals("pk_test_abc", capturedKey)
        assertTrue(capturedUrl.contains("fileName=my+file.mp4") || capturedUrl.contains("fileName=my%20file.mp4"))
        assertEquals("https://blob/x?sig=1", sas.blobSasUrl)
    }

    @Test
    fun `registerMedia strips SAS query string before sending fileUrl`() = runBlocking {
        var bodyText = ""
        val client = clientWith { req ->
            bodyText = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"seq":999,"originalName":"x","size":1000,"durationMs":5000}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val reg = client.registerMedia(
            PersoMediaType.VIDEO,
            sasUrl = "https://blob/x?sig=xyz&se=2030",
            fileName = "x.mp4",
        )
        assertEquals(999L, reg.seq)
        assertTrue(bodyText.contains("\"fileUrl\":\"https://blob/x\""),
            "fileUrl should have query stripped, got: $bodyText")
        assertTrue(bodyText.contains("\"spaceSeq\":42"))
    }

    @Test
    fun `submitTranslate unwraps envelope and returns first project id`() = runBlocking {
        val client = clientWith {
            respond(
                content = """{"result":{"startGenerateProjectIdList":[101,102]}}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val seq = client.submitTranslate(
            mediaSeq = 999,
            isVideoProject = true,
            sourceLanguageCode = "ko",
            targetLanguageCodes = listOf("ko"),
            numberOfSpeakers = 2,
        )
        assertEquals(101L, seq)
    }

    @Test
    fun `submitTranslate throws when project id list is empty`(): Unit = runBlocking {
        val client = clientWith {
            respond(
                content = """{"result":{"startGenerateProjectIdList":[]}}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        assertFailsWith<PersoApiException> {
            client.submitTranslate(1, true, "ko", listOf("ko"), 1)
        }
    }

    @Test
    fun `getProgress parses enveloped response`() = runBlocking {
        val client = clientWith {
            respond(
                content = """{"result":{"projectSeq":101,"progress":65,"progressReason":"Transcribing","hasFailed":false}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val p = client.getProgress(101)
        assertEquals(65, p.progress)
        assertEquals("Transcribing", p.progressReason)
        assertFalse(p.hasFailed)
    }

    @Test
    fun `getDownloadInfo returns availability flags`() = runBlocking {
        val client = clientWith {
            respond(
                content = """{"result":{
                    "hasOriginalVoiceOnly":true,
                    "hasOriginalBackground":true,
                    "hasOriginalSpeakerAudioCollection":true,
                    "hasZipDownload":true
                }}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val info = client.getDownloadInfo(101)
        assertTrue(info.hasOriginalVoiceOnly)
        assertTrue(info.hasOriginalBackground)
        assertTrue(info.hasOriginalSpeakerAudioCollection)
    }

    @Test
    fun `upstream error becomes PersoApiException with status preserved`(): Unit = runBlocking {
        val client = clientWith {
            respond(
                content = """{"error":"quota"}""",
                status = HttpStatusCode(402, "Payment Required"),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ex = assertFailsWith<PersoApiException> { client.getProgress(1) }
        assertEquals(402, ex.statusCode)
    }

    @Test
    fun `streamDownload writes the body to target and cleans tmp on failure`(): Unit = runBlocking {
        val tmp = File.createTempFile("perso-test", ".bin").apply { delete() }
        val client = clientWith {
            respond(
                content = ByteReadChannel("hello".toByteArray()),
                status = HttpStatusCode.OK,
            )
        }
        client.streamDownload("https://download/x", tmp)
        assertTrue(tmp.exists())
        assertEquals("hello", tmp.readText())
        tmp.delete()
    }
}
