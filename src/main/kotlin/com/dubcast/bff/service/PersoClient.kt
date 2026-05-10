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
    // Perso 가 일시적 5xx (특히 F5001 INTERNAL_SERVER_ERROR) 던질 때가 있어 짧은 backoff 재시도.
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
        var lastErr: PersoApiException? = null
        repeat(3) { attempt ->
            try {
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
            } catch (e: PersoApiException) {
                lastErr = e
                // 4xx (인증/요청 오류) 는 retry 의미 없음 — 즉시 throw.
                if (e.statusCode in 400..499) throw e
                log.warn("registerMedia {} (attempt {}/3) — retrying in 3s: {}", e.statusCode, attempt + 1, e.message)
                kotlinx.coroutines.delay(3000)
            }
        }
        throw lastErr ?: RuntimeException("Perso registerMedia failed after retries")
    }

    // Convenience: SAS + blob upload + register in one call.
    suspend fun uploadMedia(mediaType: PersoMediaType, file: File): PersoMediaRegistration {
        log.info("Uploading to Perso: {} ({} bytes)", file.name, file.length())
        val sas = getSasToken(file.name)
        uploadToBlob(sas.blobSasUrl, file)
        return registerMedia(mediaType, sas.blobSasUrl, file.name)
    }

    // ── STT (전용) ───────────────────────────────────────────────────────────
    // 자막 생성 전용. `submitTranslate` source==target 트릭 폐기 — Perso 가 정식 STT API 제공.
    suspend fun submitStt(
        mediaSeq: Long,
        isVideoProject: Boolean,
        title: String? = null,
    ): Long {
        val response = httpClient.post(url("/video-translator/api/v1/projects/spaces/${config.spaceSeq}/stt")) {
            authHeader()
            contentType(ContentType.Application.Json)
            setBody(PersoSttRequest(mediaSeq, isVideoProject, title))
        }
        checkResponse(response)
        val env: PersoEnvelope<PersoTranslateResult> = response.body()
        val first = env.result.startGenerateProjectIdList.firstOrNull()
            ?: throw PersoApiException(500, "Perso STT returned empty startGenerateProjectIdList")
        log.info("Perso STT project submitted: projectSeq={}, mediaSeq={}", first, mediaSeq)
        return first
    }

    suspend fun applySttChanges(projectSeq: Long) {
        val response = httpClient.post(url(
            "/video-translator/api/v1/project/$projectSeq/space/${config.spaceSeq}/stt/apply-changes"
        )) { authHeader() }
        checkResponse(response)
    }

    // STT script — paginated. `cursorId` null=처음부터, 응답의 `nextCursorId` 를 다음 호출에 전달.
    suspend fun getSttScript(projectSeq: Long, cursorId: Long? = null): PersoScriptResponse {
        val response = httpClient.get(url(
            "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/stt/script"
        )) {
            authHeader()
            if (cursorId != null) parameter("cursorId", cursorId)
        }
        checkResponse(response)
        return response.body()
    }

    // ── Audio Separation (전용) ─────────────────────────────────────────────
    // 음성분리 전용. 응답은 화자별 utterance 단위 audioUrl 의 paginated stream.
    suspend fun submitAudioSeparation(
        mediaSeq: Long,
        isVideoProject: Boolean,
        title: String? = null,
    ): Long {
        val response = httpClient.post(url("/video-translator/api/v1/projects/spaces/${config.spaceSeq}/audio-separation")) {
            authHeader()
            contentType(ContentType.Application.Json)
            setBody(PersoAudioSeparationRequest(mediaSeq, isVideoProject, title))
        }
        checkResponse(response)
        val env: PersoEnvelope<PersoTranslateResult> = response.body()
        val first = env.result.startGenerateProjectIdList.firstOrNull()
            ?: throw PersoApiException(500, "Perso audio-separation returned empty startGenerateProjectIdList")
        log.info("Perso audio-separation project submitted: projectSeq={}, mediaSeq={}", first, mediaSeq)
        return first
    }

    /**
     * Perso 응답에서 받은 다운로드 URL/path 를 로컬 파일로 스트림.
     *
     * Path 가 `/perso-storage/...` 형식이면 진짜 storage host (Azure Blob 기반 public CDN, 인증 X)
     * 로 보내야 한다 — `api.perso.ai` 에 똑같은 path 로 GET 하면 origin 이 file 모르고 404. 인증
     * 헤더는 storage CDN 에서 무시되거나 Azure SAS 서명 깨뜨릴 수 있어 안 붙임.
     *
     * 외부 SAS 서명 URL (host 포함 absolute URL) 도 같은 함수로 처리 — 인증 헤더 X.
     * `api.perso.ai` 의 다른 endpoint (드물게) 호출 시에만 인증 헤더가 필요한데 그건 별도 메서드 사용.
     */
    suspend fun streamDownloadAuthorized(downloadUrl: String, targetFile: File) {
        val absUrl = when {
            downloadUrl.startsWith("http") -> downloadUrl
            downloadUrl.startsWith("/perso-storage/") -> "${config.storageBaseUrl}$downloadUrl"
            else -> url(downloadUrl)
        }
        // storage host / absolute pre-signed URL 은 인증 헤더 X — 그 외 (api host 직접 호출) 만 헤더.
        val needsAuth = !downloadUrl.startsWith("http") && !downloadUrl.startsWith("/perso-storage/")
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            httpClient.prepareGet(absUrl) {
                if (needsAuth) authHeader()
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
                tempFile.delete()
                throw RuntimeException("Failed to rename download temp file to ${targetFile.absolutePath}")
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }
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

    /**
     * Audio-separation 프로젝트 전용 download links — translation 의 [getDownloadLinks] 와 응답
     * 모델 다름. target 별 valid 한 응답 필드:
     *   - target=originalVoiceSpeakers → audioFile.voiceAudioDownloadLink (.tar)
     *   - target=originalSubBackground → audioFile.originalSubBackgroundDownloadLink (.wav)
     */
    suspend fun getSeparationDownloadLinks(projectSeq: Long, target: String): PersoSeparationDownloadLinks {
        val response = httpClient.get(url(
            "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download"
        )) {
            authHeader()
            parameter("target", target)
        }
        checkResponse(response)
        return response.body<PersoEnvelope<PersoSeparationDownloadLinks>>().result
    }

    /** project meta + downloadPathInfo (storage path 직접 노출). */
    suspend fun getProjectInfo(projectSeq: Long): PersoProjectInfo {
        val response = httpClient.get(url(
            "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}"
        )) { authHeader() }
        checkResponse(response)
        return response.body<PersoEnvelope<PersoProjectInfo>>().result
    }

    // ── Download info / links ────────────────────────────────────────────────
    suspend fun getDownloadInfo(projectSeq: Long): PersoDownloadInfo {
        val targetPath = "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download-info"
        log.info("Perso getDownloadInfo: GET {}{}", config.baseUrl, targetPath)
        val response = httpClient.get(url(targetPath)) { authHeader() }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            log.error("Perso getDownloadInfo failed: status={} body={} url={}{}",
                response.status.value, body, config.baseUrl, targetPath)
            throw PersoApiException(response.status.value, body)
        }
        // Perso download-info 응답은 envelope `{"result":{...}}` — envelope 벗겨야 함.
        return response.body<PersoEnvelope<PersoDownloadInfo>>().result
    }

    suspend fun getDownloadLinks(projectSeq: Long, target: String): PersoDownloadLinksResult {
        // Perso download endpoint 일시 5xx 가 자주 발생 → registerMedia 와 동일 backoff retry. 4xx 즉시 throw.
        var lastErr: PersoApiException? = null
        repeat(3) { attempt ->
            val response = httpClient.get(url(
                "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download"
            )) {
                authHeader()
                parameter("target", target)
            }
            if (response.status.isSuccess()) {
                return response.body<PersoEnvelope<PersoDownloadLinksResult>>().result
            }
            val body = response.bodyAsText()
            val err = PersoApiException(response.status.value, body)
            lastErr = err
            if (err.statusCode in 400..499) throw err
            log.warn("getDownloadLinks {} (attempt {}/3) — retrying in 3s: target={} body={}",
                err.statusCode, attempt + 1, target, body)
            kotlinx.coroutines.delay(3000)
        }
        throw lastErr ?: RuntimeException("Perso getDownloadLinks failed after retries")
    }

    /**
     * 지원 언어 목록 — `GET /video-translator/api/v1/languages`.
     * 모바일 클라이언트의 타깃 언어 드롭다운을 동적으로 채우기 위함.
     */
    suspend fun getLanguages(): PersoLanguagesResponse {
        val response = httpClient.get(url("/video-translator/api/v1/languages")) {
            authHeader()
        }
        checkResponse(response)
        return response.body()
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
