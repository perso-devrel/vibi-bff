package com.vibi.bff.model

import kotlinx.serialization.Serializable

private fun requireValidVolume(name: String, v: Float) {
    require(v.isFinite() && v >= 0f) { "$name volume must be finite and >= 0" }
}

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

// --- Render ---
@Serializable
data class BgmClip(
    val audioFileKey: String,
    val startMs: Long,
    val volume: Float = 1.0f,
    /** 1.0 = 정상 속도. ffmpeg atempo 필터로 적용 (0.25..4 → atempo chain). */
    val speed: Float = 1.0f,
    /** 음원 내부 trim 시작 ms. 0 이면 음원 처음부터. */
    val sourceTrimStartMs: Long = 0L,
    /** 음원 내부 trim 끝 ms. 0 이면 음원 끝까지 (backward-compat). */
    val sourceTrimEndMs: Long = 0L,
) {
    init {
        requireValidVolume("BgmClip", volume)
        require(speed > 0f) { "BgmClip.speed must be > 0 (got $speed)" }
        require(sourceTrimStartMs >= 0L) { "BgmClip.sourceTrimStartMs must be >= 0 (got $sourceTrimStartMs)" }
        require(sourceTrimEndMs == 0L || sourceTrimEndMs > sourceTrimStartMs) {
            "BgmClip.sourceTrimEndMs ($sourceTrimEndMs) must be 0 or > sourceTrimStartMs ($sourceTrimStartMs)"
        }
    }
}

@Serializable
data class Segment(
    val sourceFileKey: String,
    val order: Int,
    val durationMs: Long,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val volumeScale: Float = 1.0f,
    val speedScale: Float = 1.0f,
) {
    init {
        require(speedScale > 0f) { "Segment.speedScale must be > 0 (got $speedScale)" }
        require(volumeScale >= 0f) { "Segment.volumeScale must be >= 0 (got $volumeScale)" }
    }
}

