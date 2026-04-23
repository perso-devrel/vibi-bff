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
data class BgmClip(
    val audioFileKey: String,
    val startMs: Long,
    val volume: Float = 1.0f,
)

@Serializable
data class ImageClip(
    val imageFileKey: String,
    val startMs: Long,
    val endMs: Long,
    val xPct: Float,
    val yPct: Float,
    val widthPct: Float,
    val heightPct: Float,
)

@Serializable
data class Segment(
    val sourceFileKey: String,
    val type: String, // "VIDEO" or "IMAGE"
    val order: Int,
    val durationMs: Long,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val imageXPct: Float? = null,
    val imageYPct: Float? = null,
    val imageWidthPct: Float? = null,
    val imageHeightPct: Float? = null,
    val volumeScale: Float = 1.0f,
    val speedScale: Float = 1.0f,
) {
    init {
        require(speedScale > 0f) { "Segment.speedScale must be > 0 (got $speedScale)" }
        require(volumeScale >= 0f) { "Segment.volumeScale must be >= 0 (got $volumeScale)" }
    }
}

@Serializable
data class FrameConfig(
    val width: Int,
    val height: Int,
    val backgroundColorHex: String = "#000000",
) {
    init {
        require(width > 0) { "FrameConfig.width must be > 0 (got $width)" }
        require(height > 0) { "FrameConfig.height must be > 0 (got $height)" }
    }
}

@Serializable
data class RenderConfig(
    val dubClips: List<DubClip>,
    val videoDurationMs: Long? = null,          // legacy (Task 2 이하)
    val segments: List<Segment>? = null,        // Task 3a+
    val imageClips: List<ImageClip> = emptyList(), // Task 2
    val frame: FrameConfig? = null,
    val bgmClips: List<BgmClip> = emptyList(),
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

// --- Separation ---
@Serializable
data class SeparationSpec(
    val mediaType: String,                  // "VIDEO" | "AUDIO"
    val numberOfSpeakers: Int,
    val sourceLanguageCode: String = "auto",
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
) {
    init {
        require(mediaType == "VIDEO" || mediaType == "AUDIO") {
            "mediaType must be VIDEO or AUDIO (got $mediaType)"
        }
        require(numberOfSpeakers in 1..10) {
            "numberOfSpeakers must be in 1..10 (got $numberOfSpeakers)"
        }
        // Trim is optional; both must be present together. File-duration
        // check lives in the route where ffprobe is available.
        // NOTE: error messages are wire error codes — clients switch on
        // `ErrorResponse.error`, so don't reword without updating the
        // SeparationSpec contract in README + separation-pipeline skill.
        require((trimStartMs == null) == (trimEndMs == null)) { "partial_trim_range" }
        if (trimStartMs != null && trimEndMs != null) {
            require(trimStartMs >= 0) { "trim_start_negative" }
            require(trimEndMs > trimStartMs) { "trim_range_invalid" }
            require(trimEndMs - trimStartMs >= 500) { "trim_range_too_short" }
        }
    }
}

@Serializable
data class SeparationResponse(val jobId: String)

@Serializable
data class StemInfo(
    val stemId: String,
    val label: String,
    val url: String,
    val durationMs: Long? = null,
)

@Serializable
data class SeparationStatusResponse(
    val jobId: String,
    val status: String,
    val progress: Int? = null,
    val progressReason: String? = null,
    val error: String? = null,
    val stems: List<StemInfo> = emptyList(),
    val mixJobId: String? = null,
)

@Serializable
data class StemMixSelection(
    val stemId: String,
    val volume: Float = 1.0f,
)

@Serializable
data class StemMixRequest(
    val stems: List<StemMixSelection>,
) {
    init {
        require(stems.isNotEmpty()) { "stems must not be empty" }
        for (s in stems) {
            require(s.volume >= 0f) { "stem volume must be >= 0 (got ${s.volume})" }
        }
    }
}

@Serializable
data class StemMixResponse(val mixJobId: String)

@Serializable
data class StemMixStatusResponse(
    val mixJobId: String,
    val status: String,
    val progress: Int? = null,
    val error: String? = null,
    val downloadUrl: String? = null,
)

