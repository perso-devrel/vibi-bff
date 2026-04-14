package com.dubcast.bff

import com.dubcast.bff.model.ElevenLabsVoice
import com.dubcast.bff.model.ElevenLabsVoicesResponse
import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.voiceRoutes
import com.dubcast.bff.service.ElevenLabsClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import kotlin.test.*

class VoiceRoutesTest {

    private lateinit var elevenLabsClient: ElevenLabsClient

    @BeforeTest
    fun setup() {
        elevenLabsClient = mockk()
    }

    @AfterTest
    fun cleanup() {
        unmockkAll()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
        }
        routing {
            route("/api/v1") {
                voiceRoutes(elevenLabsClient)
            }
        }
        block()
    }

    @Test
    fun `GET voices returns voice list`() = testApp {
        coEvery { elevenLabsClient.getVoices() } returns ElevenLabsVoicesResponse(
            voices = listOf(
                ElevenLabsVoice(
                    voiceId = "voice-1",
                    name = "Rachel",
                    previewUrl = "https://example.com/preview.mp3",
                    category = "premade",
                    labels = mapOf("accent" to "american"),
                ),
                ElevenLabsVoice(
                    voiceId = "voice-2",
                    name = "Josh",
                    category = "premade",
                ),
            )
        )

        val response = client.get("/api/v1/voices")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val voices = body["voices"]!!.jsonArray
        assertEquals(2, voices.size)
        assertEquals("Rachel", voices[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("voice-1", voices[0].jsonObject["voiceId"]!!.jsonPrimitive.content)
        assertEquals("premade", voices[0].jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals("Josh", voices[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET voices returns empty list`() = testApp {
        coEvery { elevenLabsClient.getVoices() } returns ElevenLabsVoicesResponse(voices = emptyList())

        val response = client.get("/api/v1/voices")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(0, body["voices"]!!.jsonArray.size)
    }
}
