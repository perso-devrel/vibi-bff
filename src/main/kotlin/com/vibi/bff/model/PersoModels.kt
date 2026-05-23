package com.vibi.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Perso wraps most responses in { "result": ... }. Keep a generic envelope
// so client-side calls don't need per-endpoint unwrapping boilerplate.
@Serializable
data class PersoEnvelope<T>(val result: T)

// --- Languages: 지원 타깃 언어 목록 (GET /video-translator/api/v1/languages) ---
@Serializable
data class PersoLanguage(
    val code: String,
    val name: String,
    @SerialName("native_name") val nativeName: String? = null,
)

@Serializable
data class PersoLanguagesResponse(
    val languages: List<PersoLanguage> = emptyList(),
)

// --- Upload: SAS token ---
@Serializable
data class PersoSasTokenResponse(
    @SerialName("blobSasUrl") val blobSasUrl: String,
    @SerialName("expirationDatetime") val expirationDatetime: String,
)

// --- Upload: register media ---
@Serializable
data class PersoRegisterMediaRequest(
    val spaceSeq: Int,
    val fileUrl: String,
    val fileName: String,
)

@Serializable
data class PersoMediaRegistration(
    val seq: Long,
    val originalName: String? = null,
    val videoFilePath: String? = null,
    val audioFilePath: String? = null,
    val thumbnailFilePath: String? = null,
    val size: Long? = null,
    val durationMs: Long? = null,
)

// --- Audio-separation submit ---
// `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/audio-separation`
@Serializable
data class PersoAudioSeparationRequest(
    val mediaSeq: Long,
    val isVideoProject: Boolean,
    val title: String? = null,
)

@Serializable
data class PersoTranslateResult(
    val startGenerateProjectIdList: List<Long>,
)

// --- Progress ---
@Serializable
data class PersoProgressResult(
    val projectSeq: Long,
    val progress: Int = 0,
    val progressReason: String? = null,
    val hasFailed: Boolean = false,
    val speedType: String? = null,
    val expectedRemainingTimeMinutes: Int? = null,
    val isCancelable: Boolean = false,
)

// --- Download info (availability flags) ---
// audio-separation 컨텍스트에서만 사용 — translation 관련 플래그는 제거됨.
@Serializable
data class PersoDownloadInfo(
    val hasOriginalVoiceOnly: Boolean = false,
    val hasOriginalBackground: Boolean = false,
    val hasOriginalSpeakerAudioCollection: Boolean = false,
    /** audio-separation 결과의 background (.wav) 가용 여부. `target=originalSubBackground` 응답과 align. */
    val hasOriginalSubBackground: Boolean = false,
)

// --- Project info — `/projects/{projectSeq}/spaces/{spaceSeq}` ---
@Serializable
data class PersoProjectInfo(
    val seq: Long? = null,
    val downloadInfo: PersoDownloadInfo? = null,
    val downloadPathInfo: PersoDownloadPathInfo? = null,
)

@Serializable
data class PersoDownloadPathInfo(
    /** 모든 화자 mix (BGM 제거). file 명 OriginalVoiceOnly. */
    val originalVoicePath: String? = null,
    /** 진짜 BGM only — file 명 OriginalBaseBackground. 화자 수 무관하게 분리됨. */
    val originalBackgroundPath: String? = null,
    /** Sub-background — file 명 OriginalSubBackground. 화자 수에 따라 의미 다른 케이스 발견. */
    val originalSubBackgroundPath: String? = null,
)

/** Audio-separation 프로젝트의 `/download?target=...` 응답. */
@Serializable
data class PersoSeparationDownloadLinks(
    val audioFile: PersoSeparationAudioFile? = null,
)

@Serializable
data class PersoSeparationAudioFile(
    /** target=originalVoiceSpeakers 응답의 .tar archive — 화자별 audio collection. */
    val voiceAudioDownloadLink: String? = null,
    /** target=originalSubBackground 응답의 .wav background. */
    val originalSubBackgroundDownloadLink: String? = null,
)
