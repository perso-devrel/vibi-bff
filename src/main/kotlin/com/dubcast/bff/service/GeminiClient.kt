package com.dubcast.bff.service

import com.dubcast.bff.config.GeminiConfig
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.FileInputStream

/**
 * Vertex AI Gemini — SRT 자막 텍스트의 다국어 번역만 담당.
 * Perso 가 STT (originalSubtitle) 를 만들고, 다른 언어 자막이 필요할 때 본 클라이언트로 번역.
 *
 * 인증: GCP service account JSON ([config.credentialsPath]) 의 OAuth2 token. 첫 호출 시 1회 로드
 * 후 자체 캐시. 만료되면 자동 refresh.
 */
class GeminiClient(
    private val config: GeminiConfig,
    private val httpClient: HttpClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var credentials: GoogleCredentials? = null

    @Synchronized
    private fun accessToken(): String {
        val creds = credentials ?: run {
            val loaded = FileInputStream(config.credentialsPath).use {
                GoogleCredentials.fromStream(it)
            }.createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
            credentials = loaded
            loaded
        }
        creds.refreshIfExpired()
        return creds.accessToken.tokenValue
    }

    /**
     * SRT body 를 [targetLanguageCode] 로 번역. 큐 번호 / 타임스탬프는 그대로 유지하고 텍스트
     * 라인만 번역.
     */
    suspend fun translateSrt(srtBody: String, targetLanguageCode: String): String {
        val prompt = """
        Translate the following SRT subtitle file to language code "$targetLanguageCode".
        Preserve the cue numbers and timestamps exactly. Translate ONLY the subtitle text lines.
        Do not add any commentary, code fences, or explanations — output the SRT body only.

        ---
        $srtBody
        """.trimIndent()

        val url = "https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}:generateContent"
        log.info("Gemini translate request: model={} target={} srtChars={}", config.model, targetLanguageCode, srtBody.length)

        val request = GeminiGenerateRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = prompt))
                )
            )
        )

        val response = httpClient.post(url) {
            header("Authorization", "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw RuntimeException("Gemini translate failed (${response.status.value}): $body")
        }

        val parsed: GeminiGenerateResponse = response.body()
        val translated = parsed.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: throw RuntimeException("Gemini returned empty translation")
        // ```srt 같은 fence 제거
        return translated
            .removePrefix("```srt").removePrefix("```").removeSuffix("```")
            .trim()
    }
}

// --- Vertex AI Gemini DTOs (minimal subset) ---
@Serializable
private data class GeminiGenerateRequest(val contents: List<GeminiContent>)

@Serializable
private data class GeminiContent(val role: String, val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiGenerateResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class GeminiCandidate(val content: GeminiContent? = null)
