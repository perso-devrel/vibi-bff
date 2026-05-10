package com.dubcast.bff.model

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
    @SerialName("supports_dubbing") val supportsDubbing: Boolean = true,
    @SerialName("supports_subtitles") val supportsSubtitles: Boolean = true,
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

// --- STT / Audio-separation submit (전용 endpoint) ---
// `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/stt`
// `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/audio-separation`
// 두 endpoint 가 request body 형식이 동일.
@Serializable
data class PersoSttRequest(
    val mediaSeq: Long,
    val isVideoProject: Boolean,
    val title: String? = null,
)

@Serializable
data class PersoAudioSeparationRequest(
    val mediaSeq: Long,
    val isVideoProject: Boolean,
    val title: String? = null,
)

// STT/audio-separation script — sentences + speakers (paginated by nextCursorId).
// `audioUrl` 은 audio-separation 응답에만 존재 (STT 는 null).
@Serializable
data class PersoScriptResponse(
    val hasNext: Boolean = false,
    val nextCursorId: Long? = null,
    val sentences: List<PersoScriptSentence> = emptyList(),
    val speakers: List<PersoScriptSpeaker> = emptyList(),
)

@Serializable
data class PersoScriptSentence(
    val seq: Long,
    val externalScriptSeq: String? = null,
    val speakerOrderIndex: Int = 1,
    val offsetMs: Long = 0,
    val durationMs: Long = 0,
    val originalDraftText: String? = null,
    val originalText: String? = null,
    val audioUrl: String? = null,
)

@Serializable
data class PersoScriptSpeaker(
    val speakerOrderIndex: Int,
    val externalSpeakerSeq: String? = null,
)

// --- Translate submit ---
@Serializable
data class PersoTranslateRequest(
    val mediaSeq: Long,
    val isVideoProject: Boolean,
    val sourceLanguageCode: String,
    val targetLanguageCodes: List<String>,
    val numberOfSpeakers: Int,
    val preferredSpeedType: String = "GREEN",
    val withLipSync: Boolean = false,
    val title: String? = null,
    val ttsModel: String? = null,
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
@Serializable
data class PersoDownloadInfo(
    val hasPreviousProjectVideo: Boolean = false,
    val hasTranslatedVideo: Boolean = false,
    val hasLipSyncVideo: Boolean = false,
    val hasOriginalSubtitle: Boolean = false,
    val hasTranslatedSubtitle: Boolean = false,
    val hasOriginalVoiceOnly: Boolean = false,
    val hasTranslatedVoice: Boolean = false,
    val hasOriginalBackground: Boolean = false,
    val hasTranslatedBackground: Boolean = false,
    val hasTranslateAudio: Boolean? = null,
    val hasTranslatedVoiceWithBackground: Boolean? = null,
    val hasZipDownload: Boolean = false,
    val hasOriginalSpeakerAudioCollection: Boolean = false,
    val hasSpeakerSegmentExcel: Boolean = false,
    val hasSpeakerSegmentWithTranslationExcel: Boolean = false,
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

// --- Download links ---
@Serializable
data class PersoDownloadLinksResult(
    val videoFile: PersoVideoFileLinks? = null,
    val audioFile: PersoAudioFileLinks? = null,
    val srtFile: PersoSrtFileLinks? = null,
    val zippedFileDownloadLink: String? = null,
)

@Serializable
data class PersoVideoFileLinks(
    val videoDownloadLink: String? = null,
)

/** Translation 프로젝트 (`/download?target=dubbingVideo|translatedAudio` 등) 의 audio file 링크. */
@Serializable
data class PersoAudioFileLinks(
    val originalVoiceAudioDownloadLink: String? = null,
    val backgroundAudioDownloadLink: String? = null,
    val voiceWithBackgroundAudioDownloadLink: String? = null,
    val translatedAudioDownloadLink: String? = null,
    /** target=translatedVoice 응답의 더빙 오디오 링크. */
    val translatedVoiceDownloadLink: String? = null,
    /** translation 컨텍스트 legacy fallback — AutoDubService 가 dubbed audio 추론 시 마지막 후보. */
    val voiceAudioDownloadLink: String? = null,
)

/** Audio-separation 프로젝트의 `/download?target=...` 응답 — translation 응답과 필드 의미 다름. */
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

@Serializable
data class PersoSrtFileLinks(
    val originalSubtitleDownloadLink: String? = null,
    val translatedSubtitleDownloadLink: String? = null,
)
