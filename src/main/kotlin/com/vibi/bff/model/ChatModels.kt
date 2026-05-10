package com.vibi.bff.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 모바일 → BFF 채팅 요청. */
@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val projectContext: ProjectContext,
    val locale: String = "ko",
)

/** 채팅 한 turn. role = "user" 또는 "model". */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * 모바일이 매 발화마다 전송하는 timeline 요약. Gemini 가 지시어("이 클립", "여기") 를 해석하기 위해
 * 필수. v1 은 매번 전체 전송 — 토큰 비용이 실측 문제일 때 diff 전략 도입.
 */
@Serializable
data class ProjectContext(
    val segments: List<ContextSegment> = emptyList(),
    val subtitleClips: List<ContextSubtitleClip> = emptyList(),
    val dubClips: List<ContextDubClip> = emptyList(),
    val bgmClips: List<ContextBgmClip> = emptyList(),
    val separationStems: List<ContextStem> = emptyList(),
    val separationDirectives: List<ContextSeparationDirective> = emptyList(),
    val currentPlayheadMs: Long = 0L,
    val selectedSegmentId: String? = null,
    val selectedClipId: String? = null,
    val isRangeSelecting: Boolean = false,
    val pendingRangeStartMs: Long? = null,
    val pendingRangeEndMs: Long? = null,
    /** 전체 timeline 길이 (모든 segment 합). "끝까지" 같은 발화 해석용 — Gemini 가 segments 합산할
     *  필요 없게 직접 노출. */
    val videoDurationMs: Long = 0L,
)

@Serializable
data class ContextSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val sourceUri: String,
    val speedScale: Float = 1.0f,
    val volumeScale: Float = 1.0f,
)

@Serializable
data class ContextSubtitleClip(
    val id: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val languageCode: String,
)

@Serializable
data class ContextDubClip(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val voiceId: String,
)

@Serializable
data class ContextBgmClip(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val volumeScale: Float = 1.0f,
    val speedScale: Float = 1.0f,
)

@Serializable
data class ContextStem(
    val stemId: String,
    val label: String,
    val volume: Float,
    val selected: Boolean,
)

/**
 * 이미 음성분리된 구간 — Gemini 가 (1) 중복 분리 회피 (2) 비용 안내 (3) 대안 제시
 * (기존 삭제 후 재분리 vs 짧은 분할) 판단을 위해 참조. 1분당 비용 기준.
 */
@Serializable
data class ContextSeparationDirective(
    val id: String,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val durationMs: Long,
    val numberOfSpeakers: Int,
)

/** Gemini 응답 형태. kind=text 또는 kind=proposal 둘 중 하나. */
@Serializable
data class ChatResponse(
    val kind: String,
    val text: String? = null,
    val proposal: Proposal? = null,
)

/**
 * 편집 의도 plan. 사용자가 confirm 카드의 [적용] 누르면 모바일 dispatcher 가 steps 를 순차 실행.
 * steps 는 등록된 EDIT_TOOLS 의 functionDeclaration name 만 포함. 최대 5개.
 */
@Serializable
data class Proposal(
    val rationale: String,
    val steps: List<ToolCall>,
)

@Serializable
data class ToolCall(
    val name: String,
    val args: JsonObject,
)
