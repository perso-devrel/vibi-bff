package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.config.AppConfig
import com.vibi.bff.model.*
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.StemMixService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.discard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

fun Route.separationRoutes(
    separationService: SeparationService,
    stemMixService: StemMixService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
    mediaSourceResolver: MediaSourceResolver,
    objectStore: ObjectStore?,
    /** JWT 검증용 — null 이면 인증 강제 안 함 (테스트 호환). 운영에선 항상 주입. */
    jwtSecret: String? = null,
) {
    route("/separate") {
        // POST /api/v2/separate — submit job
        // Accepts either multipart `file` (legacy) or `spec.editedRenderJobId`
        // referencing a completed /api/v2/render output. See MediaSourceResolver.
        post {
            val principal = jwtSecret?.let { call.requireUser(it) }
            val (filePart, specOpt) = parseOptionalUploadAndSpec<SeparationSpec>(
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE),
                fileStorage,
                MAX_UPLOAD_FILE_SIZE,
            )
            val spec = specOpt ?: run {
                filePart?.delete()
                throw IllegalArgumentException("spec is required")
            }

            // Pre-check: 같은 (caller+source+spec) 의 active 잡이 이미 있으면
            // resolve/trim 으로 디스크 낭비하지 말고 바로 기존 jobId 반환 — "이 구간
            // 음원분리" 버튼 연타 방어. dedup 키에 userId 를 포함해 cross-user
            // collision 으로 다른 사용자의 jobId 가 노출되는 IDOR 오라클 차단.
            // submit 의 atomic claim 이 진짜 safety net 이라 pre-check miss 해도
            // 정확성은 유지되지만 IO 절약 효과 큼.
            val dedupKey = buildSeparationDedupKey(spec, principal?.userId)
            if (dedupKey != null) {
                separationService.findActiveJob(dedupKey)?.let { existing ->
                    filePart?.delete()
                    call.respond(HttpStatusCode.Accepted, SeparationResponse(jobId = existing))
                    return@post
                }
            }

            // resolve() 는 항상 owned copy(또는 caller-owned upload)를 돌려준다 —
            // 따라서 maybeTrim 의 deleteOriginalOnFailure 분기가 필요 없다.
            // resolve / maybeTrim / submit 어느 단계든 throw 시 caller-owned source
            // 파일이 디스크에 남는 것을 막기 위해 try-catch. submit 성공 후엔 service
            // 가 owner.
            var source: File? = null
            var pipelineInput: File? = null
            var audioPreExtracted = false
            try {
                source = mediaSourceResolver.resolve(filePart, spec.editedRenderJobId, principal?.userId)
                val trimmed = maybeTrim(source, spec)
                pipelineInput = trimmed.file
                // maybeTrim 이 명시 Boolean 으로 반환 — reference equality (pipelineInput !== source)
                // 대신 캡슐화. 미래 리팩토링이 maybeTrim 의 no-trim 분기에서 같은 file 을
                // wrap 한 새 인스턴스를 반환해도 silent flip 안 됨.
                audioPreExtracted = trimmed.audioPreExtracted
                val sourceDurationMs = computeSeparationSourceDurationMs(pipelineInput, spec)
                val jobId = separationService.submit(
                    sourceFile = pipelineInput,
                    spec = spec,
                    audioPreExtracted = audioPreExtracted,
                    dedupKey = dedupKey,
                    userId = principal?.userId,
                    sourceDurationMs = sourceDurationMs,
                    renderJobId = spec.editedRenderJobId,
                )
                call.respond(HttpStatusCode.Accepted, SeparationResponse(jobId = jobId))
            } catch (e: Throwable) {
                runCatching { pipelineInput?.delete() }
                if (audioPreExtracted) runCatching { source?.delete() }
                throw e
            }
        }

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

            call.respond(HttpStatusCode.OK, SeparationStatusResponse(
                jobId = jobId,
                status = job.status,
                progress = job.progress,
                progressReason = job.progressReason,
                error = job.error,
                stems = stems,
                mixJobId = job.consumedByMixJobId,
                actualDurationMs = job.actualDurationMs,
            ))
        }

        // GET /api/v2/separate/{jobId}/stem/{stemId}?token=... — streamed audio
        get("/{jobId}/stem/{stemId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val stemId = call.parameters["stemId"]
                ?: throw NotFoundException("stemId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            if (!signer.verify(jobId, stemId, token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")

            // capability (HMAC 토큰) + identity (Authorization 헤더) 이중 검증.
            // 헤더가 있고 valid 면 owner 매칭 강제. 헤더가 invalid (만료 JWT) 면
            // capability 단독으로 통과 — 모바일 default-bearer-header 클라이언트가
            // 토큰 만료 중 stem 재생을 시도해도 streaming 깨지지 않도록.
            // jwtSecret 미주입 (테스트) 분기 / 헤더 부재는 token 단독 신뢰.
            if (jwtSecret != null && call.request.header(HttpHeaders.Authorization) != null) {
                val principal = runCatching { call.requireUser(jwtSecret) }.getOrNull()
                if (principal != null && job.ownerUserId != null && job.ownerUserId != principal.userId) {
                    throw NotFoundException("Separation job not found: $jobId")
                }
            }

            val stem = job.stems.firstOrNull { it.stemId == stemId }
                ?: throw NotFoundException("Stem not found: $stemId")
            if (!stem.file.exists()) {
                throw NotFoundException("Stem file missing on disk")
            }
            val ext = stem.file.extension.ifBlank { "wav" }
            call.respondDownload(
                file = stem.file,
                objectKey = ObjectKey.separationStem(jobId, stem.stemId, ext),
                contentType = contentTypeForExtension(ext, ContentType("audio", "wav")),
                downloadFilename = "${stem.stemId}.$ext",
                store = objectStore,
            )
        }

        // POST /api/v2/separate/{jobId}/mix — kick off mixing; on success, stems are disposed
        post("/{jobId}/mix") {
            val principal = jwtSecret?.let { call.requireUser(it) }
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")

            // mix 는 separation 잡의 stems 를 reserve → 성공 시 dispose. 다른 사용자가
            // 남의 잡을 mix command 로 소비/삭제시키는 IDOR 막기 위해 reserveForMix
            // 호출 전에 ownerUserId 검증. mismatch 는 not-found 로 응답.
            //
            // **body 파싱보다 먼저** 검증 — IDOR-rejected 요청이 비싼 JSON deserialize
            // 비용을 지불하지 않도록 (DoS 증폭 방지). missing-job (typo / dispose) 도
            // body 파싱 전에 409 로 차단해 미사용 JSON deserialize 도 막음.
            val existing = separationService.getJob(jobId)
            if (existing == null) {
                // 응답 전 incoming body drain — Ktor 3.x 가 자동 drain 안 함, client
                // 가 body 송신 중인데 server 가 먼저 respond + close 하면 TCP RST 로
                // client 가 broken-pipe IOException 봄 (409 body 못 읽음).
                runCatching { call.receiveChannel().discard() }
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(error = "Separation job not ready or already consumed"),
                )
            }
            if (existing.ownerUserId != null && existing.ownerUserId != principal?.userId) {
                runCatching { call.receiveChannel().discard() }
                throw NotFoundException("Separation job not found: $jobId")
            }

            val req = call.receive<StemMixRequest>()

            val mixJobId = stemMixService.newJobId()
            val job = separationService.reserveForMix(jobId, mixJobId)
                ?: return@post call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        error = "Separation job not ready or already consumed",
                    ),
                )

            val stemByIdLocal = job.stems.associateBy { it.stemId }
            val unknownStemId = req.stems.firstOrNull { it.stemId !in stemByIdLocal }?.stemId
            if (unknownStemId != null) {
                separationService.releaseReservation(jobId)
                throw IllegalArgumentException("Unknown stemId: $unknownStemId")
            }
            val selected = req.stems.map { sel ->
                sel to stemByIdLocal.getValue(sel.stemId).file
            }

            // mix 잡의 owner = caller principal. 운영에서는 owner 검증 위에서 통과한
            // principal 이 보장됨. principal null (jwtSecret 미주입 분기) 이면 owner null
            // 잡으로 등록 — 그래야 같은 caller 가 mix status 폴링 시 owner null 분기로
            // 통과 (이전엔 existing.ownerUserId 로 fallback 해 caller=null 인데 owner=set
            // 인 잡이 만들어져 자기 mix 잡에 접근 못하는 lockout 회귀 발생).
            stemMixService.submit(mixJobId, selected, ownerUserId = principal?.userId) { completed ->
                when (completed.status) {
                    "COMPLETED" -> separationService.dispose(jobId)
                    "FAILED" -> separationService.releaseReservation(jobId)
                    else -> { /* still PROCESSING — leave alone */ }
                }
            }

            call.respond(HttpStatusCode.Accepted, StemMixResponse(mixJobId = mixJobId))
        }

        // GET /api/v2/separate/mix/{mixJobId} — mix status
        get("/mix/{mixJobId}") {
            val mixJobId = call.parameters["mixJobId"]
                ?: throw NotFoundException("mixJobId required")
            val job = stemMixService.getJob(mixJobId)
                ?: throw NotFoundException("Mix job not found: $mixJobId")

            // owner 매칭 — Authorization 헤더가 있으면 강제, 없으면 token-only polling
            // 호환을 위해 통과 (모바일 background worker / webview 가 헤더 없이 폴링
            // 가능). 헤더가 있는데 invalid 면 capability 단독 분기 (mixJobId 자체가
            // unguessable v4 UUID 라 추측 불가능).
            if (jwtSecret != null && call.request.header(HttpHeaders.Authorization) != null) {
                val principal = runCatching { call.requireUser(jwtSecret) }.getOrNull()
                if (principal != null && job.ownerUserId != null && job.ownerUserId != principal.userId) {
                    throw NotFoundException("Mix job not found: $mixJobId")
                }
            }

            val downloadUrl = if (job.status == "COMPLETED") {
                val token = signer.sign(mixJobId, "download", appConfig.separation.mixUrlTtlSec)
                "/api/v2/separate/mix/$mixJobId/download?token=$token"
            } else null

            call.respond(HttpStatusCode.OK, StemMixStatusResponse(
                mixJobId = mixJobId,
                status = job.status,
                progress = job.progress,
                error = job.error,
                downloadUrl = downloadUrl,
            ))
        }

        // GET /api/v2/separate/mix/{mixJobId}/download?token=... — streamed mix
        get("/mix/{mixJobId}/download") {
            val mixJobId = call.parameters["mixJobId"]
                ?: throw NotFoundException("mixJobId required")
            val token = call.request.queryParameters["token"]
                ?: throw IllegalArgumentException("token required")

            if (!signer.verify(mixJobId, "download", token)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }

            val job = stemMixService.getJob(mixJobId)
                ?: throw NotFoundException("Mix job not found: $mixJobId")

            // capability(HMAC) + identity(JWT 헤더) 이중. 헤더가 있고 valid 면 owner
            // 매칭 강제. 헤더가 invalid 면 capability 단독 fallback — stem 다운로드와 동일.
            if (jwtSecret != null && call.request.header(HttpHeaders.Authorization) != null) {
                val principal = runCatching { call.requireUser(jwtSecret) }.getOrNull()
                if (principal != null && job.ownerUserId != null && job.ownerUserId != principal.userId) {
                    throw NotFoundException("Mix job not found: $mixJobId")
                }
            }

            if (job.status != "COMPLETED" || !job.outputFile.exists()) {
                throw NotFoundException("Mix not ready: status=${job.status}")
            }
            call.respondDownload(
                file = job.outputFile,
                objectKey = ObjectKey.separationMix(mixJobId),
                contentType = ContentType("audio", "mpeg"),
                downloadFilename = "$mixJobId.mp3",
                store = objectStore,
            )
        }
    }
}

