package com.dubcast.bff

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.config.StorageConfig
import com.dubcast.bff.model.ElevenLabsLipSyncResponse
import com.dubcast.bff.model.ElevenLabsLipSyncStatus
import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.lipSyncRoutes
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.*

class LipSyncRoutesTest {

    private val testDir = File("C:/tmp/test-storage-lipsync")
    private lateinit var elevenLabsClient: ElevenLabsClient
    private lateinit var fileStorage: FileStorageService
    private val appConfig = AppConfig(
        elevenLabs = ElevenLabsConfig(apiKey = "test-key", baseUrl = "https://api.elevenlabs.io"),
        storage = StorageConfig(basePath = testDir.path),
        baseUrl = "http://localhost:8080",
    )

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        elevenLabsClient = mockk()
        fileStorage = FileStorageService(appConfig.storage).also { it.init() }
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
            route("/api/v1") {
                lipSyncRoutes(elevenLabsClient, fileStorage, appConfig)
            }
        }
        block()
    }

    private fun createTestFiles(): Pair<String, String> {
        val videoBlobPath = fileStorage.saveUpload("test.mp4", ByteArrayInputStream("fake video".toByteArray()))
        val audioBlobPath = fileStorage.saveUpload("test.mp3", ByteArrayInputStream("fake audio".toByteArray()))
        return videoBlobPath to audioBlobPath
    }

    @Test
    fun `POST lipsync creates job and returns processing status`() = testApp {
        val (videoBlobPath, audioBlobPath) = createTestFiles()

        coEvery { elevenLabsClient.createLipSync(any(), any()) } returns ElevenLabsLipSyncResponse(
            id = "ls-123",
            status = "processing",
        )

        val response = client.post("/api/v1/lipsync") {
            contentType(ContentType.Application.Json)
            setBody("""{"videoBlobPath":"$videoBlobPath","audioBlobPath":"$audioBlobPath"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ls-123", body["id"]!!.jsonPrimitive.content)
        assertEquals("processing", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET lipsync status returns processing`() = testApp {
        coEvery { elevenLabsClient.getLipSyncStatus("ls-123") } returns ElevenLabsLipSyncStatus(
            id = "ls-123",
            status = "in_progress",
        )

        val response = client.get("/api/v1/lipsync/ls-123/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("processing", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET lipsync status returns completed with video URL`() = testApp {
        coEvery { elevenLabsClient.getLipSyncStatus("ls-456") } returns ElevenLabsLipSyncStatus(
            id = "ls-456",
            status = "dubbed",
        )
        coEvery { elevenLabsClient.downloadLipSyncResult("ls-456", "en", any()) } answers {
            val file = thirdArg<File>()
            file.parentFile?.mkdirs()
            file.writeText("lip synced video")
        }

        val response = client.get("/api/v1/lipsync/ls-456/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("completed", body["status"]!!.jsonPrimitive.content)
        val videoUrl = body["outputVideoUrl"]!!.jsonPrimitive.content
        assertTrue(videoUrl.contains("/files/lipsync/ls-456.mp4"))
    }

    @Test
    fun `GET lipsync status returns failed`() = testApp {
        coEvery { elevenLabsClient.getLipSyncStatus("ls-789") } returns ElevenLabsLipSyncStatus(
            id = "ls-789",
            status = "failed",
            error = "Processing error",
        )

        val response = client.get("/api/v1/lipsync/ls-789/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("failed", body["status"]!!.jsonPrimitive.content)
        assertEquals("Processing error", body["error"]!!.jsonPrimitive.content)
    }
}
