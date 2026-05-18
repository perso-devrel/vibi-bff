package com.vibi.bff.service

import com.vibi.bff.config.GeminiConfig
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Vertex AI Gemini — 채팅 + functionDeclarations 기반 편집 의도 해석.
 *
 * 인증 우선순위 (첫 호출 시 1회 로드 후 캐시, 만료되면 자동 refresh):
 * 1. [config.credentialsPath] 가 비어있지 않으면 그 파일을 service account JSON 으로 사용 (로컬 dev).
 * 2. 비어있으면 Application Default Credentials — Cloud Run / GCE 에 attached service
 *    account 가 metadata server 로 자동 주입, 또는 로컬에선 GOOGLE_APPLICATION_CREDENTIALS
 *    env / `gcloud auth application-default login` 캐시 사용.
 */
class GeminiClient(
    private val config: GeminiConfig,
    private val httpClient: HttpClient,
    /** Gemini API 호출 instrumentation. null 이면 추적 skip (테스트). */
    private val externalCalls: ExternalApiCallsRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var credentials: GoogleCredentials? = null

    /**
     * GoogleCredentials.refreshIfExpired 는 OAuth2 token endpoint blocking I/O
     * (HttpURLConnection sync). Netty event loop 에서 직접 호출하면 thread 가 막힘 →
     * Dispatchers.IO 로 격리. credential load (FileInputStream / metadata server) 도
     * 동일 dispatcher 안에서. @Synchronized 는 race 시 GoogleCredentials 인스턴스 중복
     * 생성을 막는 용도라 유지.
     */
    private suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        loadOrRefreshCredentials().accessToken.tokenValue
    }

    @Synchronized
    private fun loadOrRefreshCredentials(): GoogleCredentials {
        val creds = credentials ?: run {
            val source = if (config.credentialsPath.isNotBlank()) {
                log.info("Gemini credentials: loading from file path={}", config.credentialsPath)
                FileInputStream(config.credentialsPath).use { GoogleCredentials.fromStream(it) }
            } else {
                log.info("Gemini credentials: using Application Default Credentials (metadata server / gcloud)")
                GoogleCredentials.getApplicationDefault()
            }
            val loaded = source.createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
            credentials = loaded
            loaded
        }
        creds.refreshIfExpired()
        return creds
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
    ): GeminiChatResult = externalCalls.withExternalCall("gemini", "chat") {
        doChat(userMessages, toolFunctionDeclarations, systemInstruction)
    }

    private suspend fun doChat(
        userMessages: List<Pair<String, String>>,
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
            // gemini-2.5-flash 가 functionDeclarations 를 받아도 tool_code 마크다운 +
            // print(default_api.foo(...)) 텍스트로 fallback 하는 케이스 다발 — mode=AUTO 명시
            // (default 동일, 명시적 declaration 으로 future-proof). 진짜 tool 호출 시점엔
            // structured functionCall part 반환을 강하게 유도.
            putJsonObject("toolConfig") {
                putJsonObject("functionCallingConfig") {
                    put("mode", "AUTO")
                }
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
        // 2) 안전망 — gemini-2.5-flash 가 systemInstruction 무시하고 tool_code 텍스트로 fallback
        //    한 케이스 복원. Python `print(default_api.<name>(...))` 또는 YAML `- tool:
        //    default_api.<name>` + 들여쓴 `args:` 블록 둘 다 시도.
        if (toolCalls.isEmpty() && textBuilder.isNotEmpty()) {
            val (recovered, recoveredRationale) = recoverToolCallsFromText(textBuilder.toString())
            if (recovered.isNotEmpty()) {
                log.warn("Gemini emitted pseudo-tool-code text instead of structured functionCall — recovered {} call(s)", recovered.size)
                return GeminiChatResult.ToolCalls(recovered, rationaleText = recoveredRationale)
            }
        }
        return if (toolCalls.isNotEmpty()) {
            GeminiChatResult.ToolCalls(toolCalls, rationaleText = textBuilder.toString().trim().ifBlank { null })
        } else {
            GeminiChatResult.TextResponse(textBuilder.toString().trim().ifBlank { "(no text)" })
        }
    }

    /**
     * 텍스트에서 ToolCall 을 복원. 두 패턴을 순차로 시도:
     *
     * 1. Python: `print(default_api.<name>(arg1=..., arg2=...))` 또는 `default_api.<name>(...)`
     *    — Python literal (str/int/float/bool/list) 지원.
     * 2. YAML pseudo: `- tool: default_api.<name>` 헤더 + 들여쓴 `args:` 매핑 블록. rationale
     *    필드 (`rationale: <text>`) 도 함께 추출해 [GeminiChatResult.ToolCalls.rationaleText]
     *    로 전달.
     *
     * 정규 표현식 한 번에 여러 줄 매칭 — 한 응답에 multi-step 출력하면 모두 회수.
     *
     * @return ([calls], [rationale]) — calls 가 비어있으면 caller 가 plain text 로 처리.
     */
    private fun recoverToolCallsFromText(text: String): Pair<List<GeminiToolCall>, String?> {
        val pythonRegex = Regex("""default_api\.([a-zA-Z_][a-zA-Z0-9_]*)\(([^()]*(?:\([^()]*\)[^()]*)*)\)""")
        val pythonCalls = pythonRegex.findAll(text).mapNotNull { m ->
            val name = m.groupValues[1]
            val argsBody = m.groupValues[2].trim()
            runCatching {
                val args = parsePythonKwargs(argsBody)
                GeminiToolCall(name, args)
            }.onFailure { log.warn("recover skipped name={} body={} err={}", name, argsBody, it.message) }.getOrNull()
        }.toList()
        if (pythonCalls.isNotEmpty()) return pythonCalls to null

        return recoverYamlToolCalls(text)
    }

    /**
     * YAML pseudo-tool-code 복원. 모델이 systemInstruction 무시하고 다음 형태로 응답한 경우:
     * ```
     * kind: proposal
     * rationale: <설명 텍스트>
     * steps:
     * - tool: default_api.separate_audio_range
     *   args:
     *     startMs: 1000
     *     endMs: 4000
     * ```
     * `default_api.` prefix 는 옵션 — 없어도 매치. value 는 YAML scalar (string/int/double/
     * bool/null/list) 로 파싱.
     */
    private fun recoverYamlToolCalls(text: String): Pair<List<GeminiToolCall>, String?> {
        val toolHeader = Regex("""^\s*-?\s*tool\s*:\s*(?:default_api\.)?([a-zA-Z_][a-zA-Z0-9_]*)\s*$""")
        val argsHeader = Regex("""^\s*args\s*:\s*$""")
        val kvLine = Regex("""^(\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*(.*)$""")
        val rationaleLine = Regex("""^\s*rationale\s*:\s*(.+)$""")

        val lines = text.lines()
        var rationale: String? = null
        lines.firstOrNull { rationaleLine.containsMatchIn(it) }
            ?.let { rationaleLine.find(it)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { rationale = it.removeSurrounding("\"").removeSurrounding("'") }

        val results = mutableListOf<GeminiToolCall>()
        var i = 0
        while (i < lines.size) {
            val header = toolHeader.find(lines[i])
            if (header == null) { i++; continue }
            val name = header.groupValues[1]
            i++
            // `args:` 블록까지 진행 — 없을 수 있음
            while (i < lines.size && !argsHeader.containsMatchIn(lines[i]) && !toolHeader.containsMatchIn(lines[i])) i++
            val argsObj = mutableMapOf<String, JsonElement>()
            if (i < lines.size && argsHeader.containsMatchIn(lines[i])) {
                i++
                var argsIndent: Int? = null
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank()) { i++; continue }
                    if (toolHeader.containsMatchIn(l)) break
                    val kv = kvLine.matchEntire(l)
                    if (kv == null) { i++; continue }
                    val indent = kv.groupValues[1].length
                    if (argsIndent == null) argsIndent = indent
                    if (indent < (argsIndent ?: indent)) break
                    val key = kv.groupValues[2]
                    val rawVal = kv.groupValues[3].trim()
                    // 예약어 (rationale/kind/steps) 가 들여쓰기 사고로 args 안에 잡히지 않도록
                    if (key in setOf("rationale", "kind", "steps", "tool")) break
                    argsObj[key] = parseYamlScalar(rawVal)
                    i++
                }
            }
            results += GeminiToolCall(name, JsonObject(argsObj))
        }
        return results to rationale
    }

    private fun parseYamlScalar(raw: String): JsonElement {
        val s = raw.trim()
        if (s.isEmpty()) return kotlinx.serialization.json.JsonPrimitive("")
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return kotlinx.serialization.json.JsonPrimitive(s.substring(1, s.length - 1))
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            val inner = s.substring(1, s.length - 1)
            return buildJsonArray {
                splitTopLevel(inner, ',').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { add(parseYamlScalar(it)) }
            }
        }
        when (s.lowercase()) {
            "true" -> return kotlinx.serialization.json.JsonPrimitive(true)
            "false" -> return kotlinx.serialization.json.JsonPrimitive(false)
            "null", "~" -> return kotlinx.serialization.json.JsonNull
        }
        s.toLongOrNull()?.let { return kotlinx.serialization.json.JsonPrimitive(it) }
        s.toDoubleOrNull()?.let { return kotlinx.serialization.json.JsonPrimitive(it) }
        return kotlinx.serialization.json.JsonPrimitive(s)
    }

    /**
     * `key1=val1, key2=val2` 형태 Python kwargs → JsonObject. value 는 string / int / float /
     * bool / list (재귀 1단계까지). 나머지는 string fallback.
     */
    private fun parsePythonKwargs(body: String): JsonObject {
        if (body.isBlank()) return buildJsonObject { }
        val kwargs = splitTopLevel(body, ',').map { it.trim() }.filter { it.isNotEmpty() }
        return buildJsonObject {
            kwargs.forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) return@forEach
                val key = kv.substring(0, eq).trim()
                val rawVal = kv.substring(eq + 1).trim()
                put(key, parsePythonLiteral(rawVal))
            }
        }
    }

    private fun parsePythonLiteral(raw: String): JsonElement {
        val s = raw.trim()
        // string ("..." or '...')
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return kotlinx.serialization.json.JsonPrimitive(s.substring(1, s.length - 1))
        }
        // list [...]
        if (s.startsWith("[") && s.endsWith("]")) {
            val inner = s.substring(1, s.length - 1)
            return kotlinx.serialization.json.buildJsonArray {
                splitTopLevel(inner, ',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(parsePythonLiteral(it)) }
            }
        }
        // bool
        when (s) {
            "True", "true" -> return kotlinx.serialization.json.JsonPrimitive(true)
            "False", "false" -> return kotlinx.serialization.json.JsonPrimitive(false)
            "None", "null" -> return kotlinx.serialization.json.JsonNull
        }
        // number
        s.toLongOrNull()?.let { return kotlinx.serialization.json.JsonPrimitive(it) }
        s.toDoubleOrNull()?.let { return kotlinx.serialization.json.JsonPrimitive(it) }
        // unquoted identifier — string fallback
        return kotlinx.serialization.json.JsonPrimitive(s)
    }

    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var depthBracket = 0
        var depthParen = 0
        var inStr: Char? = null
        var escape = false
        for (c in s) {
            if (escape) { buf.append(c); escape = false; continue }
            if (c == '\\') { buf.append(c); escape = true; continue }
            if (inStr != null) {
                buf.append(c)
                if (c == inStr) inStr = null
                continue
            }
            when (c) {
                '"', '\'' -> { inStr = c; buf.append(c) }
                '[' -> { depthBracket++; buf.append(c) }
                ']' -> { depthBracket--; buf.append(c) }
                '(' -> { depthParen++; buf.append(c) }
                ')' -> { depthParen--; buf.append(c) }
                sep -> if (depthBracket == 0 && depthParen == 0) { out += buf.toString(); buf.setLength(0) } else buf.append(c)
                else -> buf.append(c)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
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
