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

// --- Lip-Sync ---
@Serializable
data class ElevenLabsLipSyncResponse(
    @SerialName("dubbing_id") val id: String,
    val status: String? = null,
)

@Serializable
data class ElevenLabsLipSyncStatus(
    @SerialName("dubbing_id") val id: String,
    val status: String,
    @SerialName("target_languages") val targetLanguages: List<String> = emptyList(),
    val error: String? = null,
)
