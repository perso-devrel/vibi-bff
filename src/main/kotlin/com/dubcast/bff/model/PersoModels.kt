package com.dubcast.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Perso wraps most responses in { "result": ... }. Keep a generic envelope
// so client-side calls don't need per-endpoint unwrapping boilerplate.
@Serializable
data class PersoEnvelope<T>(val result: T)

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

@Serializable
data class PersoAudioFileLinks(
    val originalVoiceAudioDownloadLink: String? = null,
    val voiceAudioDownloadLink: String? = null,
    val backgroundAudioDownloadLink: String? = null,
    val voiceWithBackgroundAudioDownloadLink: String? = null,
    val translatedAudioDownloadLink: String? = null,
    // Speaker collection (typically ZIP); field name is best-effort —
    // Perso returns it for target=originalVoiceSpeakers. Keep loose parsing
    // via ignoreUnknownKeys = true in the client Json config.
    val originalVoiceSpeakersDownloadLink: String? = null,
)

@Serializable
data class PersoSrtFileLinks(
    val originalSubtitleDownloadLink: String? = null,
    val translatedSubtitleDownloadLink: String? = null,
)
