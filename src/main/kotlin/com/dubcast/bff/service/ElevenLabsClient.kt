package com.dubcast.bff.service

import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.model.*
import com.dubcast.bff.plugins.ElevenLabsApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

class ElevenLabsClient(
    private val config: ElevenLabsConfig,
    private val httpClient: HttpClient,
    private val voicesCacheTtlMs: Long = 600_000,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun url(path: String) = "${config.baseUrl}$path"

    private suspend fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw ElevenLabsApiException(response.status.value, body)
        }
    }

    // --- Voices ---
    private val voicesCacheMutex = Mutex()
    @Volatile private var voicesCache: ElevenLabsVoicesResponse? = null
    @Volatile private var voicesCachedAt: Long = 0L

    private fun voicesCacheFresh(): ElevenLabsVoicesResponse? {
        val cached = voicesCache ?: return null
        return if (System.currentTimeMillis() - voicesCachedAt < voicesCacheTtlMs) cached else null
    }

    suspend fun getVoices(): ElevenLabsVoicesResponse {
        voicesCacheFresh()?.let { return it }
        return voicesCacheMutex.withLock {
            // Re-check inside lock so concurrent first callers reuse the same fetch.
            voicesCacheFresh()?.let { return@withLock it }
            val fresh = fetchVoices()
            voicesCache = fresh
            voicesCachedAt = System.currentTimeMillis()
            fresh
        }
    }

    fun invalidateVoicesCache() {
        voicesCache = null
        voicesCachedAt = 0L
    }

    private suspend fun fetchVoices(): ElevenLabsVoicesResponse {
        log.info("Fetching voice list from upstream")
        val allVoices = mutableListOf<ElevenLabsVoice>()
        var pageToken: String? = null
        var hasMore = true

        while (hasMore) {
            val response = httpClient.get(url("/v2/voices")) {
                header("xi-api-key", config.apiKey)
                parameter("page_size", 100)
                if (pageToken != null) parameter("next_page_token", pageToken)
            }
            checkResponse(response)
            val page: ElevenLabsVoicesResponse = response.body()
            allVoices.addAll(page.voices)
            pageToken = page.nextPageToken
            hasMore = page.hasMore && pageToken != null
        }

        return ElevenLabsVoicesResponse(voices = allVoices, totalCount = allVoices.size)
    }

    private val voiceIdPattern = Regex("^[a-zA-Z0-9_-]+$")

    // --- TTS ---
    suspend fun textToSpeech(
        voiceId: String,
        text: String,
        modelId: String = "eleven_multilingual_v2",
        stability: Float = 0.5f,
        similarityBoost: Float = 0.75f,
        languageCode: String? = null,
    ): ByteArray {
        require(voiceIdPattern.matches(voiceId)) { "Invalid voiceId: $voiceId" }
        log.info("TTS request: voiceId={}, text length={}", voiceId, text.length)
        val requestBody = ElevenLabsTtsRequest(
            text = text,
            modelId = modelId,
            voiceSettings = ElevenLabsVoiceSettings(
                stability = stability,
                similarityBoost = similarityBoost,
            ),
            languageCode = languageCode,
        )
        val response = httpClient.post(url("/v1/text-to-speech/$voiceId")) {
            header("xi-api-key", config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        checkResponse(response)
        return response.body()
    }

    // --- Lip-Sync ---
    suspend fun createLipSync(
        videoFile: File,
        audioFile: File,
        targetLang: String = "en",
        startMs: Long? = null,
        durationMs: Long? = null,
    ): ElevenLabsLipSyncResponse {
        log.info("Creating lip-sync: video={}, audio={}, targetLang={}, startMs={}, durationMs={}", videoFile.name, audioFile.name, targetLang, startMs, durationMs)
        val response = httpClient.submitFormWithBinaryData(
            url = url("/v1/dubbing"),
            formData = formData {
                append("file", ChannelProvider(videoFile.length()) { ByteReadChannel(FileInputStream(videoFile).asSource().buffered()) }, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"${videoFile.name}\"")
                })
                append("file", ChannelProvider(audioFile.length()) { ByteReadChannel(FileInputStream(audioFile).asSource().buffered()) }, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                })
                append("target_lang", targetLang)
                append("mode", "automatic")
                if (startMs != null || durationMs != null) {
                    val startSec = (startMs ?: 0) / 1000
                    append("start_time", startSec.toString())
                    if (durationMs != null) {
                        val endSec = ((startMs ?: 0) + durationMs) / 1000
                        append("end_time", endSec.toString())
                    }
                }
            }
        ) {
            header("xi-api-key", config.apiKey)
        }
        checkResponse(response)
        return response.body()
    }

    suspend fun getLipSyncStatus(id: String): ElevenLabsLipSyncStatus {
        val response = httpClient.get(url("/v1/dubbing/$id")) {
            header("xi-api-key", config.apiKey)
        }
        checkResponse(response)
        return response.body()
    }

    private val downloadInFlight = ConcurrentHashMap<String, Boolean>()

    suspend fun downloadLipSyncResult(id: String, langCode: String, targetFile: File) {
        if (targetFile.exists()) return
        if (downloadInFlight.putIfAbsent(id, true) != null) {
            log.info("Download already in flight for id={}, waiting", id)
            while (downloadInFlight.containsKey(id)) {
                kotlinx.coroutines.delay(500)
            }
            return
        }
        try {
            log.info("Downloading lip-sync result: id={}", id)
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            httpClient.prepareGet(url("/v1/dubbing/$id/audio/$langCode")) {
                header("xi-api-key", config.apiKey)
            }.execute { response ->
                checkResponse(response)
                val channel = response.bodyAsChannel()
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            tempFile.renameTo(targetFile)
            log.info("Downloaded lip-sync result to {} ({} bytes)", targetFile.path, targetFile.length())
        } finally {
            downloadInFlight.remove(id)
        }
    }
}
