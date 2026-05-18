package com.vibi.bff.routes

import com.vibi.bff.MAX_UPLOAD_FILE_SIZE
import com.vibi.bff.config.AppConfig
import com.vibi.bff.model.*
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.GcsObjectStore
import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.StemMixService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun Route.separationRoutes(
    separationService: SeparationService,
    stemMixService: StemMixService,
    signer: SignedUrlService,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
    mediaSourceResolver: MediaSourceResolver,
    gcsObjectStore: GcsObjectStore?,
) {
    route("/separate") {
        // POST /api/v2/separate — submit job
        // Accepts either multipart `file` (legacy) or `spec.editedRenderJobId`
        // referencing a completed /api/v2/render output. See MediaSourceResolver.
        post {
            val (filePart, specOpt) = parseOptionalUploadAndSpec<SeparationSpec>(
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE),
                fileStorage,
                MAX_UPLOAD_FILE_SIZE,
            )
            val spec = specOpt ?: run {
                filePart?.delete()
                throw IllegalArgumentException("spec is required")
            }

            // Pre-check: 같은 (source+spec) 의 active 잡이 이미 있으면 resolve/trim
            // 으로 디스크 낭비하지 말고 바로 기존 jobId 반환 — "이 구간 음원분리" 버튼
            // 연타 방어. submit 의 atomic claim 이 진짜 safety net 이라 pre-check
            // miss 해도 정확성은 유지되지만 IO 절약 효과 큼.
            val dedupKey = buildSeparationDedupKey(spec)
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
            try {
                source = mediaSourceResolver.resolve(filePart, spec.editedRenderJobId)
                pipelineInput = maybeTrim(source, spec)
                val jobId = separationService.submit(pipelineInput, spec, dedupKey)
                call.respond(HttpStatusCode.Accepted, SeparationResponse(jobId = jobId))
            } catch (e: Throwable) {
                runCatching { pipelineInput?.delete() }
                if (pipelineInput != source) runCatching { source?.delete() }
                throw e
            }
        }

        // GET /api/v2/separate/{jobId} — status + stem URLs (signed)
        get("/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
            val job = separationService.getJob(jobId)
                ?: throw NotFoundException("Separation job not found: $jobId")

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
            val stem = job.stems.firstOrNull { it.stemId == stemId }
                ?: throw NotFoundException("Stem not found: $stemId")
            if (!stem.file.exists()) {
                throw NotFoundException("Stem file missing on disk")
            }
            val ext = stem.file.extension.ifBlank { "wav" }
            call.respondDownload(
                file = stem.file,
                objectKey = "separation/$jobId/${stem.stemId}.$ext",
                contentType = contentTypeForExtension(ext, ContentType("audio", "wav")),
                downloadFilename = "${stem.stemId}.$ext",
                gcs = gcsObjectStore,
            )
        }

        // POST /api/v2/separate/{jobId}/mix — kick off mixing; on success, stems are disposed
        post("/{jobId}/mix") {
            val jobId = call.parameters["jobId"]
                ?: throw NotFoundException("jobId required")
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

            stemMixService.submit(mixJobId, selected) { completed ->
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
            if (job.status != "COMPLETED" || !job.outputFile.exists()) {
                throw NotFoundException("Mix not ready: status=${job.status}")
            }
            call.respondDownload(
                file = job.outputFile,
                objectKey = "separation/mix/$mixJobId.mp3",
                contentType = ContentType("audio", "mpeg"),
                downloadFilename = "$mixJobId.mp3",
                gcs = gcsObjectStore,
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
 * Idempotency 키. 같은 (editedRenderJobId, trim 구간, 화자 수, 언어, mediaType) 으로
 * 들어온 요청은 같은 키 → SeparationService.submit 이 기존 active jobId 반환.
 *
 * **editedRenderJobId path 만 지원**. legacy multipart upload 는 source 식별이 file
 * 바이트 hash 라 별도 비용 (대용량 영상 stream hash). 현재 "이 구간 음원분리" 버튼은
 * 항상 edited render 결과를 source 로 쓰므로 이 한정으로 충분. upload 경로 dedup 이
 * 필요해지면 RenderInputCacheService 의 sha256 prefix 패턴 재사용 가능.
 */
internal fun buildSeparationDedupKey(spec: SeparationSpec): String? {
    val editedId = spec.editedRenderJobId ?: return null
    val start = spec.trimStartMs ?: 0L
    val end = spec.trimEndMs ?: 0L
    return "edited=$editedId|trim=$start-$end|spk=${spec.numberOfSpeakers}|" +
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
internal suspend fun maybeTrim(
    file: File,
    spec: SeparationSpec,
): File = withContext(Dispatchers.IO) {
    val start = spec.trimStartMs
    val rawEnd = spec.trimEndMs
    if (start == null || rawEnd == null) return@withContext file

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

    val ext = file.extension.ifEmpty { "bin" }
    val trimmed = File(file.parentFile, "${file.nameWithoutExtension}.trimmed.$ext")
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
    trimmed
}
