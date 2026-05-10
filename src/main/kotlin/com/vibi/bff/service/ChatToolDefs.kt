package com.vibi.bff.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini Vertex AI 의 functionDeclarations 단일 진실 공급원.
 * 모바일 dispatcher 는 여기 등록된 name 외엔 거부 (방어선). v1 사용자 선택 도구 범위:
 *  - 영상 편집: delete/duplicate range, volume/speed
 *  - 음성 분리: range 기반 separate, stem 볼륨
 *  - 자막·더빙: 텍스트 수정, 자동 생성 (영상/BGM 모두)
 *  - 음원/녹음: 위치/볼륨
 *
 * **시간 인자 규약**: 모든 startMs/endMs 는 글로벌 timeline 의 milliseconds, 반열린 구간
 * `[startMs, endMs)` — startMs INCLUSIVE, endMs EXCLUSIVE. "2초부터 5초까지" → startMs=2000,
 * endMs=5000. 매 schema description 에 inclusive/exclusive 를 반복 명시 (모델이 한 곳만
 * 보고 다른 tool 에 일반화하지 않는 사고 방지).
 *
 * 새 tool 추가 시: (1) 여기 declaration, (2) 모바일 ChatToolDispatcher when 분기.
 */
object ChatToolDefs {

    /** Vertex `tools[0].functionDeclarations` JsonArray 직렬화 형태로 반환. */
    fun functionDeclarations(): List<JsonObject> = listOf(
        // --- 영상 편집 ---
        fn(
            name = "delete_segment_range",
            description = "Delete a sub-range from a video segment. Range is half-open [startMs, endMs) in global timeline ms.",
            properties = mapOf(
                "segmentId" to schema("string", "Target segment id from projectContext.segments."),
                "startMs" to schema("integer", "Range start ms, INCLUSIVE (global timeline). Half-open [startMs, endMs) — '2s to 5s' → startMs=2000, endMs=5000."),
                "endMs" to schema("integer", "Range end ms, EXCLUSIVE (global timeline)."),
            ),
            required = listOf("segmentId", "startMs", "endMs"),
        ),
        fn(
            name = "duplicate_segment_range",
            description = "Duplicate a sub-range of a video segment, inserting the copy after the original. Range is half-open [startMs, endMs) in global timeline ms.",
            properties = mapOf(
                "segmentId" to schema("string", "Target segment id."),
                "startMs" to schema("integer", "Range start ms, INCLUSIVE (global timeline). Half-open [startMs, endMs) — '2s to 5s' → startMs=2000, endMs=5000."),
                "endMs" to schema("integer", "Range end ms, EXCLUSIVE (global timeline)."),
            ),
            required = listOf("segmentId", "startMs", "endMs"),
        ),
        fn(
            name = "update_segment_volume",
            description = "Set volume for a sub-range of a video segment, or the whole segment if startMs/endMs omitted. Range is half-open [startMs, endMs) in global timeline ms.",
            properties = mapOf(
                "segmentId" to schema("string", "Target segment id."),
                "volumeScale" to schema("number", "0..2 multiplier. 1.0 = original."),
                "startMs" to schema("integer", "Optional range start ms, INCLUSIVE (global timeline). Half-open [startMs, endMs)."),
                "endMs" to schema("integer", "Optional range end ms, EXCLUSIVE (global timeline)."),
            ),
            required = listOf("segmentId", "volumeScale"),
        ),
        fn(
            name = "update_segment_speed",
            description = "Set playback speed for a sub-range of a video segment, or the whole segment if startMs/endMs omitted. Range is half-open [startMs, endMs) in global timeline ms.",
            properties = mapOf(
                "segmentId" to schema("string", "Target segment id."),
                "speedScale" to schema("number", "0.25..4 multiplier. 1.0 = original."),
                "startMs" to schema("integer", "Optional range start ms, INCLUSIVE (global timeline). Half-open [startMs, endMs)."),
                "endMs" to schema("integer", "Optional range end ms, EXCLUSIVE (global timeline)."),
            ),
            required = listOf("segmentId", "speedScale"),
        ),
        // --- 음원 분리 ---
        fn(
            name = "separate_audio_range",
            description = "Run audio separation on a segment range or whole BGM clip. Speaker count is auto-detected upstream — do not ask for it. Use startMs/endMs (global timeline ms) for partial range; omit both for whole segment / whole BGM clip.",
            properties = mapOf(
                "segmentId" to schema("string", "Target segment id (for video). Omit for BGM-only separation."),
                "bgmClipId" to schema("string", "Target BGM clip id (instead of segmentId)."),
                "startMs" to schema("integer", "Optional partial range start, INCLUSIVE (global timeline ms). Half-open interval [startMs, endMs) — '2s to 5s' → startMs=2000, endMs=5000."),
                "endMs" to schema("integer", "Optional partial range end, EXCLUSIVE (global timeline ms). Half-open interval [startMs, endMs)."),
            ),
            required = listOf(),
        ),
        fn(
            name = "update_stem_volume",
            description = "Set volume of a separated stem (persisted; effective at next render and in preview if separation sheet is open). 0..2 multiplier — use 0 to mute, 1 to reset to original. Common user intents: '배경음 제거/음소거' → stemId='background', volume=0. '보컬만 남기기' → mute background. '1번 화자 음소거' → stemId='speaker_1', volume=0. Use exact stemId from projectContext.separationStems.",
            properties = mapOf(
                "stemId" to schema("string", "Exact stem id from projectContext.separationStems[].stemId. Naming convention: 'background' = BGM/배경음 trail, 'voice_all' = combined vocal track (all speakers mixed), 'speaker_<N>' (e.g. 'speaker_1', 'speaker_2') = individual speaker tracks."),
                "volume" to schema("number", "0..2 multiplier. 0 = mute, 1 = original, 2 = double."),
            ),
            required = listOf("stemId", "volume"),
        ),
        // --- 자막·더빙 ---
        fn(
            name = "update_subtitle_text",
            description = "Replace a subtitle clip's text. Preserve language unless user explicitly switches.",
            properties = mapOf(
                "clipId" to schema("string", "Target subtitle clip id."),
                "text" to schema("string", "New text. Use the user's language verbatim — do not auto-translate."),
            ),
            required = listOf("clipId", "text"),
        ),
        fn(
            name = "transcribe_for_subtitles",
            description = "STEP 1 of the subtitle generation flow. Transcribe the project's video to a script (STT only — NO translation yet) so the user can review/edit it before subtitle clips are created. The transcribed script is delivered to the chat as a model message; the user is then expected to either confirm or request edits. After the script is shown, your NEXT turn must call apply_subtitles_with_script to actually create the subtitle clips. Use this tool whenever the user asks for subtitles — do not call generate_subtitles unless the user explicitly says 'skip review' or similar.",
            properties = mapOf(
                "sourceLanguageCode" to schema("string", "BCP-47 source language or 'auto'."),
                "targetLanguageCodes" to schema("array", "Target codes the user asked for, e.g., ['en','ja']. Will be remembered for the apply step — pass the same value to apply_subtitles_with_script unless the user changed their mind."),
            ),
            required = listOf("targetLanguageCodes"),
        ),
        fn(
            name = "apply_subtitles_with_script",
            description = "STEP 2 of the subtitle generation flow. Create subtitle clips from a script that has already been transcribed (and possibly edited). Use this AFTER transcribe_for_subtitles has run and the user has either confirmed the script or requested edits. If the user asked for edits, regenerate the FULL SRT body with the edits applied and pass it as `srt`; if the user is fine with the original transcription, omit `srt` (the cached transcript is used). The SRT format is standard SubRip: '1\\n00:00:01,000 --> 00:00:03,000\\nHello\\n\\n2\\n...'.",
            properties = mapOf(
                "targetLanguageCodes" to schema("array", "BCP-47 codes to translate to, e.g., ['en','ja']."),
                "srt" to schema("string", "Optional full SRT body with the user's edits applied. Omit if the user accepted the original transcription as-is. When provided, must be valid SRT — keep timing entries unchanged unless the user asked for timing changes."),
                "sourceLanguageCode" to schema("string", "BCP-47 source language or 'auto'. Same as in step 1."),
            ),
            required = listOf("targetLanguageCodes"),
        ),
        fn(
            name = "generate_subtitles",
            description = "DEPRECATED — prefer transcribe_for_subtitles + apply_subtitles_with_script for the standard review-then-apply flow. Only call this directly if the user explicitly asks to skip the script review step.",
            properties = mapOf(
                "sourceLanguageCode" to schema("string", "BCP-47 source language or 'auto'."),
                "targetLanguageCodes" to schema("array", "Target codes, e.g., ['en','ja']."),
            ),
            required = listOf("targetLanguageCodes"),
        ),
        fn(
            name = "generate_dub",
            description = "Trigger automatic dubbing for the project's video into a single target language. Costs minutes.",
            properties = mapOf(
                "sourceLanguageCode" to schema("string", "BCP-47 source language or 'auto'."),
                "targetLanguageCode" to schema("string", "Single target lang code."),
            ),
            required = listOf("targetLanguageCode"),
        ),
        // --- BGM 위치·볼륨 ---
        fn(
            name = "move_bgm_clip",
            description = "Set a BGM clip's start position (timeline insertion point) in ms.",
            properties = mapOf(
                "clipId" to schema("string", "Target BGM clip id."),
                "newStartMs" to schema("integer", "New start ms."),
            ),
            required = listOf("clipId", "newStartMs"),
        ),
        fn(
            name = "update_bgm_volume",
            description = "Set BGM clip volume (0..2).",
            properties = mapOf(
                "clipId" to schema("string", "Target BGM clip id."),
                "volumeScale" to schema("number", "0..2 multiplier."),
            ),
            required = listOf("clipId", "volumeScale"),
        ),
        // --- BGM 자막·더빙 (Stage -1 prereq 완료 후 활성) ---
        fn(
            name = "generate_subtitles_for_bgm",
            description = "Trigger automatic subtitle generation for a BGM clip's audio.",
            properties = mapOf(
                "clipId" to schema("string", "Target BGM clip id."),
                "targetLanguageCodes" to schema("array", "Target codes."),
            ),
            required = listOf("clipId", "targetLanguageCodes"),
        ),
        fn(
            name = "generate_dub_for_bgm",
            description = "Trigger automatic dubbing for a BGM clip's audio into a target language.",
            properties = mapOf(
                "clipId" to schema("string", "Target BGM clip id."),
                "targetLanguageCode" to schema("string", "Single target lang code."),
            ),
            required = listOf("clipId", "targetLanguageCode"),
        ),
    )

