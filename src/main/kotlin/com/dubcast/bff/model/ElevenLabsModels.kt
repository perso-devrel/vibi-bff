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
    val error: String? = null,
)
