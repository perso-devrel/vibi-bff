package com.vibi.bff.service

import com.vibi.bff.config.PersoConfig
import com.vibi.bff.model.*
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.PersoApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
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

    /**
     * Perso control-plane 호출의 transient 실패 retry 헬퍼.
     * 재시도 대상:
     *   - PersoApiException 5xx (Perso 가 종종 F5001 INTERNAL_SERVER_ERROR 던짐)
     *   - IOException (EOFException = "server prematurely closed the connection" 등 connection drop)
     *   - HttpRequestTimeoutException (request/socket timeout)
     * 4xx 는 즉시 throw — 인증/요청 오류는 재시도해도 안 풀림.
     *
     * 대용량 PUT/GET (uploadToBlob / streamDownloadAuthorized) 은 별도 정책 (이미 caller 측
     * `downloadFreshLinkWithRetry` 가 fresh-link backoff 으로 감쌈) — 본 helper 미사용.
     */
    private suspend fun <T> withTransientRetry(label: String, block: suspend () -> T): T {
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: PersoApiException) {
                if (e.statusCode in 400..499) throw e
                lastErr = e
                log.warn("{} {} (attempt {}/3) — retrying in 3s: {}",
                    label, e.statusCode, attempt + 1, e.message)
            } catch (e: HttpRequestTimeoutException) {
                lastErr = e
                log.warn("{} timeout (attempt {}/3) — retrying in 3s: {}",
                    label, attempt + 1, e.message)
            } catch (e: IOException) {
                lastErr = e
                log.warn("{} IO error (attempt {}/3) — retrying in 3s: {} ({})",
                    label, attempt + 1, e::class.simpleName, e.message)
            }
            kotlinx.coroutines.delay(3000)
        }
        throw lastErr ?: RuntimeException("$label failed after 3 attempts")
    }

    /**
     * BILLABLE submit(audio-separation) 전용 retry. [withTransientRetry] 와 달리 **Perso 5xx 만**
     * 재시도한다 — 5xx 는 Perso 가 요청을 받고 에러 응답한 것이라 과금 프로젝트가 생성되지 않아 retry 가
     * 안전(흔한 F5001 INTERNAL_SERVER_ERROR 회복). 반대로 timeout/IOException 은 **응답이 안 온 것**이라
     * Perso 가 이미 프로젝트를 만들었을 수 있고, 그 상태로 retry 하면 **두 번째 과금 프로젝트**가 생긴다
     * (Perso 는 Idempotency-Key/dedup API 없음). → timeout/IO 는 재시도하지 않고 즉시 throw 해서
     * 잡 실패 → onJobFailed 환불 → 사용자가 재시도(플러그인 재시도 버튼)하도록 한다.
     */
    private suspend fun <T> withBillableSubmitRetry(label: String, block: suspend () -> T): T {
        var lastErr: PersoApiException? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: PersoApiException) {
                if (e.statusCode in 400..499) throw e // 4xx 는 재시도 무의미
                lastErr = e
                log.warn("{} {} (attempt {}/3) — retrying in 3s: {}", label, e.statusCode, attempt + 1, e.message)
                kotlinx.coroutines.delay(3000)
            }
            // timeout(HttpRequestTimeoutException)/IOException 등은 catch 안 함 → 즉시 전파(이중과금 방지).
        }
        throw lastErr ?: RuntimeException("$label failed after 3 attempts")
    }

    // ── Step 1: Get SAS token ────────────────────────────────────────────────
    suspend fun getSasToken(fileName: String): PersoSasTokenResponse {
        val encoded = URLEncoder.encode(fileName, Charsets.UTF_8)
        return withTransientRetry("getSasToken($fileName)") {
            val response = httpClient.get(url("/file/api/upload/sas-token?fileName=$encoded")) {
                authHeader()
            }
            checkResponse(response)
            response.body()
        }
    }

    // ── Step 2: Upload raw bytes to Azure Blob via SAS URL ───────────────────
    // The SAS URL is pre-signed — must NOT send XP-API-KEY here, and must set
    // x-ms-blob-type so Azure accepts it as a block blob upload. Streamed via
    // WriteChannelContent so 500 MB uploads don't pin the full file in heap.
    //
    // 이전엔 `ByteReadChannel(FileInputStream(file).asSource().buffered())` 를 감싼
    // ReadChannelContent 패턴을 썼는데 kotlinx-io Source → ByteReadChannel 변환이
    // contentLength 만큼 바이트가 채워지기 전 source.exhausted()=true 를 시그널하는
    // 회귀가 관측됨 → 엔진이 readByte 에서 `EOFException: Not enough data available`.
    // 직접 InputStream 을 read 해 ByteWriteChannel 에 writeFully 하면 중간 변환을
    // 우회한다.
    //
    // withTransientRetry 래핑 — Azure / 네트워크 일시 5xx, IOException (EOFException
    // 포함), timeout 을 3회 backoff 재시도. writeTo 안에서 매 호출마다 fresh
    // FileInputStream 을 열어 재시도 시 head 부터 다시 스트림.
    suspend fun uploadToBlob(sasUrl: String, file: File) {
        withTransientRetry("uploadToBlob(${file.name})") {
            val response = httpClient.put(sasUrl) {
                header("x-ms-blob-type", "BlockBlob")
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long = file.length()
                    override val contentType: ContentType = ContentType.Application.OctetStream
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                channel.writeFully(buffer, 0, read)
                            }
                        }
                    }
                })
            }
            checkResponse(response)
        }
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
        return withTransientRetry("registerMedia($fileName)") {
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
            response.body()
        }
    }

    // Convenience: SAS + blob upload + register in one call.
    //
    // 디버깅: 단계별 시작/끝 로그를 박아두면 실패 시 어느 단계에서 죽었는지 즉시 분간된다.
    // (실패는 보통 submitAudioSeparation 이전 어딘가에서 발생하는데, 성공 로그는 submit
    // 직후에만 찍혀 사이가 깜깜했음.)
    suspend fun uploadMedia(mediaType: PersoMediaType, file: File): PersoMediaRegistration {
        log.info("Uploading to Perso: {} ({} bytes, type={})", file.name, file.length(), mediaType)
        val sas = getSasToken(file.name)
        log.info("Perso SAS issued: file={} expires={}", file.name, sas.expirationDatetime)
        uploadToBlob(sas.blobSasUrl, file)
        log.info("Perso blob upload OK: file={} ({}B)", file.name, file.length())
        val reg = registerMedia(mediaType, sas.blobSasUrl, file.name)
        log.info("Perso registerMedia OK: file={} seq={}", file.name, reg.seq)
        return reg
    }

    // ── Audio Separation (전용) ─────────────────────────────────────────────
    // 음성분리 전용. 응답은 화자별 utterance 단위 audioUrl 의 paginated stream.
    suspend fun submitAudioSeparation(
        mediaSeq: Long,
        isVideoProject: Boolean,
        title: String? = null,
    ): Long {
        log.info("Perso submitAudioSeparation: mediaSeq={} isVideoProject={} title={}",
            mediaSeq, isVideoProject, title)
        // BILLABLE submit — withBillableSubmitRetry 로 Perso 5xx 만 재시도(에러 응답=프로젝트 미생성→
        // 재시도 안전, 흔한 F5001 회복). timeout/IOException(응답 없음=성공했을 수도)은 재시도 안 함 →
        // 이중 프로젝트=이중 Perso 과금 방지. 실패는 잡 실패 → 환불 → 사용자 재시도(플러그인 재시도 버튼).
        return withBillableSubmitRetry("submitAudioSeparation(mediaSeq=$mediaSeq)") {
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
            first
        }
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
    /**
     * SSRF 방지 — Perso 응답이나 외부 입력으로 들어오는 download URL/path 를 정제.
     *
     * - protocol-relative (`//host/path`) 또는 `\\host\path` 류는 즉시 reject (Perso 가
     *   안 쓰는 형태인데도 host concat 으로 넘어가면 attacker host 가 될 수 있음).
     * - absolute http(s) URL → host 가 [PersoConfig.allDownloadAllowedHosts] 안에 있는지 검증.
     *   localhost / 사설 IP 등으로 우회 시도 차단.
     * - relative path 는 baseUrl 에 [URI.resolve] 후 동일 host 검증.
     */
    private fun resolveAndValidateDownloadUrl(downloadUrl: String): String {
        require(downloadUrl.isNotBlank()) { "downloadUrl must not be blank" }
        // Reject protocol-relative + backslash 트릭 (`//evil.com/x`, `\\evil.com\x`).
        require(!downloadUrl.startsWith("//")) { "protocol-relative downloadUrl rejected: $downloadUrl" }
        require(!downloadUrl.startsWith("\\")) { "backslash-prefixed downloadUrl rejected: $downloadUrl" }

        val absUrl = when {
            downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://") -> downloadUrl
            downloadUrl.startsWith("/perso-storage/") -> "${config.storageBaseUrl}$downloadUrl"
            downloadUrl.startsWith("/") -> URI.create(config.baseUrl).resolve(downloadUrl).toString()
            else -> url(downloadUrl)
        }

        val parsedHost = runCatching { URI.create(absUrl).host?.lowercase() }.getOrNull()
            ?: throw IllegalArgumentException("downloadUrl has no resolvable host: $downloadUrl")
        require(parsedHost in config.allDownloadAllowedHosts) {
            "downloadUrl host '$parsedHost' not in allowed list: $downloadUrl"
        }
        return absUrl
    }

    suspend fun streamDownloadAuthorized(downloadUrl: String, targetFile: File) {
        // SSRF 방지: absolute URL 은 host 화이트리스트 검증, relative path 는 baseUrl 에 resolve
        // 후 host 재검증. protocol-relative (`//evil.com/x`) 형태 reject.
        val absUrl = resolveAndValidateDownloadUrl(downloadUrl)
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

    // ── Progress poll ────────────────────────────────────────────────────────
    suspend fun getProgress(projectSeq: Long): PersoProgressResult {
        return withTransientRetry("getProgress($projectSeq)") {
            val response = httpClient.get(url(
                "/video-translator/api/v1/projects/$projectSeq/space/${config.spaceSeq}/progress"
            )) { authHeader() }
            checkResponse(response)
            response.body<PersoEnvelope<PersoProgressResult>>().result
        }
    }

    /**
     * Audio-separation 프로젝트 전용 download links. target 별 valid 한 응답 필드:
     *   - target=originalVoiceSpeakers → audioFile.voiceAudioDownloadLink (.tar)
     *   - target=originalSubBackground → audioFile.originalSubBackgroundDownloadLink (.wav)
     */
    suspend fun getSeparationDownloadLinks(projectSeq: Long, target: String): PersoSeparationDownloadLinks {
        return withTransientRetry("getSeparationDownloadLinks($projectSeq,$target)") {
            val response = httpClient.get(url(
                "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/download"
            )) {
                authHeader()
                parameter("target", target)
            }
            checkResponse(response)
            response.body<PersoEnvelope<PersoSeparationDownloadLinks>>().result
        }
    }

    /**
     * project meta + downloadPathInfo (storage path 직접 노출).
     *
     * Perso 의 다른 endpoint 들은 `{"result":{...}}` envelope 으로 오지만 이 endpoint 는
     * envelope 없이 raw object 로 응답하는 케이스가 관측됨 (Perso 측 inconsistency).
     * 두 형태 모두 받도록 JsonElement 로 먼저 받아서 envelope 이면 벗기고 아니면 그대로 파싱.
     */
    suspend fun getProjectInfo(projectSeq: Long): PersoProjectInfo {
        return withTransientRetry("getProjectInfo($projectSeq)") {
            val response = httpClient.get(url(
                "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}"
            )) { authHeader() }
            checkResponse(response)
            val element = response.body<kotlinx.serialization.json.JsonElement>()
            val obj = element as? kotlinx.serialization.json.JsonObject
                ?: throw PersoApiException(500, "getProjectInfo: expected JSON object, got $element")
            val target = obj["result"] ?: obj
            AppJson.decodeFromJsonElement(PersoProjectInfo.serializer(), target)
        }
    }

    // ── Audio-separation diarized script ─────────────────────────────────────
    /**
     * 분리 프로젝트의 diarized script 전체를 cursor 페이지네이션으로 모아 반환. 새 STT 잡 없이
     * 분리가 이미 만든 script 를 읽는다. plugin server/ 의 getFullAudioSeparationScript 포팅.
     * guard: 무한 cursor 루프 방지(최대 100 page).
     */
    suspend fun getFullAudioSeparationScript(projectSeq: Long): PersoScriptPage {
        val sentences = mutableListOf<PersoScriptSentence>()
        var speakers: List<PersoScriptSpeaker> = emptyList()
        var cursorId: Long? = null
        repeat(100) {
            val page = getAudioSeparationScriptPage(projectSeq, cursorId)
            sentences += page.sentences
            if (page.speakers.isNotEmpty()) speakers = page.speakers
            if (!page.hasNext || page.nextCursorId == null) {
                return PersoScriptPage(hasNext = false, nextCursorId = null, sentences = sentences, speakers = speakers)
            }
            cursorId = page.nextCursorId
        }
        return PersoScriptPage(hasNext = false, nextCursorId = null, sentences = sentences, speakers = speakers)
    }

    private suspend fun getAudioSeparationScriptPage(projectSeq: Long, cursorId: Long?): PersoScriptPage {
        return withTransientRetry("getAudioSeparationScript($projectSeq,${cursorId ?: "start"})") {
            val response = httpClient.get(url(
                "/video-translator/api/v1/projects/$projectSeq/spaces/${config.spaceSeq}/audio-separation/script"
            )) {
                authHeader()
                if (cursorId != null) parameter("cursorId", cursorId)
            }
            checkResponse(response)
            // Perso 의 audio-separation/script 는 getProjectInfo 처럼 envelope({"result":...}) 없이
            // raw object 로 응답하는 케이스가 있다 → 둘 다 수용(result 있으면 벗기고 없으면 그대로).
            val element = response.body<kotlinx.serialization.json.JsonElement>()
            val obj = element as? kotlinx.serialization.json.JsonObject
                ?: throw PersoApiException(500, "audio-separation/script: expected JSON object, got $element")
            AppJson.decodeFromJsonElement(PersoScriptPage.serializer(), obj["result"] ?: obj)
        }
    }

}
