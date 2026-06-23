package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * Adobe 플러그인 history(저장된 분리 목록·script) 응답 DTO.
 * 필드명은 plugin 클라(`src/jobs/separationClient.ts` 의 SavedSeparationWire / ScriptDraft)와 1:1.
 */

/** GET /api/v2/separations — owner+project 의 READY 분리 목록(최신순). */
@Serializable
data class SeparationHistoryResponse(
    val separations: List<SeparationHistoryItem>,
)

@Serializable
data class SeparationHistoryItem(
    val jobId: String,
    val fileName: String? = null,
    val byteLength: Long? = null,
    val durationMs: Long? = null,
    val createdAt: Long,
    val hasScript: Boolean,
    val stems: List<HistoryStem>,
)

@Serializable
data class HistoryStem(
    val stemId: String,
    val label: String,
)

/** GET /api/v2/separate/{jobId}/script — 분리가 만든 diarized script draft. */
@Serializable
data class ScriptDraftResponse(
    val speakers: List<ScriptSpeaker>,
    val segments: List<ScriptSegment>,
)

@Serializable
data class ScriptSpeaker(
    val index: Int,
    val label: String,
)

@Serializable
data class ScriptSegment(
    val id: String,
    val speakerIndex: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
)
