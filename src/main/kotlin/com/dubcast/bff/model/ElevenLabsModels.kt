package com.dubcast.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Voices ---
@Serializable
data class ElevenLabsVoice(
    @SerialName("voice_id") val voiceId: String,
    val name: String,
    @SerialName("preview_url") val previewUrl: String? = null,
    val category: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class ElevenLabsVoicesResponse(
    val voices: List<ElevenLabsVoice>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

// --- TTS Request ---
@Serializable
data class ElevenLabsTtsRequest(
    val text: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("voice_settings") val voiceSettings: ElevenLabsVoiceSettings,
    @SerialName("language_code") val languageCode: String? = null,
)

@Serializable
data class ElevenLabsVoiceSettings(
    val stability: Float,
    @SerialName("similarity_boost") val similarityBoost: Float,
)

// --- Speech-to-text (my_plan: 자막 STT 엔진을 ElevenLabs 로) ---
@Serializable
data class ElevenLabsSttWord(
    val text: String,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val type: String? = null
)

@Serializable
data class ElevenLabsSttResponse(
    val text: String,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("language_probability") val languageProbability: Double? = null,
    val words: List<ElevenLabsSttWord> = emptyList()
)

