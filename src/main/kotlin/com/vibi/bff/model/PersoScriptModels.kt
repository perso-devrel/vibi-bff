package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * Perso audio-separation 의 diarized script (문장 + 화자). cursor 페이지네이션 응답.
 * 필드명은 Perso 응답(camelCase)과 1:1. `PersoEnvelope<PersoScriptPage>.result` 로 unwrap.
 * 분리가 이미 만든 script 라 새 STT 잡 없이 읽는다(plugin 의 persoTypes 포팅).
 */
@Serializable
data class PersoScriptPage(
    val hasNext: Boolean = false,
    val nextCursorId: Long? = null,
    val sentences: List<PersoScriptSentence> = emptyList(),
    val speakers: List<PersoScriptSpeaker> = emptyList(),
)

@Serializable
data class PersoScriptSentence(
    val seq: Long = 0,
    val speakerOrderIndex: Int = 1,
    val offsetMs: Long = 0,
    val durationMs: Long = 0,
    val originalText: String = "",
    val audioUrl: String? = null,
)

@Serializable
data class PersoScriptSpeaker(
    val speakerOrderIndex: Int = 1,
    val externalSpeakerSeq: String? = null,
)
