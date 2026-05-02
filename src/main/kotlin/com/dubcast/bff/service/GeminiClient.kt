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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    /**
     * 채팅 + functionDeclarations 호출. plain-JSON 으로 직접 직렬화 — Vertex generateContent 의
     * tools/systemInstruction 스키마는 GeminiGenerateRequest 의 좁은 DTO 로 표현 어려움.
     *
     * 응답: candidates[0].content.parts[0] 의 functionCall (proposal.steps 후보) 또는 text.
     * v1 은 모델이 단일 part 만 반환한다고 가정. 다중 functionCall 은 systemInstruction 에서
     * "최대 5 step" 으로 막혀있고, 모델이 여러 part 로 쪼개면 caller 가 첫 part 만 사용 →
     * 사용자가 [수정 요청] 으로 재발화 트리거.
     */
    suspend fun chat(
        userMessages: List<Pair<String, String>>,  // role to content
        toolFunctionDeclarations: List<JsonObject>,
        systemInstruction: String,
    ): GeminiChatResult {
        val url = "https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}:generateContent"

        val payload = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put("role", "system")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemInstruction) })
                    }
                },
            )
            putJsonArray("contents") {
                userMessages.forEach { (role, content) ->
                    add(
                        buildJsonObject {
                            put("role", if (role == "model") "model" else "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", content) })
                            }
                        },
                    )
                }
            }
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put(
                            "functionDeclarations",
                            buildJsonArray { toolFunctionDeclarations.forEach { add(it) } },
                        )
                    },
                )
            }
        }

        log.info("Gemini chat request: model={} turns={} tools={}", config.model, userMessages.size, toolFunctionDeclarations.size)
        val response = httpClient.post(url) {
            header("Authorization", "Bearer ${accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw RuntimeException("Gemini chat failed (${response.status.value}): $body")
        }
        val raw = response.bodyAsText()
        return parseChatResult(raw)
    }

    private fun parseChatResult(raw: String): GeminiChatResult {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
            ?: return GeminiChatResult.TextResponse("(empty)")
        val candidates = (root["candidates"] as? kotlinx.serialization.json.JsonArray) ?: return GeminiChatResult.TextResponse("(no candidate)")
        val first = candidates.firstOrNull() as? JsonObject ?: return GeminiChatResult.TextResponse("(no first candidate)")
        val content = first["content"] as? JsonObject ?: return GeminiChatResult.TextResponse("(no content)")
        val parts = (content["parts"] as? kotlinx.serialization.json.JsonArray).orEmpty()

        // 1) functionCall part 우선 — proposal step 으로 사용.
        val toolCalls = mutableListOf<GeminiToolCall>()
        val textBuilder = StringBuilder()
        parts.forEach { p ->
            val po = p as? JsonObject ?: return@forEach
            val fc = po["functionCall"] as? JsonObject
            if (fc != null) {
                val name = (fc["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val args = (fc["args"] as? JsonObject) ?: kotlinx.serialization.json.buildJsonObject { }
                toolCalls += GeminiToolCall(name, args)
            } else {
                val t = (po["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (!t.isNullOrBlank()) textBuilder.append(t)
            }
        }
        return if (toolCalls.isNotEmpty()) {
            GeminiChatResult.ToolCalls(toolCalls, rationaleText = textBuilder.toString().trim().ifBlank { null })
        } else {
            GeminiChatResult.TextResponse(textBuilder.toString().trim().ifBlank { "(no text)" })
        }
    }
}

sealed interface GeminiChatResult {
    data class TextResponse(val text: String) : GeminiChatResult
    data class ToolCalls(
        val calls: List<GeminiToolCall>,
        val rationaleText: String?,
    ) : GeminiChatResult
}

data class GeminiToolCall(val name: String, val args: JsonObject)

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