/**
 * 클라(iOS AVAsset / Android MediaMetadataRetriever)와 BFF(ffprobe) 사이의
 * 영상 길이 측정 ms 단위 오차 허용치. 100ms 이내면 자동 clamp.
 */
private const val TRIM_DURATION_TOLERANCE_MS = 100L

/**
 * admin 대시보드의 "영상 당 분리 사용량" KPI 원본. spec 에 trim 윈도우가 있으면 그 길이,
 * 없으면 source 파일을 ffprobe 로 측정. probe 실패하면 0 (대시보드에서 길이 모름으로 표시).
 *
 * [MediaTrimmer.probeDurationMs] 는 suspend (내부 withContext(Dispatchers.IO)) 라 본 함수도
 * suspend. ffprobe 실패는 분석 손실로만 끝나고 separation 잡 자체는 계속 — runCatching 으로 흡수.
 */
internal suspend fun computeSeparationSourceDurationMs(pipelineInput: File, spec: SeparationSpec): Long {
    if (spec.trimStartMs != null && spec.trimEndMs != null) {
        return (spec.trimEndMs - spec.trimStartMs).coerceAtLeast(0L)
    }
    return runCatching { MediaTrimmer.probeDurationMs(pipelineInput) ?: 0L }
        .getOrDefault(0L)
        .coerceAtLeast(0L)
}

