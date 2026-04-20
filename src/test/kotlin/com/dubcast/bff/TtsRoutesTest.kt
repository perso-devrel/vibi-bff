package com.dubcast.bff

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.config.StorageConfig
import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.ttsRoutes
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class TtsRoutesTest {

    private val testDir = File("C:/tmp/test-storage-tts")
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
                ttsRoutes(elevenLabsClient, fileStorage, appConfig)
            }
        }
        block()
    }

    @Test
    fun `POST tts returns audio URL`() = testApp {
        val fakeAudio = "fake-audio-bytes".toByteArray()
        coEvery {
            elevenLabsClient.textToSpeech(
                voiceId = "voice-1",
                text = "hello",
                targetFile = any(),
                modelId = "eleven_multilingual_v2",
                stability = 0.5f,
                similarityBoost = 0.75f,
                languageCode = null,
            )
        } coAnswers {
            val file = arg<File>(2)
            file.parentFile.mkdirs()
            file.writeBytes(fakeAudio)
        }

        val response = client.post("/api/v1/tts") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hello","voiceId":"voice-1"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val audioUrl = body["audioUrl"]!!.jsonPrimitive.content
        assertTrue(audioUrl.startsWith("http://localhost:8080/files/tts/"))
        assertTrue(audioUrl.endsWith(".mp3"))
    }

    @Test
    fun `POST tts with custom parameters`() = testApp {
        val fakeAudio = "audio".toByteArray()
        coEvery {
            elevenLabsClient.textToSpeech(
                voiceId = "voice-2",
                text = "hi",
                targetFile = any(),
                modelId = "eleven_turbo_v2_5",
                stability = 0.8f,
                similarityBoost = 0.9f,
                languageCode = "ko",
            )
        } coAnswers {
            val file = arg<File>(2)
            file.parentFile.mkdirs()
            file.writeBytes(fakeAudio)
        }

        val response = client.post("/api/v1/tts") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hi","voiceId":"voice-2","modelId":"eleven_turbo_v2_5","stability":0.8,"similarityBoost":0.9,"languageCode":"ko"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