@Serializable
data class RenderConfig(
    val videoDurationMs: Long? = null,          // legacy (Task 2 이하)
    val segments: List<Segment>? = null,        // Task 3a+
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
     * 분리 파이프라인 입력 품질 보장 위해 별도 유지.
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
    /**
     * directive 가 앵커된 영상 세그먼트의 speedScale. stem audio 에 atempo 로 적용해 속도 조절된
     * 영상과 tempo 를 맞춘다. 1.0 = 원본 속도. rangeStart/End 는 클라이언트가 이미 속도 반영해
     * 압축한 타임라인 위치라, BFF 는 atrim 으로 rangeMs*speed 만큼 원본 stem 을 떼어 atempo=speed 로
     * 압축한다 (RenderService 참조).
     */
    val appliedSpeedScale: Float = 1.0f,
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
        require(appliedSpeedScale > 0f) {
            "SeparationDirectiveDto.appliedSpeedScale must be > 0 (got $appliedSpeedScale)"
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

// --- Render v3 (asset-by-reference) ---
@Serializable
data class SegmentV3(
    /** R2 asset key (`assets/<sha>.<ext>`). 라우트가 다운로드 후 로컬 파일로 해석. */
    val sourceAssetKey: String,
    val order: Int,
    val durationMs: Long,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val volumeScale: Float = 1.0f,
    val speedScale: Float = 1.0f,
) {
    init {
        require(sourceAssetKey.startsWith("assets/")) {
            "SegmentV3.sourceAssetKey must start with 'assets/' (got '$sourceAssetKey')"
        }
        require(speedScale > 0f) { "SegmentV3.speedScale must be > 0 (got $speedScale)" }
        require(volumeScale >= 0f) { "SegmentV3.volumeScale must be >= 0 (got $volumeScale)" }
    }
}

@Serializable
data class BgmClipV3(
    val audioAssetKey: String,
    val startMs: Long,
    val volume: Float = 1.0f,
    /** 1.0 = 정상 속도. ffmpeg atempo 필터로 적용 (0.25..4 → atempo chain). */
    val speed: Float = 1.0f,
    val sourceTrimStartMs: Long = 0L,
    val sourceTrimEndMs: Long = 0L,
) {
    init {
        require(audioAssetKey.startsWith("assets/")) {
            "BgmClipV3.audioAssetKey must start with 'assets/' (got '$audioAssetKey')"
        }
        requireValidVolume("BgmClipV3", volume)
        require(speed > 0f) { "BgmClipV3.speed must be > 0 (got $speed)" }
        require(sourceTrimStartMs >= 0L) { "BgmClipV3.sourceTrimStartMs must be >= 0" }
        require(sourceTrimEndMs == 0L || sourceTrimEndMs > sourceTrimStartMs) {
            "BgmClipV3.sourceTrimEndMs ($sourceTrimEndMs) must be 0 or > sourceTrimStartMs ($sourceTrimStartMs)"
        }
    }
}

@Serializable
data class RenderConfigV3(
    val segments: List<SegmentV3>,
    val bgmClips: List<BgmClipV3> = emptyList(),
    val separationDirectives: List<SeparationDirectiveDto> = emptyList(),
    val outputKind: String = "video",
    val quality: String = "medium",
) {
    init {
        require(segments.isNotEmpty()) { "RenderConfigV3.segments must not be empty" }
        require(outputKind == "video" || outputKind == "audio") {
            "outputKind must be 'video' or 'audio' (got '$outputKind')"
        }
        require(quality == "high" || quality == "medium" || quality == "low") {
            "quality must be 'high', 'medium', or 'low' (got '$quality')"
        }
    }
}

// --- Asset upload (v3 모바일 → R2 직접 PUT 흐름) ---
@Serializable
data class AssetUploadUrlRequest(
    val sha256Hex: String,
    val sizeBytes: Long,
    val ext: String,
    val contentType: String,
) {
    init {
        require(sha256Hex.matches(SHA256_HEX_RE)) { "sha256Hex must be 64-char lower hex" }
        require(sizeBytes > 0L) { "sizeBytes must be > 0" }
        require(ext.isNotBlank()) { "ext must not be blank" }
        require(contentType.isNotBlank()) { "contentType must not be blank" }
    }

    companion object {
        private val SHA256_HEX_RE = Regex("^[0-9a-f]{64}$")
    }
}

@Serializable
data class AssetUploadUrlResponse(
    val assetKey: String,
    val alreadyExists: Boolean,
    val uploadUrl: String? = null,
    val expiresInSec: Long = 0L,
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
)

// --- Separation ---
/**
 * 모바일이 trim + audio extract 까지 끝내고 보내는 audio bytes 의 메타. 본 BFF surface 는
 * 모바일이 항상 audio (m4a/mp3/wav) 만 보낸다는 contract — trim 윈도우, mediaType, 그리고
 * editedRenderJobId source 분기는 모두 모바일 측에서 처리되므로 spec 에 더 이상 들어오지 않는다.
 */
@Serializable
data class SeparationSpec(
    val sourceLanguageCode: String = "auto",
)

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
    /**
     * READY 상태에서 stem FLAC 의 실측 길이(ms). 클라이언트가 사용자 선택 trim 길이 대신 이 값을
     * 써서 timeline 막대(SeparationDirective.rangeEndMs) 와 stem 실제 길이를 1:1 매칭. null 이면
     * 측정 실패 → 클라이언트는 사용자 선택값 fallback.
     */
    val actualDurationMs: Long? = null,
    /**
     * QUEUED 상태일 때만 set. 1-based — `1` 은 다음 차례. 모바일이 "대기 N번째" 안내에 사용.
     * 다른 상태에선 null.
     */
    val queuePosition: Int? = null,
    /**
     * QUEUED 일 때 [queuePosition] × 최근 평균 처리 시간(초) 으로 계산한 추정 대기. 평균 표본
     * 부족하면 보수적 fallback 사용. null 이면 추정 불가 (QUEUED 가 아니거나 표본 0).
     */
    val estimatedWaitSec: Long? = null,
)

