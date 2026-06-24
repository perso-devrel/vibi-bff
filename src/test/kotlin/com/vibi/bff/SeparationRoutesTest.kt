package com.vibi.bff

import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.configureErrorHandling
import com.vibi.bff.plugins.configureRateLimiting
import com.vibi.bff.plugins.configureSerialization
import com.vibi.bff.model.PersoScriptPage
import com.vibi.bff.model.PersoScriptSentence
import com.vibi.bff.model.PersoScriptSpeaker
import com.vibi.bff.routes.separationRoutes
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.InsufficientCreditsException
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.ScriptJobRow
import com.vibi.bff.service.SeparationHistoryRow
import com.vibi.bff.service.SeparationJob
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.UserRepository
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
        userRepository: UserRepository? = null,
        queueRepository: SeparationQueueRepository? = null,
        persoClient: PersoClient? = null,
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
                    queueRepository = queueRepository,
                    jwtSecret = jwtSecret,
                    creditRepository = creditRepository,
                    userRepository = userRepository,
                    persoClient = persoClient,
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
    fun `GET stem without token or bearer returns 401`() = testApp(jwtSecret = testJwtSecret) {
        // 토큰 없이 호출하면 Bearer JWT 가 필수 — 토큰·헤더 둘 다 없으면 401.
        val response = client.get("/api/v2/separate/sep-x/stem/background")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET stem without token but with valid bearer is accepted (404 for missing job)`() =
        testApp(jwtSecret = testJwtSecret) {
            // UXP 패널 경로: 토큰 대신 Bearer 로 인증 → token-required 안 나고 정상 진입(잡 없으면 404).
            coEvery { separationService.getJob("sep-z") } returns null
            val response = client.get("/api/v2/separate/sep-z/stem/background") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(UUID.randomUUID(), testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
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
        // probeDurationMs 실패 → size-based duration fallback 경로 검증. 8MB / 8KB/s = 1000s
        // = 1_000_000ms ≈ 16.7분 → 시작된 1분 블록 17개 → reserve cost = 17. probe-fail 만으로
        // floor 1 credit 으로 떨어지는 undercharge 우회가 막혔음을 비례 차감 값으로 실증한다.
        val bigBytes = ByteArray(8_000_000) { 0 }
        every { creditRepo.reserve(eq(callerId), any(), eq(17)) } returns
            CreditRepository.ReserveOutcome(charged = 17, balance = 100)
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
            verify(exactly = 1) { creditRepo.reserve(eq(callerId), any(), eq(17)) }
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

    /** A-1: 삭제된 계정의 아직-유효한 JWT 로 submit 시도 → users row 부재라 401 account_deleted.
     *  Perso/ffmpeg 비용 유발 전에 차단. submit 자체는 호출되지 않아야 한다. */
    @Test
    fun `POST separate rejects deleted account (valid JWT but user row gone)`() {
        val userRepo = mockk<UserRepository>()
        every { userRepo.exists(any()) } returns false
        val callerId = UUID.randomUUID()
        testApp(jwtSecret = testJwtSecret, userRepository = userRepo) {
            val response = client.post("/api/v2/separate") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
                setBody(MultiPartFormDataContent(formData {
                    append("file", "fake".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp4")
                        append(HttpHeaders.ContentDisposition, "filename=\"t.m4a\"")
                    })
                    append("spec", "{}")
                }))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            verify(exactly = 0) { separationService.submit(any(), any(), anyNullable(), any(), anyNullable(), anyNullable()) }
        }
    }

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
    fun `POST separate charges credits proportional to audio duration in 1min blocks`() {
        val callerId = UUID.randomUUID()
        val creditRepo = mockk<CreditRepository>(relaxed = true)
        every { creditRepo.reserve(any(), any(), any()) } returns
            CreditRepository.ReserveOutcome(charged = 12, balance = 1)
        // probed duration 12분 → 시작된 1분 블록 12개 → reserve cost = 12. 라우트가 측정 길이를
        // CreditCost.forSeparation 으로 그대로 반영하는지 (견적-차감 단일 source) 검증.
        mockkObject(MediaTrimmer)
        coEvery { MediaTrimmer.probeStreamKinds(any()) } returns setOf("audio")
        coEvery { MediaTrimmer.probeDurationMs(any()) } returns 12 * 60_000L
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
            verify(exactly = 1) { creditRepo.reserve(eq(callerId), match { it.startsWith("sep-") }, eq(12)) }
            verify(exactly = 0) { creditRepo.refund(any()) }
        }
    }

    // ── Adobe history (목록/삭제/script) ──────────────────────────────────────

    @Test
    fun `GET separations returns the owner's ready history`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        coEvery { queue.listReadyHistory(callerId, null) } returns listOf(
            SeparationHistoryRow(
                jobId = "sep-1",
                fileName = "song.mp3",
                byteLength = 1000L,
                durationMs = 60_000L,
                createdAtMs = 1_700_000_000_000L,
                stemsJson = """[{"stemId":"background","label":"BG","ext":"wav"}]""",
                hasScript = true,
            ),
        )
        testApp(jwtSecret = testJwtSecret, queueRepository = queue) {
            val res = client.get("/api/v2/separations") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val seps = AppJson.parseToJsonElement(res.bodyAsText()).jsonObject["separations"]!!.jsonArray
            assertEquals(1, seps.size)
            val first = seps[0].jsonObject
            assertEquals("sep-1", first["jobId"]!!.jsonPrimitive.content)
            assertEquals(true, first["hasScript"]!!.jsonPrimitive.boolean)
            assertEquals("background", first["stems"]!!.jsonArray[0].jsonObject["stemId"]!!.jsonPrimitive.content)
        }
        coVerify { queue.listReadyHistory(callerId, null) }
    }

    @Test
    fun `GET separations is empty without auth`() = testApp(queueRepository = mockk()) {
        // jwtSecret 미주입 → principal null → 빈 목록(인증 강제 못 하는 dev/테스트 분기).
        val res = client.get("/api/v2/separations")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(0, AppJson.parseToJsonElement(res.bodyAsText()).jsonObject["separations"]!!.jsonArray.size)
    }

    @Test
    fun `DELETE separation returns 204`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        coEvery { queue.deleteOwnedReturning("sep-9", callerId) } returns null
        testApp(jwtSecret = testJwtSecret, queueRepository = queue) {
            val res = client.delete("/api/v2/separate/sep-9") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }
        coVerify { queue.deleteOwnedReturning("sep-9", callerId) }
        // no-op 삭제(null)면 대기 잡 정리는 호출되지 않는다.
        coVerify(exactly = 0) { separationService.onQueuedDeleted(any()) }
    }

    @Test
    fun `DELETE of a QUEUED job triggers waiting-queue cleanup`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        // 아직 대기 중(QUEUED, stems 없음)인 잡 삭제 → onQueuedDeleted 로 환불/정리/nudge.
        coEvery { queue.deleteOwnedReturning("sep-q", callerId) } returns
            com.vibi.bff.service.DeletedJob(SeparationQueueRepository.STATUS_QUEUED, null)
        testApp(jwtSecret = testJwtSecret, queueRepository = queue) {
            val res = client.delete("/api/v2/separate/sep-q") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }
        coVerify(exactly = 1) { separationService.onQueuedDeleted("sep-q") }
    }

    @Test
    fun `DELETE of a READY job does not trigger waiting-queue cleanup`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        // 완료(READY) 잡 삭제 → 대기 잡 정리는 호출되지 않는다(R2 purge 만, ObjectStore 미주입이라 skip).
        coEvery { queue.deleteOwnedReturning("sep-r", callerId) } returns
            com.vibi.bff.service.DeletedJob(SeparationQueueRepository.STATUS_READY, null)
        testApp(jwtSecret = testJwtSecret, queueRepository = queue) {
            val res = client.delete("/api/v2/separate/sep-r") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }
        coVerify(exactly = 0) { separationService.onQueuedDeleted(any()) }
    }

    @Test
    fun `GET script returns assembled draft from Perso projectSeq`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        val perso = mockk<PersoClient>()
        coEvery { queue.loadForScript("sep-7") } returns ScriptJobRow(callerId, "READY", 42L)
        coEvery { perso.getFullAudioSeparationScript(42L) } returns PersoScriptPage(
            hasNext = false,
            nextCursorId = null,
            sentences = listOf(
                PersoScriptSentence(seq = 1, speakerOrderIndex = 1, offsetMs = 0, durationMs = 1000, originalText = "hello"),
            ),
            speakers = listOf(PersoScriptSpeaker(speakerOrderIndex = 1)),
        )
        testApp(jwtSecret = testJwtSecret, queueRepository = queue, persoClient = perso) {
            val res = client.get("/api/v2/separate/sep-7/script") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = AppJson.parseToJsonElement(res.bodyAsText()).jsonObject
            assertEquals(1, body["speakers"]!!.jsonArray.size)
            val seg = body["segments"]!!.jsonArray[0].jsonObject
            assertEquals("hello", seg["text"]!!.jsonPrimitive.content)
            assertEquals(1000, seg["endMs"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `GET script returns 409 when not ready`() {
        val callerId = UUID.randomUUID()
        val queue = mockk<SeparationQueueRepository>()
        coEvery { queue.loadForScript("sep-x") } returns ScriptJobRow(callerId, "QUEUED", null)
        testApp(jwtSecret = testJwtSecret, queueRepository = queue, persoClient = mockk()) {
            val res = client.get("/api/v2/separate/sep-x/script") {
                header(HttpHeaders.Authorization, "Bearer ${issueTestJwt(callerId, testJwtSecret)}")
            }
            assertEquals(HttpStatusCode.Conflict, res.status)
        }
    }
}
