package com.vibi.bff.routes

import com.vibi.bff.MAX_SEPARATION_AUDIO_SIZE
import com.vibi.bff.config.AppConfig
import com.vibi.bff.model.*
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.RL_SEPARATE
import com.vibi.bff.plugins.requireUser
import io.ktor.server.plugins.ratelimit.rateLimit
import com.vibi.bff.service.CreditCost
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.InsufficientCreditsException
import com.vibi.bff.service.MediaTrimmer
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SeparationRoutes")

/** Perso audio-separation 이 받는 모든 audio 포맷 — m4a (AAC), mp3, wav (PCM). 모바일이 trim
 * + audio extract 까지 끝낸 m4a 가 신규 경로의 default. mp3/wav 는 사용자가 직접 갖고 있는
 * audio 파일 (녹음, 외부 다운로드 등) 을 재인코딩 없이 그대로 보내는 케이스. video / flac /
 * ogg 등은 모두 reject — flac 은 Perso 가 silent fail 하는 회귀 (CLAUDE.md 참조). */
private val SEPARATION_AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav")

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
) {
    route("/separate") {
        // submit(POST)만 레이트리밋 — 크레딧이 1차 방어, 보조 상한. 상태 조회/stem GET 은 제외.
        rateLimit(RL_SEPARATE) {
        // POST /api/v2/separate — submit job
        // 모바일이 trim + audio extract 까지 끝낸 audio (m4a/mp3/wav) 만 받는다.
        // BFF 는 file 을 그대로 Perso 에 forward — ffmpeg 단계 없음.
        post {
            val principal = jwtSecret?.let { call.requireUser(it) }
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
            // 로컬 file 존재 체크 의도적 제거 — R2 가 source-of-truth. DB fallback 으로 재구축된
            // SeparationJob 은 placeholder File 을 들고 있고 실체는 R2. ObjectStore.uploadIfAbsent
            // 가 HEAD 로 R2 hit 확인 후 signed URL 302. R2 도 없으면 그 안에서 throw.
            val ext = stem.file.extension.ifBlank { "wav" }
            call.respondDownload(
                file = stem.file,
                objectKey = ObjectKey.separationStem(jobId, stem.stemId, ext),
                contentType = contentTypeForExtension(ext, ContentType("audio", "wav")),
                downloadFilename = "${stem.stemId}.$ext",
                store = objectStore,
            )
        }
    }
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
