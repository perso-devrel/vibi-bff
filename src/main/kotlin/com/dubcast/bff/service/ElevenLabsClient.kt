package com.dubcast.bff.service

import com.dubcast.bff.config.ElevenLabsConfig
import com.dubcast.bff.model.ElevenLabsLipSyncResponse
import com.dubcast.bff.model.ElevenLabsLipSyncStatus
import com.dubcast.bff.model.ElevenLabsVoicesResponse
import com.dubcast.bff.plugins.ElevenLabsApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import org.slf4j.LoggerFactory
import java.io.File

class ElevenLabsClient(
    private val config: ElevenLabsConfig,
    private val httpClient: HttpClient,
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
    suspend fun getVoices(): ElevenLabsVoicesResponse {
        log.info("Fetching voice list")
        val response = httpClient.get(url("/v1/voices")) {
            header("xi-api-key", config.apiKey)
        }
        checkResponse(response)
        return response.body()
    }

    // --- TTS ---
    suspend fun textToSpeech(
        voiceId: String,
        text: String,
        modelId: String = "eleven_multilingual_v2",
        stability: Float = 0.5f,
        similarityBoost: Float = 0.75f,
        languageCode: String? = null,
    ): ByteArray {
        log.info("TTS request: voiceId={}, text length={}", voiceId, text.length)
        val response = httpClient.post(url("/v1/text-to-speech/$voiceId")) {
            header("xi-api-key", config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildTtsBody(text, modelId, stability, similarityBoost, languageCode))
        }
        checkResponse(response)
        return response.body()
    }

    private fun buildTtsBody(
        text: String,
        modelId: String,
        stability: Float,
        similarityBoost: Float,
        languageCode: String?,
    ): String {
        val langPart = if (languageCode != null) ""","language_code":"$languageCode"""" else ""
        return """{"text":"$text","model_id":"$modelId","voice_settings":{"stability":$stability,"similarity_boost":$similarityBoost}$langPart}"""
    }

    // --- Lip-Sync ---
    suspend fun createLipSync(videoFile: File, audioFile: File): ElevenLabsLipSyncResponse {
        log.info("Creating lip-sync: video={}, audio={}", videoFile.name, audioFile.name)
        val response = httpClient.submitFormWithBinaryData(
            url = url("/v1/dubbing"),
            formData = formData {
                append("file", ChannelProvider(videoFile.length()) { ByteReadChannel(videoFile.readBytes()) }, Headers.build {
                    append(HttpHeaders.ContentType, "video/mp4")
                    append(HttpHeaders.ContentDisposition, "filename=\"${videoFile.name}\"")
                })
                append("file", ChannelProvider(audioFile.length()) { ByteReadChannel(audioFile.readBytes()) }, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                })
                append("mode", "lip_sync")
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

    suspend fun downloadLipSyncResult(id: String, langCode: String, targetFile: File) {
        log.info("Downloading lip-sync result: id={}", id)
        httpClient.prepareGet(url("/v1/dubbing/$id/audio/$langCode")) {
            header("xi-api-key", config.apiKey)
        }.execute { response ->
            checkResponse(response)
            val channel = response.bodyAsChannel()
            targetFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            log.info("Downloaded lip-sync result to {} ({} bytes)", targetFile.path, targetFile.length())
        }
    }
}
