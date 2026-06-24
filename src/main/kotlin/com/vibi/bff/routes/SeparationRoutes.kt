package com.vibi.bff.routes

import com.vibi.bff.MAX_SEPARATION_AUDIO_SIZE
import com.vibi.bff.config.AppConfig
import com.vibi.bff.model.*
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.RL_SEPARATE
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.plugins.requireUserActiveIfPossible
import com.vibi.bff.service.UserRepository
import io.ktor.server.plugins.ratelimit.rateLimit
import com.vibi.bff.service.CreditCost
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.FfmpegRunner
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.InsufficientCreditsException
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.StemMeta
import kotlinx.serialization.builtins.ListSerializer
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.routes.SeparationRoutes")

/** Perso audio-separation 이 받는 모든 audio 포맷 — m4a (AAC), mp3, wav (PCM). 모바일이 trim
 * + audio extract 까지 끝낸 m4a 가 신규 경로의 default. mp3/wav 는 사용자가 직접 갖고 있는
 * audio 파일 (녹음, 외부 다운로드 등) 을 재인코딩 없이 그대로 보내는 케이스. video / flac /
 * ogg 등은 모두 reject — flac 은 Perso 가 silent fail 하는 회귀 (CLAUDE.md 참조). */
private val SEPARATION_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav")

/** 단일 분리 잡의 측정 길이 상한(분). Perso 는 분당 과금 + WAV stem egress 도 분 비례 → 무제한 길이는
 *  무제한 비용. 서버 측정 길이(computeSeparationSourceDurationMs)에 적용. env MAX_SEPARATION_MINUTES 조정. */
private val MAX_SEPARATION_MINUTES = (System.getenv("MAX_SEPARATION_MINUTES")?.toIntOrNull() ?: 60).coerceAtLeast(1)