/**
 * Idempotency 키. 같은 (caller, editedRenderJobId, trim 구간, 언어, mediaType) 으로
 * 들어온 요청은 같은 키 → SeparationService.submit 이 기존 active jobId 반환.
 *
 * **userId 필수**: 같은 spec 이라도 caller 가 다르면 다른 키 → 다른 사용자의 active
 * 잡 ID 가 dedup-hit 으로 노출되는 IDOR 오라클 차단. 미인증 호출 (운영에서는 발생
 * 안 함, 테스트 호환) 은 "anon" 으로 묶임.
 *
 * **editedRenderJobId path 만 지원**. legacy multipart upload 는 source 식별이 file
 * 바이트 hash 라 별도 비용 (대용량 영상 stream hash). 현재 "이 구간 음원분리" 버튼은
 * 항상 edited render 결과를 source 로 쓰므로 이 한정으로 충분. upload 경로 dedup 이
 * 필요해지면 RenderInputCacheService 의 sha256 prefix 패턴 재사용 가능.
 */
internal fun buildSeparationDedupKey(spec: SeparationSpec, userId: UUID?): String? {
    val editedId = spec.editedRenderJobId ?: return null
    // 미인증 caller 는 dedup 비활성화 — "anon" 단일 버킷에 묶이면 jwtSecret 미주입
    // 분기 (테스트 / dev) 에서 동시 caller 들이 cross-pollute (다른 캐서가 같은 spec 으로
    // 첫 caller 의 jobId 를 dedup-hit 으로 받음). 운영은 항상 userId set 이라 영향 없음.
    if (userId == null) return null
    val start = spec.trimStartMs ?: 0L
    val end = spec.trimEndMs ?: 0L
    return "user=$userId|edited=$editedId|trim=$start-$end|" +
        "lang=${spec.sourceLanguageCode}|type=${spec.mediaType}"
}

