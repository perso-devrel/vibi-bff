package com.vibi.bff

import com.vibi.bff.config.PersoConfig
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.PersoMediaType
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 실제 Perso API 를 때리는 통합 테스트 — 분리 파이프라인 회귀 가드.
 *
 * 배경: `997c683` refactor 가 MediaTrimmer 출력을 FLAC 으로 바꾼 뒤 Perso 분리 잡이
 * upload OK → progress=100 / reason="Failed" 로 끝나는 회귀 발생. 본 IT 가 그걸
 * 재현/방지하기 위한 도구. 화자 2명이 섞인 /tmp/vibi-sep-test/two_speakers.<format>
 * 을 업로드 → audio-separation 잡 submit → progress 폴링.
 *
 * 실행:
 *   PERSO_IT_FORMAT=wav ./gradlew test --tests "com.vibi.bff.PersoLiveSeparationIT" -i   # 정상 (default)
 *   PERSO_IT_FORMAT=fixture ./gradlew test --tests "com.vibi.bff.PersoLiveSeparationIT" -i  # 회귀 확인용 (Perso 가 거부)
 *
 * fixture 생성:
 *   say -v Samantha -o /tmp/vibi-sep-test/spk1.aiff "..."
 *   say -v Daniel   -o /tmp/vibi-sep-test/spk2.aiff "..."
 *   ffmpeg -i spk1.aiff ... spk1.wav && ffmpeg -i spk2.aiff -af "adelay=6000|6000" spk2.wav
 *   ffmpeg -i spk1.wav -i spk2.wav -filter_complex amix=2:normalize=0 -ar 44100 -ac 1 two_speakers.wav
 *
 *   - .env 의 PERSO_API_KEY / PERSO_SPACE_SEQ 자동 로드
 *   - 환경 변수 미설정 / fixture 부재 시 skip — CI 안전.
 */
class PersoLiveSeparationIT {

    @Test
    fun `live upload + audio separation with 2 speakers`() = runBlocking {
        val env = dotenv {
            directory = File(System.getProperty("user.dir")).absolutePath
            ignoreIfMissing = true
        }
        fun envOrProp(k: String): String? =
            System.getenv(k) ?: System.getProperty(k) ?: env[k]?.takeIf { it.isNotBlank() }

        val apiKey = envOrProp("PERSO_API_KEY") ?: run {
            println("[SKIP] PERSO_API_KEY not set — skipping live IT")
            return@runBlocking
        }
        val spaceSeq = envOrProp("PERSO_SPACE_SEQ")?.toIntOrNull() ?: run {
            println("[SKIP] PERSO_SPACE_SEQ missing or non-int")
            return@runBlocking
        }
        // 포맷 선택 — env PERSO_IT_FORMAT=wav|mp3|fixture (default wav, MediaTrimmer 가 실제로 보내는 포맷).
        val format = (envOrProp("PERSO_IT_FORMAT") ?: "wav").lowercase()
        val fixture = File("/tmp/vibi-sep-test/two_speakers.$format")
        if (!fixture.exists()) {
            println("[SKIP] missing fixture: $fixture — generate via header comment recipe")
            return@runBlocking
        }
        println("[LIVE-IT] format=$format")

        val persoConfig = PersoConfig(
            apiKey = apiKey,
            baseUrl = "https://api.perso.ai",
            storageBaseUrl = "https://portal-media.perso.ai",
            spaceSeq = spaceSeq,
            pollIntervalMs = 5_000L,
            maxPollMinutes = 5,
            downloadAllowedHosts = setOf("portal-media.perso.ai"),
        )

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(AppJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 120_000L
                requestTimeoutMillis = 600_000L
                socketTimeoutMillis = 600_000L
            }
            engine {
                requestTimeout = 600_000L
            }
        }

        try {
            val perso = PersoClient(persoConfig, httpClient)

            println("[LIVE-IT] step 1: uploading ${fixture.name} (${fixture.length()}B) → Perso")
            val reg = try {
                perso.uploadMedia(PersoMediaType.AUDIO, fixture)
            } catch (e: Throwable) {
                println("[LIVE-IT] step 1 FAILED: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                fail("uploadMedia failed: ${e.message}")
            }
            println("[LIVE-IT] step 1 OK: mediaSeq=${reg.seq}")

            println("[LIVE-IT] step 2: submitAudioSeparation(mediaSeq=${reg.seq})")
            val projectSeq = try {
                perso.submitAudioSeparation(
                    mediaSeq = reg.seq,
                    isVideoProject = false,
                    title = "live-it-${System.currentTimeMillis()}",
                )
            } catch (e: Throwable) {
                println("[LIVE-IT] step 2 FAILED: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                fail("submitAudioSeparation failed: ${e.message}")
            }
            println("[LIVE-IT] step 2 OK: projectSeq=$projectSeq")

            println("[LIVE-IT] step 3: poll progress (deadline 5min)")
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            var lastPct = -1
            while (System.currentTimeMillis() < deadline) {
                val p = try {
                    perso.getProgress(projectSeq)
                } catch (e: Throwable) {
                    println("[LIVE-IT] getProgress failed: ${e::class.simpleName}: ${e.message}")
                    delay(5000)
                    continue
                }
                if (p.progress != lastPct) {
                    println("[LIVE-IT] progress=${p.progress}% reason=${p.progressReason} hasFailed=${p.hasFailed}")
                    lastPct = p.progress
                }
                if (p.hasFailed) fail("Perso hasFailed=true at progress=${p.progress} reason=${p.progressReason}")
                if (p.progress >= 100 || p.progressReason?.contains("Completed", true) == true) {
                    println("[LIVE-IT] step 3 OK: separation reported complete")
                    return@runBlocking
                }
                delay(5000)
            }
            fail("polling timed out after 5min — last progress=$lastPct%")
        } finally {
            httpClient.close()
        }
    }
}
