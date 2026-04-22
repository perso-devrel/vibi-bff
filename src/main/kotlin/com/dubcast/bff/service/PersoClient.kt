package com.dubcast.bff.service

import com.dubcast.bff.config.PersoConfig
import com.dubcast.bff.model.*
import com.dubcast.bff.plugins.PersoApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

enum class PersoMediaType { VIDEO, AUDIO }

class PersoClient(
    private val config: PersoConfig,
    private val httpClient: HttpClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun url(path: String) = "${config.baseUrl}$path"

    private fun HttpRequestBuilder.authHeader() {
        header("XP-API-KEY", config.apiKey)
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw PersoApiException(response.status.value, body)
        }
    }

    // ── Step 1: Get SAS token ────────────────────────────────────────────────
    suspend fun getSasToken(fileName: String): PersoSasTokenResponse {
        val encoded = URLEncoder.encode(fileName, Charsets.UTF_8)
        val response = httpClient.get(url("/file/api/upload/sas-token?fileName=$encoded")) {
            authHeader()
        }
        checkResponse(response)
        return response.body()
    }

    // ── Step 2: Upload raw bytes to Azure Blob via SAS URL ───────────────────
    // The SAS URL is pre-signed — must NOT send XP-API-KEY here, and must set
    // x-ms-blob-type so Azure accepts it as a block blob upload. Streamed via
    // ReadChannelContent so 500 MB uploads don't pin the full file in heap.
    suspend fun uploadToBlob(sasUrl: String, file: File) {
        val response = httpClient.put(sasUrl) {
            header("x-ms-blob-type", "BlockBlob")
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentLength: Long = file.length()
                override val contentType: ContentType = ContentType.Application.OctetStream
                override fun readFrom(): ByteReadChannel =
                    ByteReadChannel(FileInputStream(file).asSource().buffered())
            })
        }
        checkResponse(response)
    }

    // ── Step 3: Register media with Perso backend ────────────────────────────
    // The `fileUrl` must be the SAS URL with the ?query-string stripped —
    // Perso stores the path-only form and signs its own reads internally.
    suspend fun registerMedia(
        mediaType: PersoMediaType,
        sasUrl: String,
        fileName: String,
    ): PersoMediaRegistration {
        val path = when (mediaType) {
            PersoMediaType.VIDEO -> "/file/api/upload/video"
            PersoMediaType.AUDIO -> "/file/api/upload/audio"
        }
        val fileUrl = sasUrl.substringBefore('?')
        val response = httpClient.put(url(path)) {
            authHeader()
            contentType(ContentType.Application.Json)
            setBody(PersoRegisterMediaRequest(
                spaceSeq = config.spaceSeq,
                fileUrl = fileUrl,
                fileName = fileName,
            ))
        }
        checkResponse(response)
        return response.body()
    }

    // Convenience: SAS + blob upload + register in one call.
    suspend fun uploadMedia(mediaType: PersoMediaType, file: File): PersoMediaRegistration {
        log.info("Uploading to Perso: {} ({} bytes)", file.name, file.length())
        val sas = getSasToken(file.name)
        uploadToBlob(sas.blobSasUrl, file)
        return registerMedia(mediaType, sas.blobSasUrl, file.name)
    }

    // ── Translate / submit ───────────────────────────────────────────────────
    suspend fun submitTranslate(
        mediaSeq: Long,
        isVideoProject: Boolean,
        sourceLanguageCode: String,
        targetLanguageCodes: List<String>,
        numberOfSpeakers: Int,
        title: String? = null,
    ): Long {
        val response = httpClient.post(url("/video-translator/api/v1/projects/spaces/${config.spaceSeq}/translate")) {
            authHeader()
            contentType(ContentType.Application.Json)
            setBody(PersoTranslateRequest(
                mediaSeq = mediaSeq,
                isVideoProject = isVideoProject,
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCodes = targetLanguageCodes,
                numberOfSpeakers = numberOfSpeakers,
                title = title,
            ))
        }
        checkResponse(response)
        val env: PersoEnvelope<PersoTranslateResult> = response.body()
        val first = env.result.startGenerateProjectIdList.firstOrNull()
            ?: throw PersoApiException(500, "Perso returned empty startGenerateProjectIdList")
        log.info("Perso project submitted: projectSeq={}, mediaSeq={}", first, mediaSeq)
        return first
    }

    // ── Progress poll ────────────────────────────────────────────────────────
    suspend fun getProgress(projectSeq: Long): PersoProgressResult {
        val response = httpClient.get(url(
            "/video-translator/api/v1/projects/$projectSeq/space/${config.spaceSeq}/progress"
        )) { authHeader() }
        checkResponse(response)
        return response.body<PersoEnvelope<PersoProgressResult>>().result
    }

    // ── Download info / links ────────────────────────────────────────────────
    suspend fun getDownloadInfo(projectSeq: Long): PersoDownloadInfo {
        val response = httpClient.get(url(
            "/file/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download-info"
        )) { authHeader() }
        checkResponse(response)
        // download-info is NOT enveloped per the doc — parsed directly.
        return response.body()
    }

    suspend fun getDownloadLinks(projectSeq: Long, target: String): PersoDownloadLinksResult {
        val response = httpClient.get(url(
            "/file/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download"
        )) {
            authHeader()
            parameter("target", target)
        }
        checkResponse(response)
        return response.body<PersoEnvelope<PersoDownloadLinksResult>>().result
    }

    // Stream a pre-signed download URL to a local file (atomic: tmp → rename).
    suspend fun streamDownload(downloadUrl: String, targetFile: File) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            httpClient.prepareGet(downloadUrl).execute { response ->
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
                // renameTo returns false silently on cross-device, full disk,
                // or target-exists — clean up ourselves instead of leaking the
                // .tmp file on the happy-path exit.
                tempFile.delete()
                throw RuntimeException("Failed to rename download temp file to ${targetFile.absolutePath}")
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
    }
}
