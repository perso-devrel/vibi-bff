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
)

@Serializable
data class LanguageListResponse(
    val languages: List<LanguageOption>,
)

// --- Render ---
@Serializable
data class BgmClip(
    val audioFileKey: String,
    val startMs: Long,
    val volume: Float = 1.0f,
    /** 음원 내부 trim 시작 ms. 0 이면 음원 처음부터. */
    val sourceTrimStartMs: Long = 0L,
    /** 음원 내부 trim 끝 ms. 0 이면 음원 끝까지 (backward-compat). */
    val sourceTrimEndMs: Long = 0L,
) {
    init {
        requireValidVolume("BgmClip", volume)
        require(sourceTrimStartMs >= 0L) { "BgmClip.sourceTrimStartMs must be >= 0 (got $sourceTrimStartMs)" }
        require(sourceTrimEndMs == 0L || sourceTrimEndMs > sourceTrimStartMs) {
            "BgmClip.sourceTrimEndMs ($sourceTrimEndMs) must be 0 or > sourceTrimStartMs ($sourceTrimStartMs)"
        }
    }
}

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
    val videoDurationMs: Long? = null,          // legacy (Task 2 이하)
    val segments: List<Segment>? = null,        // Task 3a+
    val frame: FrameConfig? = null,
    val bgmClips: List<BgmClip> = emptyList(),
    /** my_plan: 음성분리 명세. 각 directive 의 stem `audioUrl` 을 BFF 가 다운로드
     * (또는 자체 HMAC URL 매핑) 후 ffmpeg amix 로 합성. */
    val separationDirectives: List<SeparationDirectiveDto> = emptyList(),
    /**
     * Phase 1.5 audio-only render path. "video" (default) preserves legacy
     * full mp4 pipeline. "audio" trims/concats the segment audio tracks
     * (with speed/volume) and mixes bgmClips on top — emitting a single
     * .m4a (AAC). separation stems 는 audio 모드에선 적용되지 않는다 —
     * 분리 파이프라인의 source 로 쓰이는 용도라 의미 없음.
     *
     * 기존 클라이언트가 필드 없이 보내면 default "video" 로 하위 호환.
     */
    val outputKind: String = "video",
    /**
     * 출력 영상 품질 프로필. 파일 사이즈 = egress 비용 직결.
     *   "high"   — CRF 20, preset slow, audio 192k. 시각적 무손실급, 사이즈 큼.
     *   "medium" — CRF 23, preset fast, audio 192k. 기본값. 기존 동작과 동일.
     *   "low"    — CRF 28, preset fast, audio 128k. medium 대비 ~50% 작은 파일.
     * audio 모드 (outputKind="audio") 에선 quality 무시 — 오디오 비트레이트는
     * 자막/분리 파이프라인 입력 품질 보장 위해 별도 유지.
     */
    val quality: String = "medium",
) {
    init {
        require(outputKind == "video" || outputKind == "audio") {
            "outputKind must be 'video' or 'audio' (got '$outputKind')"
        }
        require(quality == "high" || quality == "medium" || quality == "low") {
            "quality must be 'high', 'medium', or 'low' (got '$quality')"
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
    /**
     * Stem audio 파일 안에서 본 directive piece 가 시작하는 offset (ms).
     *
     * 기본 0 = 신규 분리 결과 (stem audio 전체가 directive 의 range 와 1:1 매핑).
     * 모바일 클라이언트가 영상 range delete 로 directive 를 split 하면, 뒤쪽 piece 의
     * sourceOffsetMs 가 누적된다 — 같은 stem audio URL 을 공유한 채 ffmpeg `atrim` 으로
     * 해당 offset 부터 잘라 mix.
     */
    val sourceOffsetMs: Long = 0L,
) {
    init {
        require(rangeStartMs >= 0) {
            "SeparationDirectiveDto.rangeStartMs must be >= 0 (got $rangeStartMs)"
        }
        require(rangeEndMs > rangeStartMs) {
            "SeparationDirectiveDto.rangeEndMs ($rangeEndMs) must be > rangeStartMs ($rangeStartMs)"
        }
        require(sourceOffsetMs >= 0) {
            "SeparationDirectiveDto.sourceOffsetMs must be >= 0 (got $sourceOffsetMs)"
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