/**
 * **Caller-owned single-use** — `file` 의 owner 는 caller (SeparationRoutes 의 submit
 * 핸들러). maybeTrim 은 trim 이 발생하면 원본을 delete 하고 새 trimmed 파일을 owner 로
 * 양도. trim 이 없으면 동일 [file] 을 그대로 반환 — 그래도 caller 는 단일 ownership.
 * 두 케이스 모두 호출 후엔 [file] 을 다시 사용하면 안 됨 (지워졌거나 service 가 가져감).
 *
 * If [spec] carries a trim range, probe the file, validate against its
 * actual duration, then stream-copy cut the window with ffmpeg. The
 * trimmed file replaces the source (the source is deleted to free disk
 * before the upstream upload begins). Blocking ffprobe / ffmpeg calls
 * run on [Dispatchers.IO] so the Netty worker isn't held while a 500 MB
 * file is being cut. Throws [ApiErrorException] with error codes the
 * client can branch on.
 *
 * Phase 1 follow-up: [MediaSourceResolver] now always returns a
 * caller-owned file (a copy of the render output for editedRenderJobId,
 * or the upload itself for legacy uploads), so we can unconditionally
 * delete it on success/failure without risk of clobbering shared state.
 */
/**
 * maybeTrim 결과 — file 은 다운스트림 파이프라인 입력, audioPreExtracted 는
 * MediaTrimmer 가 PCM WAV 로 audio 추출 했는지 여부. SeparationService.runPipeline 이
 * 이 flag 로 MP3 추출 단계 skip 결정. reference equality 가 아닌 명시 Boolean 으로
 * 캡슐화.
 */
internal data class MaybeTrimResult(val file: File, val audioPreExtracted: Boolean)

internal suspend fun maybeTrim(
    file: File,
    spec: SeparationSpec,
): MaybeTrimResult = withContext(Dispatchers.IO) {
    val start = spec.trimStartMs
    val rawEnd = spec.trimEndMs
    if (start == null || rawEnd == null) return@withContext MaybeTrimResult(file, audioPreExtracted = false)

    val durationMs = MediaTrimmer.probeDurationMs(file)
        ?: run {
            file.delete()
            throw ApiErrorException(
                HttpStatusCode.InternalServerError,
                "ffmpeg_error",
                "Could not probe source duration",
            )
        }
    // iOS AVAsset / Android MediaMetadataRetriever vs ffprobe 사이 ms 단위 측정 오차 (보통 1~수십ms).
    // "전체 구간" 자동 선택 시 clientEnd > durationMs 가 흔함 — TRIM_DURATION_TOLERANCE_MS 이내면
    // 자동 clamp, 초과면 client bug 신호로 보고 reject.
    val end = if (rawEnd > durationMs && rawEnd - durationMs <= TRIM_DURATION_TOLERANCE_MS) {
        durationMs
    } else if (rawEnd > durationMs) {
        file.delete()
        throw ApiErrorException(
            HttpStatusCode.BadRequest,
            "trim_end_exceeds_duration",
            "trimEndMs=$rawEnd but file duration=$durationMs",
        )
    } else rawEnd

    // MediaTrimmer.trim 출력은 sample-accurate PCM WAV (lossless, audio-only, Perso 호환).
    // 다운스트림은 audioPreExtracted=true 로 별도 MP3 추출 단계 skip.
    val trimmed = File(file.parentFile, "${file.nameWithoutExtension}.trimmed.${MediaTrimmer.OUTPUT_EXTENSION}")
    val ok = MediaTrimmer.trim(file, start, end, trimmed)
    if (!ok) {
        file.delete()
        throw ApiErrorException(
            HttpStatusCode.InternalServerError,
            "ffmpeg_error",
            "Trim extraction failed",
        )
    }
    file.delete()
    MaybeTrimResult(trimmed, audioPreExtracted = true)
}
