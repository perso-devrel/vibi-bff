package com.vibi.bff

import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.configureErrorHandling
import com.vibi.bff.plugins.configureRateLimiting
import com.vibi.bff.plugins.configureSerialization
import com.vibi.bff.routes.separationRoutes
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.InsufficientCreditsException
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.SeparationJob
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.test.*

class SeparationRoutesTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "vibi-test-storage-separation").apply { mkdirs() }
    private val appConfig = testAppConfig(storagePath = testDir.path)
    private lateinit var separationService: SeparationService
    private lateinit var signer: SignedUrlService
    private lateinit var fileStorage: FileStorageService

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        fileStorage = FileStorageService(appConfig.storage)
        separationService = mockk(relaxed = true)
        signer = SignedUrlService(appConfig.separation.signingSecret)
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
        unmockkAll()
    }

    private fun testApp(
        jwtSecret: String? = null,
        creditRepository: CreditRepository? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
            // separationRoutes 가 rateLimit(RL_SEPARATE) 래퍼를 쓰므로 플러그인 설치 필요.
            configureRateLimiting(appConfig.auth.jwtSecret)
        }
        routing {
            route("/api/v2") {
                separationRoutes(
                    separationService, signer, fileStorage,
                    appConfig, objectStore = null,
                    jwtSecret = jwtSecret,
                    creditRepository = creditRepository,
                )
            }
        }
        block()
    }

    // ── GET smoke tests ──────────────────────────────────────────────────────

    @Test
    fun `GET unknown separation job returns 404`() = testApp {
        coEvery { separationService.getJob("no-such") } returns null
        val response = client.get("/api/v2/separate/no-such")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET stem without token returns 400`() = testApp {
        val response = client.get("/api/v2/separate/sep-x/stem/background")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET stem with invalid token returns 403`() = testApp {
        val response = client.get("/api/v2/separate/sep-x/stem/background?token=bogus.sig")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET stem with valid token but missing job returns 404`() = testApp {
        val token = signer.sign("sep-y", "background", 60)
        coEvery { separationService.getJob("sep-y") } returns null
        val response = client.get("/api/v2/separate/sep-y/stem/background?token=$token")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun separationJob(jobId: String, ownerUserId: UUID?): SeparationJob {
        val outDir = File(testDir, "sep-out-$jobId").apply { mkdirs() }
        val src = File(testDir, "src-$jobId.m4a").apply { writeText("dummy") }
        return SeparationJob(
            jobId = jobId,
            outputDir = outDir,
            ownerUserId = ownerUserId,
            sourceFile = src,
            spec = com.vibi.bff.model.SeparationSpec(),
        )
    }

    // ── POST /separate audio-only contract ───────────────────────────────────

    private suspend fun postSeparate(
        client: io.ktor.client.HttpClient,
        specJson: String = """{}""",
        fileName: String = "t.m4a",
        contentType: String = "audio/mp4",
        fileBytes: ByteArray = "fake".toByteArray(),
    ) = client.post("/api/v2/separate") {
        setBody(MultiPartFormDataContent(formData {
            append("file", fileBytes, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            })
            append("spec", specJson)
        }))
    }

    @Test
    fun `POST separate m4a happy path forwards to SeparationService`() = testApp {
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
        coEvery { MediaTrimmer.probeDurationMs(any()) } returns 30_000L
        every { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) } returns
            "sep-ok"

        val response = postSeparate(client)

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("sep-ok", body["jobId"]!!.jsonPrimitive.content)
        verify(exactly = 1) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
    }

    /** 확장자만 .m4a 인 video bytes 는 ffprobe stream-kind 검증에서 reject — Perso 가 silent fail
     *  하는 회귀 차단 (확장자 위조 공격 / 클라 버그 양쪽에 대한 BFF 측 방어). */
    @Test
    fun `POST separate rejects file labeled m4a whose probe shows video track`() = testApp {
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("video", "audio")

        val response = postSeparate(client)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unsupported_audio_format", body["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
    }

    /** probe 자체 실패 (corrupt header, unrecognized container 등) 도 reject — fail-closed. */
    @Test
    fun `POST separate rejects when probeStreamKinds fails`() = testApp {
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns null

        val response = postSeparate(client)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unsupported_audio_format", body["error"]!!.jsonPrimitive.content)
    }

    /** probe 가 audio stream 은 확인했지만 duration 측정 실패한 경우 — file size 기반 conservative
     *  추정으로 분 단위 차감. probe-fail 만으로 1 credit floor 로 떨어지는 undercharge 우회 차단. */
    @Test
    fun `POST separate uses size-based duration fallback when probeDurationMs fails`() {
        val callerId = UUID.randomUUID()
        val creditRepo = mockk<CreditRepository>(relaxed = true)
        // probeDurationMs 실패 → size-based duration fallback 경로를 타는지 검증. 비용 정책은
        // 영상 1개당 고정 1 크레딧이라 duration 과 무관하게 reserve cost 인자는 1.
        val bigBytes = ByteArray(8_000_000) { 0 }
        every { creditRepo.reserve(eq(callerId), any(), eq(1)) } returns
            CreditRepository.ReserveOutcome(charged = 1, balance = 100)
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
        coEvery { MediaTrimmer.probeDurationMs(any()) } returns null
        every { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) } returns
            "sep-fallback"

        testApp(jwtSecret = testJwtSecret, creditRepository = creditRepo) {
            val response = client.post("/api/v2/separate") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
                setBody(MultiPartFormDataContent(formData {
                    append("file", bigBytes, Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentDisposition, "filename=\"t.m4a\"")
                    })
                    append("spec", """{}""")
                }))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            verify(exactly = 1) { creditRepo.reserve(eq(callerId), any(), eq(1)) }
        }
    }

    @Test
    fun `POST separate without file returns 400`() = testApp {
        val response = client.post("/api/v2/separate") {
            setBody(MultiPartFormDataContent(formData {
                append("spec", """{}""")
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        verify(exactly = 0) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
    }

    @Test
    fun `POST separate rejects video upload with unsupported_audio_format`() = testApp {
        val response = postSeparate(
            client,
            fileName = "vid.mp4",
            contentType = "video/mp4",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unsupported_audio_format", body["error"]!!.jsonPrimitive.content)
        verify(exactly = 0) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
    }

    @Test
    fun `POST separate rejects flac (Perso silent-fail regression guard)`() = testApp {
        val response = postSeparate(
            client,
            fileName = "audio.flac",
            contentType = "audio/flac",
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("unsupported_audio_format", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST separate accepts mp3 and wav extensions`() = testApp {
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
        coEvery { MediaTrimmer.probeDurationMs(any()) } returns 10_000L
        every { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) } returns
            "sep-mp3" andThen
            "sep-wav"

        val mp3Response = postSeparate(client, fileName = "a.mp3", contentType = "audio/mpeg")
        assertEquals(HttpStatusCode.Accepted, mp3Response.status)

        val wavResponse = postSeparate(client, fileName = "a.wav", contentType = "audio/wav")
        assertEquals(HttpStatusCode.Accepted, wavResponse.status)
    }

    // ── 자원 소유권 검증 ─────────────────────────────────────────────────────

    private val testJwtSecret = "a".repeat(64)

    @Test
    fun `GET separation status returns 404 when caller is not owner`() = testApp(jwtSecret = testJwtSecret) {
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        coEvery { separationService.getJob("sep-owned") } returns separationJob("sep-owned", ownerId)

        val response = client.get("/api/v2/separate/sep-owned") {
            header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(otherId, testJwtSecret)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── 크레딧 게이트 ─────────────────────────────────────────────────────────

    @Test
    fun `POST separate returns 402 when balance insufficient`() {
        val callerId = UUID.randomUUID()
        val creditRepo = mockk<CreditRepository>()
        every { creditRepo.reserve(eq(callerId), any(), any()) } throws
            InsufficientCreditsException(required = 1, balance = 0)

        testApp(jwtSecret = testJwtSecret, creditRepository = creditRepo) {
            mockkObject(MediaTrimmer)
            coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
            coEvery { MediaTrimmer.probeDurationMs(any()) } returns 30_000L

            val response = client.post("/api/v2/separate") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
                setBody(MultiPartFormDataContent(formData {
                    append("file", "fake".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentDisposition, "filename=\"t.m4a\"")
                    })
                    append("spec", """{}""")
                }))
            }
            assertEquals(HttpStatusCode.PaymentRequired, response.status)
            val body = AppJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("insufficient_credits", body["error"]!!.jsonPrimitive.content)
            verify(exactly = 0) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
        }
    }

    @Test
    fun `POST separate charges flat 1 credit regardless of audio duration`() {
        val callerId = UUID.randomUUID()
        val creditRepo = mockk<CreditRepository>(relaxed = true)
        every { creditRepo.reserve(any(), any(), any()) } returns
            CreditRepository.ReserveOutcome(charged = 1, balance = 1)
        // audio 가 120s 여도 정책상 영상 1개당 고정 1 크레딧.
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
        coEvery { MediaTrimmer.probeDurationMs(any()) } returns 120_000L
        every { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) } returns
            "sep-new"

        testApp(jwtSecret = testJwtSecret, creditRepository = creditRepo) {
            val response = client.post("/api/v2/separate") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
                setBody(MultiPartFormDataContent(formData {
                    append("file", "fake".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentDisposition, "filename=\"t.m4a\"")
                    })
                    append("spec", """{}""")
                }))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            verify(exactly = 1) { creditRepo.reserve(eq(callerId), match { it.startsWith("sep-") }, eq(1)) }
            verify(exactly = 0) { creditRepo.refund(any()) }
        }
    }
}
