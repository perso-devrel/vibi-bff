package com.dubcast.bff

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.config.StorageConfig
import com.dubcast.bff.plugins.AppJson
import com.dubcast.bff.plugins.configureErrorHandling
import com.dubcast.bff.plugins.configureSerialization
import com.dubcast.bff.routes.uploadRoutes
import com.dubcast.bff.service.FileStorageService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class UploadRoutesTest {

    private val testDir = File("C:/tmp/test-storage-upload")

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val fileStorage = FileStorageService(StorageConfig(testDir.path)).also { it.init() }

        application {
            configureSerialization()
            configureErrorHandling()
        }
        routing {
            route("/api/v1") {
                uploadRoutes(fileStorage)
            }
        }

        block()
    }

    @Test
    fun `upload valid mp4 file returns blob path`() = testApp {
        val response = client.post("/api/v1/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("file", "fake video content".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"test.mp4\"")
                })
            }))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["blobPath"]!!.jsonPrimitive.content.startsWith("uploads/"))
    }

    @Test
    fun `upload without file returns 400`() = testApp {
        val response = client.post("/api/v1/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("name", "no file here")
            }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `upload unsupported file type returns 400`() = testApp {
        val response = client.post("/api/v1/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("file", "not a video".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"malware.exe\"")
                })
            }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Unsupported file type"))
    }
}