    /**
     * systemInstruction — `chat-tools.md` 단일 spec 을 그대로 주입. 정책/도구 의미는 모두 문서에서.
     * eager `val` 로 평가 — `object` 첫 참조(라우팅 와이어링) 시점에 resource 누락이면 즉시 실패해
     * 기동 단계에서 가시화. lazy 로 두면 첫 chat 호출에서야 InternalServerError 로 swallow 됨.
     *
     * 새 tool 추가 시: (1) functionDeclarations 에 entry 추가, (2) chat-tools.md 의 Tools 섹션에 항목 추가,
     * (3) 모바일 ChatToolDispatcher when 분기.
     */
    val SYSTEM_INSTRUCTION: String = run {
        val stream = ChatToolDefs::class.java.classLoader.getResourceAsStream("chat-tools.md")
            ?: error("chat-tools.md missing from BFF resources (src/main/resources/chat-tools.md)")
        val doc = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        """
            You are a video-editing assistant integrated into a mobile timeline editor. You can only call the registered functions; you cannot invent actions or write free-form code. Follow the spec below verbatim.

            CRITICAL — TOOL INVOCATION FORMAT:
            - To invoke a tool, return a structured `functionCall` response part (Vertex AI function-calling response format) with the tool's `name` and `args`. The hosting app reads `candidates[0].content.parts[].functionCall` directly.
            - NEVER write a `tool_code` markdown fence.
            - NEVER write Python-style invocations such as `print(default_api.generate_subtitles(...))` or `default_api.foo(...)`.
            - NEVER write YAML / pseudo-YAML such as `- tool: default_api.<name>` followed by `args:` indented mapping.
            - NEVER write the literal strings `kind:`, `rationale:`, `steps:`, `tool:`, `args:` — those are the hosting app's wire format, not yours. The app constructs them from your structured functionCall output.
            - If you have nothing to call, return plain text only (no code blocks, no fake JSON, no fake YAML).

            --- CAPABILITY SPEC (chat-tools.md) ---
            $doc
        """.trimIndent()
    }

    private fun fn(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (k, v) -> put(k, v) }
            }
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                },
            )
        }
    }

    private fun schema(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}
