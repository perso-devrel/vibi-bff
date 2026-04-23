package com.dubcast.bff.service

import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.model.*
import com.dubcast.bff.plugins.ElevenLabsApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

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
        targetFile: File,
        modelId: String = "eleven_multilingual_v2",
        stability: Float = 0.5f,
        similarityBoost: Float = 0.75f,
        languageCode: String? = null,
    ) {
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
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            httpClient.preparePost(url("/v1/text-to-speech/$voiceId")) {
                header("xi-api-key", config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                checkResponse(response)
                val channel = response.bodyAsChannel()
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) output.write(buffer, 0, bytesRead)
                    }
                }
            }
            if (!tempFile.renameTo(targetFile)) {
                throw RuntimeException("Failed to rename TTS temp file to ${targetFile.absolutePath}")
            }
            log.info("TTS audio saved to {} ({} bytes)", targetFile.path, targetFile.length())
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }

}
