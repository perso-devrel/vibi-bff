package com.dubcast.bff.model

import kotlinx.serialization.Serializable

// --- Upload ---
@Serializable
data class UploadResponse(val blobPath: String)

// --- Error ---
@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null,
)

// --- Voices ---
@Serializable
data class Voice(
    val voiceId: String,
    val name: String,
    val previewUrl: String? = null,
    val language: String? = null,
    val category: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class VoiceListResponse(
    val voices: List<Voice>,
)

// --- TTS ---
@Serializable
data class TtsRequest(
    val text: String,
    val voiceId: String,
    val languageCode: String? = null,
    val modelId: String = "eleven_multilingual_v2",
    val stability: Float = 0.5f,
    val similarityBoost: Float = 0.75f,
)

@Serializable
data class TtsResponse(
    val audioUrl: String,
    val durationMs: Long? = null,
)

// --- Render ---
@Serializable
data class DubClip(
    val audioFileKey: String,
    val startMs: Long,
    val durationMs: Long,
    val volume: Float = 1.0f,
)

@Serializable
data class RenderConfig(
    val dubClips: List<DubClip>,
    val videoDurationMs: Long,
)

@Serializable
data class RenderResponse(
    val jobId: String,
)

@Serializable
data class RenderStatusResponse(
    val jobId: String,
    val status: String,
    val progress: Int? = null,
    val error: String? = null,
)

// --- Lip-Sync ---
@Serializable
data class LipSyncRequest(
    val videoBlobPath: String,
    val audioBlobPath: String,
)

@Serializable
data class LipSyncStatusResponse(
    val id: String,
    val status: String,
    val outputVideoUrl: String? = null,
    val error: String? = null,
)
