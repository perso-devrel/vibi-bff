package com.vibi.bff.model

import kotlinx.serialization.Serializable

private fun requireValidVolume(name: String, v: Float) {
    require(v.isFinite() && v >= 0f) { "$name volume must be finite and >= 0" }
}

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

// --- Render ---
@Serializable
data class DubClip(
    val audioFileKey: String,
    val startMs: Long,
    val durationMs: Long,
    val volume: Float = 1.0f,
) {
    init {
        requireValidVolume("DubClip", volume)
    }
}

@Serializable
data class BgmClip(
    val audioFileKey: String,
    val startMs: Long,
    val volume: Float = 1.0f,
) {
    init {
        requireValidVolume("BgmClip", volume)
    }
}

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
    /**
     * Phase 1.5 audio-only render path. "video" (default) preserves legacy
     * full mp4 pipeline. "audio" trims/concats the segment audio tracks
     * (with speed/volume) and mixes bgmClips on top — emitting a single
     * .m4a (AAC). dubClips / audioOverride / separation stems are NOT
     * applied in audio mode (그 단계 전에 호출되는 자막/분리 파이프라인의
     * source 로 쓰이는 용도라 의미가 없음). sticker overlays · subtitle
     * burn-in · imageClips 는 무시된다 (audio 라 무관).
     *
     * 기존 클라이언트가 필드 없이 보내면 default "video" 로 하위 호환.
     */
    val outputKind: String = "video",
) {
    init {
        require(outputKind == "video" || outputKind == "audio") {
            "outputKind must be 'video' or 'audio' (got '$outputKind')"
        }
    }
}

@Serializable
data class SeparationDirectiveDto(
    val id: String,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val numberOfSpeakers: Int,
    val muteOriginalSegmentAudio: Boolean,
    /** stem 별 (URL + 볼륨). BFF 자체 HMAC-signed URL 만 허용 — 외부 URL 은 reject. */
    val selections: List<SeparationStemSelectionDto> = emptyList(),
) {
    init {
        require(rangeStartMs >= 0) {
            "SeparationDirectiveDto.rangeStartMs must be >= 0 (got $rangeStartMs)"
        }
        require(rangeEndMs > rangeStartMs) {
            "SeparationDirectiveDto.rangeEndMs ($rangeEndMs) must be > rangeStartMs ($rangeStartMs)"
        }
    }
}

@Serializable
data class SeparationStemSelectionDto(
    val stemId: String,
    val audioUrl: String,
    val volume: Float = 1.0f,
) {
    init {
        requireValidVolume("SeparationStemSelectionDto", volume)
    }
}

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
    /** Phase 1: 모바일이 편집된 영상 jobId 를 source 로 재사용. multipart `file` 파트가
     * 없으면 이 값으로 RenderService.getRenderOutputFile(jobId) 를 조회해 source 사용.
     * 둘 다 있으면 이 값이 우선 (file 파트 무시). */
    val editedRenderJobId: String? = null,
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
            requireValidVolume("StemMixRequest.stem", s.volume)
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
    /** Phase 1: 모바일이 편집된 영상 jobId 를 source 로 재사용. multipart `file` 파트가
     * 없으면 이 값으로 RenderService.getRenderOutputFile(jobId) 를 조회해 source 사용.
     * 둘 다 있으면 이 값이 우선 (file 파트 무시). */
    val editedRenderJobId: String? = null,
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

/**
 * `/api/v2/subtitles/regenerate` 전용 — 사용자가 수정한 SRT 를 source 로 다른 언어 자막
 * 재생성. Perso STT 미사용이라 [SubtitleSpec.mediaType] 같은 video/audio 플래그가 의미 없음.
 * 별도 DTO 로 분리해 모바일이 `mediaType="VIDEO"` 같은 dummy 값을 주입하는 패턴을 막는다.
 */
@Serializable
data class SubtitleRegenerateSpec(
    val sourceLanguageCode: String,
    val targetLanguageCodes: List<String> = emptyList(),
) {
    init {
        require(sourceLanguageCode.isNotBlank()) { "sourceLanguageCode required" }
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
    /** Phase 1: 모바일이 편집된 영상 jobId 를 source 로 재사용. multipart `file` 파트가
     * 없으면 이 값으로 RenderService.getRenderOutputFile(jobId) 를 조회해 source 사용.
     * 둘 다 있으면 이 값이 우선 (file 파트 무시). */
    val editedRenderJobId: String? = null,
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
    /** VIDEO 분기에서 dubbedVideoFile 의 audio 트랙 추출 실패 시 true. dubbedAudioUrl 은 null
     * 이 되고 모바일은 video URL 만 사용 가능. soft-fail 신호로, status 는 READY 유지. */
    val audioExtractFailed: Boolean = false,
)

