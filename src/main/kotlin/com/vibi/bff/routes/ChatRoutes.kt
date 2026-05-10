package com.vibi.bff.routes

import com.vibi.bff.model.ChatRequest
import com.vibi.bff.model.ChatResponse
import com.vibi.bff.model.ErrorResponse
import com.vibi.bff.model.Proposal
import com.vibi.bff.model.ToolCall
import com.vibi.bff.service.ChatToolDefs
import com.vibi.bff.service.GeminiChatResult
import com.vibi.bff.service.GeminiClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * v1 채팅 — POST /api/v2/chat. Vertex Gemini 의 functionDeclarations 호출 후 결과를
 * (proposal | text) ChatResponse 로 변환.
 */
fun Route.chatRoutes(geminiClient: GeminiClient) {
    post("/chat") {
        val req = call.receive<ChatRequest>()
        if (req.messages.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "messages required"))
            return@post
        }

        // projectContext 를 system prefix 로 인라인 — user turn 직전에 별도 user 가 아닌 system
        // instruction 처럼 동작하지만, Gemini 는 systemInstruction 단일 슬롯이라 첫 user message 앞에
        // "[Project context] ..." 형태로 prepend.
        val ctxJson = Json.encodeToJsonElement(req.projectContext).toString()
        val turns = mutableListOf<Pair<String, String>>()
        var prefixed = false
        req.messages.forEachIndexed { i, m ->
            val content = if (!prefixed && m.role == "user") {
                prefixed = true
                "[Project context]\n$ctxJson\n\n[User message]\n${m.content}"
            } else {
                m.content
            }
            turns += m.role to content
        }

        val tools = ChatToolDefs.functionDeclarations()
        val systemInstruction = ChatToolDefs.SYSTEM_INSTRUCTION + "\n\nLocale fallback: ${req.locale}"

        val result = runCatching { geminiClient.chat(turns, tools, systemInstruction) }
            .getOrElse { e ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "Gemini error", detail = e.message),
                )
                return@post
            }

        val response = when (result) {
            is GeminiChatResult.TextResponse -> ChatResponse(kind = "text", text = result.text)
            is GeminiChatResult.ToolCalls -> {
                // 등록되지 않은 tool name 은 dispatch 가 거부하지만, 1차 방어선 — 여기서 필터.
                val allowed = ChatToolDefs.functionDeclarations()
                    .mapNotNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .toSet()
                val safeSteps = result.calls
                    .filter { it.name in allowed }
                    .take(5)
                    .map { ToolCall(name = it.name, args = it.args) }
                if (safeSteps.isEmpty()) {
                    ChatResponse(kind = "text", text = result.rationaleText ?: "도구 호출 결과가 비어 있습니다. 발화를 좀 더 구체적으로 알려주세요.")
                } else {
                    ChatResponse(
                        kind = "proposal",
                        proposal = Proposal(
                            rationale = result.rationaleText ?: "선택하신 작업을 다음과 같이 적용하려고 합니다.",
                            steps = safeSteps,
                        ),
                    )
                }
            }
        }
        call.respond(response)
    }
}
