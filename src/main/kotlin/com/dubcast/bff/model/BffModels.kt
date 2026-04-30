package com.dubcast.bff.model

import kotlinx.serialization.Serializable

// --- Upload ---
@Serializable
data class UploadResponse(val blobPath: String)

// --- Mock testdata (음성분리 폴더 구조 기반) ---
@Serializable
data class TestdataSeparationFolder(
    val folder: String,
    val startSec: Int,
    val endSec: Int,
    val stems: List<String>,
)

// --- Error ---
@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null,
)

// --- Languages (Perso 가 지원하는 타깃 언어 목록) ---
@Serializable
data class LanguageOption(
    val code: String,
    val name: String,
    val nativeName: String? = null,
    val supportsDubbing: Boolean = true,
    val supportsSubtitles: Boolean = true,
)

@Serializable
data class LanguageListResponse(
    val languages: List<LanguageOption>,
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
    // Phase 3: when set, the multipart upload includes an `audio_override`
    // file that fully replaces the source video's audio track. Auto-dub
    // produces this by running Perso translate on the unmodified source,
    // so timestamps align 1:1 with the original timeline. dubClips and
    // bgmClips continue to mix on top.
    val audioOverrideKey: String? = null,
    /** my_plan: 음성분리 명세 — 원본 + 모든 더빙 영상에 동일 적용. 각 directive 의
     * stem `audioUrl` 을 BFF 가 다운로드(또는 자체 HMAC URL 매핑) 후 ffmpeg amix 로 합성. */
    val separationDirectives: List<SeparationDirectiveDto> = emptyList(),
    /** 결과 영상의 언어 (출력 파일명·메타에 활용). */
    val outputLanguageCode: String = "original",
)

@Serializable
data class SeparationDirectiveDto(
    val id: String,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val numberOfSpeakers: Int,
    val muteOriginalSegmentAudio: Boolean,
    /** stem 별 (URL + 볼륨). BFF 자체 HMAC-signed URL 또는 외부 URL 둘 다 허용. */
    val selections: List<SeparationStemSelectionDto> = emptyList(),
)

@Serializable
data class SeparationStemSelectionDto(
    val stemId: String,
    val audioUrl: String,
    val volume: Float = 1.0f,
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
    /** "queued" while waiting for an ffmpeg permit, otherwise null. */
    val progressReason: String? = null,
)

/**
 * Response of `POST /api/v2/render/inputs` — the shared input cache lets a
 * client upload a single source video + segment audios once and reference
 * them across many `POST /render` calls (one per variant) by [inputId].
 *
 * [inputId] is deterministic (sha256-prefix of the video bytes) so the same
 * video uploaded twice resolves to the same cache slot — only `lastAccessAt`
 * is bumped on hit, no re-write of the body.
 */
@Serializable
data class RenderInputCacheResponse(
    val inputId: String,
    val expiresAt: Long,
    val videoSizeBytes: Long,
    val audioCount: Int,
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

// --- Auto subtitles ---
@Serializable
data class SubtitleSpec(
    val mediaType: String,                          // "VIDEO" | "AUDIO"
    val sourceLanguageCode: String,                 // ISO code, never "auto" (Perso STT 필요)
    /** 번역해야 할 언어들. 빈 리스트면 STT 만 (originalSrt). source 와 동일 lang 은 무시. */
    val targetLanguageCodes: List<String> = emptyList(),
    val numberOfSpeakers: Int = 1,
) {
    init {
        require(mediaType == "VIDEO" || mediaType == "AUDIO") {
            "mediaType must be VIDEO or AUDIO (got $mediaType)"
        }
        require(sourceLanguageCode.isNotBlank()) { "sourceLanguageCode required" }
        require(numberOfSpeakers in 1..10) {
            "numberOfSpeakers must be in 1..10 (got $numberOfSpeakers)"
        }
    }
}

@Serializable
data class SubtitleJobResponse(val jobId: String)

@Serializable
data class SubtitleStatusResponse(
    val jobId: String,
    val status: String,
    val progress: Int? = null,
    val progressReason: String? = null,
    val error: String? = null,
    val originalSrtUrl: String? = null,
    /** 언어 코드 → 번역된 SRT signed URL. 1 STT + N 번역 패턴의 결과 노출 채널. */
    val translatedSrtUrlsByLang: Map<String, String> = emptyMap(),
    /** legacy 단일 필드 (첫 번째 번역된 URL) — 구 클라이언트 호환용. */
    val translatedSrtUrl: String? = null,
)

// --- Auto dubbing ---
@Serializable
data class AutoDubSpec(
    val mediaType: String,                   // "VIDEO" | "AUDIO"
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val numberOfSpeakers: Int = 1,
    val ttsModel: String? = null,
) {
    init {
        require(mediaType == "VIDEO" || mediaType == "AUDIO") {
            "mediaType must be VIDEO or AUDIO (got $mediaType)"
        }
        require(sourceLanguageCode.isNotBlank()) { "sourceLanguageCode required" }
        require(targetLanguageCode.isNotBlank()) { "targetLanguageCode required" }
        require(targetLanguageCode != sourceLanguageCode) {
            "targetLanguageCode must differ from sourceLanguageCode for auto dubbing"
        }
        require(numberOfSpeakers in 1..10) {
            "numberOfSpeakers must be in 1..10 (got $numberOfSpeakers)"
        }
    }
}

@Serializable
data class AutoDubJobResponse(val jobId: String)

@Serializable
data class AutoDubStatusResponse(
    val jobId: String,
    val status: String,
    val progress: Int? = null,
    val progressReason: String? = null,
    val error: String? = null,
    val dubbedAudioUrl: String? = null,
    /** 영상 + 더빙 audio 가 ffmpeg mux 된 mp4 의 signed download URL. mobile 미리보기용. */
    val dubbedVideoUrl: String? = null,
)