fun Route.separationRoutes(
    separationService: SeparationService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
    objectStore: ObjectStore?,
    /** 큐 상태 조회용 — null 이면 queuePosition 응답 안 함 (DB-less dev / 테스트). */
    queueRepository: SeparationQueueRepository? = null,
    /** JWT 검증용 — null 이면 인증 강제 안 함 (테스트 호환). 운영에선 항상 주입. */
    jwtSecret: String? = null,
    /** 크레딧 선차감 / 환불용. null 이면 차감 skip (테스트 / dev). 운영에선 항상 주입. */
    creditRepository: CreditRepository? = null,
    /** 삭제된 계정의 잔존 JWT 차단용 존재 확인. null 이면 skip (테스트/dev). 운영에선 항상 주입. */
    userRepository: UserRepository? = null,
    /** Adobe history script 조회용 Perso 클라이언트. null 이면 script 라우트 비활성(테스트/dev). */
    persoClient: PersoClient? = null,
) {
    route("/separate") {
        // submit(POST)만 레이트리밋 — 크레딧이 1차 방어, 보조 상한. 상태 조회/stem GET 은 제외.
        rateLimit(RL_SEPARATE) {
        // POST /api/v2/separate — submit job
        // 모바일이 trim + audio extract 까지 끝낸 audio (m4a/mp3/wav) 만 받는다.
        // BFF 는 file 을 그대로 Perso 에 forward — ffmpeg 단계 없음.
        post {
            val principal = call.requireUserActiveIfPossible(jwtSecret, userRepository)
            val (filePart, specOpt) = parseOptionalUploadAndSpec<SeparationSpec>(
                call.receiveMultipart(formFieldLimit = MAX_SEPARATION_AUDIO_SIZE),
                fileStorage,
                MAX_SEPARATION_AUDIO_SIZE,
            )
            val spec = specOpt ?: run {
                filePart?.delete()
                throw IllegalArgumentException("spec is required")
            }
            val sourceFile = filePart ?: throw IllegalArgumentException("file is required")

            // 화이트리스트 검증 2-단:
            //   1) 확장자 (caller-controlled — 1차 차단 + UX-friendly error)
            //   2) ffprobe stream-kind (실 byte content — 확장자 위조 우회 차단)
            // m4a 는 mp4 container 라 video track 동봉도 가능 → 확장자만으론 부족하다. probe 실패 /
            // video stream 동봉 / audio stream 부재면 즉시 reject.
            val ext = sourceFile.extension.lowercase()
            if (ext !in SEPARATION_AUDIO_EXTENSIONS) {
                sourceFile.delete()
                throw ApiErrorException(
                    HttpStatusCode.BadRequest,
                    "unsupported_audio_format",
                    "audio extension must be one of $SEPARATION_AUDIO_EXTENSIONS (got '$ext')",
                )
            }
            val streamKinds = MediaTrimmer.probeStreamKinds(sourceFile)
            if (streamKinds == null || "video" in streamKinds || "audio" !in streamKinds) {
                sourceFile.delete()
                throw ApiErrorException(
                    HttpStatusCode.BadRequest,
                    "unsupported_audio_format",
                    "file must contain exactly an audio track (probed streams=$streamKinds)",
                )
            }

            // submit / 크레딧 reserve 어느 단계든 throw 시 caller-owned source 파일이
            // 디스크에 남는 것을 막기 위해 try-catch. submit 성공 후엔 service 가 owner —
            // [sourceOwned]=false 로 flip 해서 catch 의 sourceFile.delete() 가 service 의
            // upload 와 race 하는 것 차단. call.respond 가 client disconnect 로 throw 해도
            // 잡은 비동기 계속 — 라우트가 file 을 지우면 안 됨.
            //
            // 크레딧 선차감 — caller=null (인증 안 됨, 테스트/dev) 또는 creditRepository=null
            // (dev) 면 skip. SeparationService 의 FAILED 환불 hook 은 별개 (잡이 생성된 경로용).
            var reservedJobId: String? = null
            var sourceOwned = true
            try {
                val sourceDurationMs = computeSeparationSourceDurationMs(sourceFile)
                // 단일 잡 측정 길이 상한 — 한 번의 submit 으로 임의 장시간 paid 잡(Perso 비용 + egress)이
                // 도는 것 차단. 서버 측정 길이 기준(클라 durationMs hint 는 과금/캡에 안 씀). 초과 시 422.
                if (sourceDurationMs > MAX_SEPARATION_MINUTES * 60_000L) {
                    throw ApiErrorException(
                        HttpStatusCode.UnprocessableEntity,
                        "audio_too_long",
                        "최대 ${MAX_SEPARATION_MINUTES}분까지 분리 가능합니다.",
                    )
                }

                // 라우트가 jobId 를 미리 생성해 차감 ledger 의 키로 사용.
                val newJobId = "sep-${UUID.randomUUID()}"
                val cost = CreditCost.forSeparation(sourceDurationMs)
                if (principal != null && creditRepository != null) {
                    withContext(Dispatchers.IO) {
                        creditRepository.reserve(principal.userId, newJobId, cost)
                    }
                    reservedJobId = newJobId
                }

                val resultJobId = separationService.submit(
                    sourceFile = sourceFile,
                    spec = spec,
                    userId = principal?.userId,
                    sourceDurationMs = sourceDurationMs,
                    providedJobId = newJobId,
                )
                // submit 성공 후엔 잡 lifecycle 의 owner 가 SeparationService — 실패 시 환불은
                // onJobFailed hook 이 담당. 파일 ownership 도 transfer — 라우트 catch 는 더 이상
                // sourceFile 을 건드리면 안 됨 (call.respond throw 시 service 가 upload 중일 수 있음).
                reservedJobId = null
                sourceOwned = false
                call.respond(HttpStatusCode.Accepted, SeparationResponse(jobId = resultJobId))
            } catch (e: InsufficientCreditsException) {
                if (sourceOwned) runCatching { sourceFile.delete() }
                throw ApiErrorException(
                    HttpStatusCode.PaymentRequired,
                    "insufficient_credits",
                    "required=${e.required} balance=${e.balance}",
                )
            } catch (e: Throwable) {
                if (sourceOwned) runCatching { sourceFile.delete() }
                // submit 진입 전 단계가 throw 했을 때만 환불. reservedJobId 는 submit 성공
                // 직후 null 로 비워지므로 본 블록에 non-null 이면 잡이 생성되지 않은 경로.
                if (reservedJobId != null && creditRepository != null) {
                    val toRefund = reservedJobId!!
                    withContext(Dispatchers.IO) {
                        runCatching { creditRepository.refund(toRefund) }
                            .onFailure { ex ->
                                log.warn("error-path refund failed jobId={}: {}", toRefund, ex.message)
                            }
                    }
                }
                throw e
            }
        }

        } // rateLimit(RL_SEPARATE)

        // GET /api/v2/separate/{jobId} — status + stem URLs (signed)
        get("/{jobId}") {
            val principal = jwtSecret?.let { call.requireUser(it) }
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")

            // owner 가 set 된 잡은 caller 도 반드시 매칭 — caller null 도 reject. mismatch 는
            // not-found 로 응답해 IDOR existence oracle 차단. owner null 인 잡만 caller 무관 통과.
            if (job.ownerUserId != null && job.ownerUserId != principal?.userId) {
                throw NotFoundException("Separation job not found: $jobId")
            }

            val ttl = appConfig.separation.urlTtlSec
            val stems = if (job.status == "READY") {
                job.stems.map { s ->
                    val token = signer.sign(jobId, s.stemId, ttl)
                    StemInfo(
                        stemId = s.stemId,
                        label = s.label,
                        url = "/api/v2/separate/$jobId/stem/${s.stemId}?token=$token",
                    )
                }
            } else emptyList()

            // QUEUED 단계에서만 큐 위치 + 예상 대기 노출. SUBMITTING/PROCESSING/READY 면 null.
            // queueRepository 가 null (테스트 분기) 거나 in-memory status 가 QUEUED 가 아니면 skip.
            val (queuePosition, estimatedWaitSec) = if (queueRepository != null && job.status == "QUEUED") {
                val pos = queueRepository.queuePosition(jobId)
                val avgSec = queueRepository.rollingAvgProcessingSec() ?: DEFAULT_ESTIMATED_PROCESSING_SEC
                pos to pos?.let { it * avgSec }
            } else null to null

            call.respond(HttpStatusCode.OK, SeparationStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                stems = stems,
                actualDurationMs = job.actualDurationMs,
                queuePosition = queuePosition,
                estimatedWaitSec = estimatedWaitSec,
            ))
        }

        // GET /api/v2/separate/{jobId}/stem/{stemId} — 다운로드. 인증 두 경로:
        //  - ?token=<HMAC> : capability 토큰(모바일 status 응답의 서명 URL). → 검증 후 302 redirect.
        //  - 토큰 없음      : Bearer JWT(identity)로 인증 + owner 매칭. UXP 패널이 헤더만 보내는 경로.
        //                     UXP fetch 는 302 를 못 따라가므로 { url } JSON 으로 응답(클라가 분기 보유).
        get("/{jobId}/stem/{stemId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val stemId = call.parameters["stemId"]
                ?: throw NotFoundException("stemId required")
            val token = call.request.queryParameters["token"]

            // 토큰이 있으면 capability 검증. 없으면 Bearer 필수(테스트 분기 jwtSecret==null 제외).
            if (token != null && !signer.verify(jobId, stemId, token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            // capability(토큰) + identity(Bearer) 이중 인증을 한 곳으로 통합:
            //  - 토큰 경로: 헤더가 있고 valid 면 principal 확보(owner 매칭 강제), 만료 JWT 면 null →
            //    capability 단독 통과(모바일이 토큰 만료 중 재생해도 streaming 안 깨지게).
            //  - 토큰 없음: requireUser 가 401(헤더 부재/invalid) → owner 매칭 강제.
            //  - jwtSecret 미주입(테스트): 양쪽 모두 null → 인증 skip.
            val principal = when {
                jwtSecret == null -> null
                token == null -> call.requireUser(jwtSecret) // 401 if missing/invalid
                call.request.header(HttpHeaders.Authorization) != null ->
                    runCatching { call.requireUser(jwtSecret) }.getOrNull()
                else -> null
            }

            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")

            // owner 가 set 된 잡은 인증된 caller 와 매칭. mismatch 는 not-found(IDOR existence oracle 차단).
            if (principal != null && job.ownerUserId != null && job.ownerUserId != principal.userId) {
                throw NotFoundException("Separation job not found: $jobId")
            }

            val stem = job.stems.firstOrNull { it.stemId == stemId }
                ?: throw NotFoundException("Stem not found: $stemId")
            // 로컬 file 존재 체크 의도적 제거 — R2 가 source-of-truth. DB fallback 으로 재구축된
            // SeparationJob 은 placeholder File 을 들고 있고 실체는 R2. ObjectStore.uploadIfAbsent
            // 가 HEAD 로 R2 hit 확인 후 signed URL. R2 도 없으면 그 안에서 throw.
            val ext = stem.file.extension.ifBlank { "flac" }
            if (token == null) {
                // 플러그인(UXP) 경로: pure-JS mix/재생이 WAV PCM 만 처리하므로 stem(FLAC)을 WAV 로 제공.
                // R2 가 있으면 transcode 한 WAV 를 캐시(첫 1회만 변환→업로드, 이후 presigned 로 즉시) —
                // 매 다운로드 transcode 로 history 복원이 느려지는 것 방지. R2 없으면(dev) 변환 후 inline.
                val store = objectStore
                if (store != null) {
                    val wavKey = ObjectKey.separationStem(jobId, stem.stemId, "wav")
                    if (!withContext(Dispatchers.IO) { store.objectExists(wavKey) }) {
                        val srcKey = ObjectKey.separationStem(jobId, stem.stemId, ext)
                        val srcIsTemp = !stem.file.exists()
                        val srcFile: File = if (!srcIsTemp) {
                            stem.file
                        } else {
                            withContext(Dispatchers.IO) { store.downloadIfAbsent(srcKey, File.createTempFile("stem-src-", ".$ext")) }
                        }
                        val wav = File.createTempFile("stem-wav-", ".wav")
                        try {
                            FfmpegRunner.run(
                                listOf(
                                    "ffmpeg", "-y", "-v", "error", "-i", srcFile.absolutePath,
                                    "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", wav.absolutePath,
                                ),
                                label = "stem flac->wav ${stem.stemId}",
                                timeoutMinutes = 2,
                            )
                            withContext(Dispatchers.IO) { store.uploadIfAbsent(wav, wavKey, "audio/wav") }
                        } finally {
                            withContext(Dispatchers.IO) {
                                wav.delete()
                                if (srcIsTemp) srcFile.delete()
                            }
                        }
                    }
                    val url = withContext(Dispatchers.IO) {
                        store.signedUrl(objectKey = wavKey, downloadFilename = "${stem.stemId}.wav", contentType = "audio/wav")
                    }
                    call.respond(HttpStatusCode.OK, SignedDownloadUrl(url))
                    return@get
                }
                // R2 미사용(dev): 변환 후 inline stream.
                val wav = File.createTempFile("stem-wav-", ".wav")
                try {
                    FfmpegRunner.run(
                        listOf(
                            "ffmpeg", "-y", "-v", "error", "-i", stem.file.absolutePath,
                            "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", wav.absolutePath,
                        ),
                        label = "stem flac->wav ${stem.stemId}",
                        timeoutMinutes = 2,
                    )
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName, "${stem.stemId}.wav",
                        ).toString(),
                    )
                    call.response.header(HttpHeaders.ContentType, ContentType("audio", "wav").toString())
                    call.respondFile(wav)
                } finally {
                    wav.delete()
                }
                return@get
            }
            // 모바일(토큰) 경로: FLAC 그대로 (R2 presigned 302).
            call.respondDownload(
                file = stem.file,
                objectKey = ObjectKey.separationStem(jobId, stem.stemId, ext),
                contentType = contentTypeForExtension(ext, ContentType("audio", "wav")),
                downloadFilename = "${stem.stemId}.$ext",
                store = objectStore,
                asJsonUrl = false,
            )
        }

        // DELETE /api/v2/separate/{jobId} — 저장된 분리 삭제(행 + R2 stem purge). 멱등 204.
        // owner 매칭으로만 삭제 — 남의 잡/이미 없는 잡은 no-op 후에도 204(존재 비노출).
        delete("/{jobId}") {
            val jobId = call.parameters["jobId"] ?: throw NotFoundException("jobId required")
            val principal = jwtSecret?.let { call.requireUser(it) }
            if (principal == null || queueRepository == null) {
                call.respond(HttpStatusCode.NoContent)
                return@delete
            }
            val deleted = queueRepository.deleteOwnedReturning(jobId, principal.userId)
            if (deleted != null) {
                // 삭제된 row 의 stems 만 R2 에서 purge. ObjectStore 없으면(dev) skip.
                val stemsJson = deleted.stemsJson
                if (stemsJson != null && objectStore != null) {
                    val metas = runCatching {
                        AppJson.decodeFromString(ListSerializer(StemMeta.serializer()), stemsJson)
                    }.getOrDefault(emptyList())
                    // 독립 key 라 병렬 삭제 — 순차면 stem 수 × (ext + wav) 만큼 R2 왕복이 직렬로
                    // 쌓여 DELETE 응답이 느려진다. deleteObject 는 에러 swallow 라 awaitAll throw 없음.
                    withContext(Dispatchers.IO) {
                        metas.map { m ->
                            async {
                                val ext = m.ext.ifBlank { "flac" }
                                objectStore.deleteObject(ObjectKey.separationStem(jobId, m.stemId, ext))
                                // 플러그인 경로가 lazy 로 만든 WAV transcode 캐시(StemMeta 에 없어 ext 로 안 잡힘)도 purge — orphan 방지.
                                if (ext != "wav") objectStore.deleteObject(ObjectKey.separationStem(jobId, m.stemId, "wav"))
                            }
                        }.awaitAll()
                    }
                }
                // 대기 중(QUEUED) 잡을 지운 경우: 대기 큐에서 빠진 김에 선차감 크레딧 환불 +
                // in-memory 정리 + dispatcher 깨우기로 다음 QUEUED 가 빈 자리를 즉시 채우게 한다.
                // 진행 중/완료 잡은 해당 없음(기존 동작 유지).
                if (deleted.status == SeparationQueueRepository.STATUS_QUEUED) {
                    separationService.onQueuedDeleted(jobId)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // GET /api/v2/separate/{jobId}/script — 분리가 이미 만든 diarized script(새 STT 잡 없음).
        // 분리의 Perso projectSeq 에서 바로 읽는다.
        get("/{jobId}/script") {
            val jobId = call.parameters["jobId"] ?: throw NotFoundException("jobId required")
            val principal = jwtSecret?.let { call.requireUser(it) }
            val row = queueRepository?.loadForScript(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")
            // owner 매칭(미스매치는 not-found, IDOR existence oracle 차단).
            if (principal != null && row.ownerUserId != null && row.ownerUserId != principal.userId) {
                throw NotFoundException("Separation job not found: $jobId")
            }
            if (row.status != SeparationQueueRepository.STATUS_READY || row.persoProjectSeq == null) {
                throw ApiErrorException(HttpStatusCode.Conflict, "not_ready")
            }
            val pc = persoClient
                ?: throw ApiErrorException(HttpStatusCode.ServiceUnavailable, "script_unavailable")
            val page = try {
                pc.getFullAudioSeparationScript(row.persoProjectSeq)
            } catch (e: Exception) {
                log.warn("script fetch failed jobId={}: {}", jobId, e.message)
                throw ApiErrorException(HttpStatusCode.BadGateway, "script_failed")
            }
            call.respond(HttpStatusCode.OK, assembleScriptDraft(page))
        }
    }

    // GET /api/v2/separations — owner+project 의 저장된 분리 목록(최신순). 패널 오픈/로그인 시
    // 결과 카드 복원용(서버가 cross-device source-of-truth). projectId 생략 → NULL(no-project) 버킷.
    get("/separations") {
        val principal = jwtSecret?.let { call.requireUser(it) }
        val projectId = call.request.queryParameters["projectId"]?.trim()?.takeIf { it.isNotEmpty() }
        if (principal == null || queueRepository == null) {
            call.respond(HttpStatusCode.OK, SeparationHistoryResponse(emptyList()))
            return@get
        }
        val items = queueRepository.listReadyHistory(principal.userId, projectId).map { r ->
            val stems = r.stemsJson?.let { json ->
                runCatching { AppJson.decodeFromString(ListSerializer(StemMeta.serializer()), json) }
                    .getOrDefault(emptyList())
            }?.map { HistoryStem(it.stemId, it.label) } ?: emptyList()
            SeparationHistoryItem(
                jobId = r.jobId,
                fileName = r.fileName,
                byteLength = r.byteLength,
                durationMs = r.durationMs,
                createdAt = r.createdAtMs,
                hasScript = r.hasScript,
                stems = stems,
            )
        }
        call.respond(HttpStatusCode.OK, SeparationHistoryResponse(items))
    }
}

/**
 * Perso script page → 클라이언트 ScriptDraft 매핑. plugin server/ 의 assembleDraft 포팅:
 * 화자 인덱스를 모아 "Speaker N" 라벨링, 문장을 segment(offset~offset+duration)로 변환.
 */
private fun assembleScriptDraft(page: PersoScriptPage): ScriptDraftResponse {
    val indices = sortedSetOf<Int>()
    page.speakers.forEach { indices.add(it.speakerOrderIndex) }
    page.sentences.forEach { indices.add(it.speakerOrderIndex) }
    if (indices.isEmpty()) indices.add(1)
    val speakers = indices.map { ScriptSpeaker(index = it, label = "Speaker $it") }
    val segments = page.sentences.mapIndexed { idx, s ->
        ScriptSegment(
            id = "seg-${if (s.seq != 0L) s.seq else (idx + 1).toLong()}",
            speakerIndex = s.speakerOrderIndex,
            text = s.originalText,
            startMs = s.offsetMs,
            endMs = s.offsetMs + s.durationMs,
        )
    }
    return ScriptDraftResponse(speakers = speakers, segments = segments)
}

/**
 * admin 대시보드의 "분리 사용량" KPI 원본 + 크레딧 차감 입력. 모바일이 이미 trim 한 audio 를
 * 보내므로 받은 파일 그대로 ffprobe.
 *
 * ffprobe 실패 시 conservative size-based fallback — `MIN_AUDIO_BITRATE_BYTES_PER_SEC` 로 나눠
 * 분 단위 추정. 이걸 안 하면 corrupt header 로 probe 만 막힌 60분 파일이 0ms 로 평가돼 1 credit
 * (floor) 만 차감되는 undercharge 우회가 가능 — Perso 처리 비용은 그대로 발생하므로 BFF 손실.
 *
 * AAC 64kbps 모노 (Perso 가 받는 최저 품질대) ≈ 8KB/s. 이보다 낮은 bitrate 는 일반 m4a/mp3/wav
 * 시나리오에 없으므로 fallback duration 이 실제보다 길게 추정되지는 않는다 (= overcharge 위험 없음,
 * undercharge 만 막음).
 */
private const val MIN_AUDIO_BITRATE_BYTES_PER_SEC = 8_000L

internal suspend fun computeSeparationSourceDurationMs(audioFile: File): Long {
    val probed = runCatching { MediaTrimmer.probeDurationMs(audioFile) }.getOrNull()
    if (probed != null && probed > 0L) return probed
    // probe 실패 / 0 → file size 기반 conservative 추정. delete 실패 / 0-byte 파일 등 edge
    // 케이스도 0L 로 떨어져 floor 1 credit 이 적용.
    val sizeBytes = runCatching { audioFile.length() }.getOrDefault(0L)
    return (sizeBytes / MIN_AUDIO_BITRATE_BYTES_PER_SEC * 1000L).coerceAtLeast(0L)
}

/**
 * rollingAvg 표본이 부족할 때 (boot 직후 / FAILED 만 누적된 상황) 추정 대기 계산용 fallback.
 * 분리 잡 평균 2-5분 → 보수적으로 3분(180초) — 사용자한테 너무 짧게 보여 불만 누적되는 것보다는
 * 약간 길게 보이고 일찍 완료되는 편이 UX 안전.
 */
private const val DEFAULT_ESTIMATED_PROCESSING_SEC: Long = 180
