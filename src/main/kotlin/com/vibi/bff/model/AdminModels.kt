package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * 일별 사용량 집계. `date` 는 UTC 기준 ISO-8601 (YYYY-MM-DD). render/separation 잡 카운트 +
 * 누적 입력 길이 ms — 대시보드의 라인/막대 차트 데이터.
 */
@Serializable
data class AdminDailyStats(
    val date: String,
    val renderCount: Long,
    val separationCount: Long,
    val totalSourceDurationMs: Long,
)

/**
 * 운영자 대시보드의 사용자 목록 한 행. 회원가입 정보 + 누적 사용량.
 *
 * - [totalRenders] / [totalSeparations] — 잡 status 와 무관한 시도 횟수 (FAILED 포함).
 *   대시보드는 "사용 시도" 가 핵심이라 성공만 카운트하지 않는다.
 * - [totalSourceDurationMs] — 사용자가 올린 입력 영상의 누적 분량 ms (render_jobs 만).
 * - [lastActivityAt] — 가장 최근 잡 (render or separation) 의 created_at, 둘 다 없으면 가입 시각.
 */
@Serializable
data class AdminUserOverview(
    val userId: String,
    val email: String,
    val name: String,
    val role: String,
    val totalRenders: Long,
    val totalSeparations: Long,
    val totalSourceDurationMs: Long,
    val lastActivityAt: String,
)

@Serializable
data class AdminUsersResponse(
    val users: List<AdminUserOverview>,
    val total: Long,
)

/**
 * 사용자별 render 잡 + 그 잡의 분리 횟수. "영상 당 음원분리 개수" 의 가시화.
 */
@Serializable
data class AdminUserJob(
    val jobId: String,
    val status: String,
    val sourceDurationMs: Long,
    val createdAt: String,
    val finishedAt: String?,
    val separationCount: Long,
)

@Serializable
data class AdminUserJobsResponse(
    val jobs: List<AdminUserJob>,
    val total: Long,
)

/**
 * 대시보드 상단 KPI 카드. 전체 누적 + 최근 7일 비교 같은 단일 숫자 시리즈.
 */
@Serializable
data class AdminOverview(
    val totalUsers: Long,
    val totalRenders: Long,
    val totalSeparations: Long,
    val totalSourceDurationMs: Long,
    val activeUsersLast7Days: Long,
)

/**
 * 외부 API (Perso 등) 일별 호출 통계. 비용 추정 + 실패율 가시화.
 *
 * - [provider] — 'perso' 등
 * - [endpoint] — 'audio-separation' 등 logical operation 단위
 * - [callCount] — 성공/실패 합산
 * - [failureCount] — `success=false` 카운트. 실패율 = failureCount / callCount
 * - [p95LatencyMs] — 응답 시간 분포 (Postgres `percentile_cont` 사용)
 */
@Serializable
data class AdminExternalCallDaily(
    val date: String,
    val provider: String,
    val endpoint: String,
    val callCount: Long,
    val failureCount: Long,
    val p95LatencyMs: Long,
)

/**
 * 영상 길이 분포 히스토그램. render_jobs.source_duration_ms 를 5개 bucket 으로 묶음.
 * Perso 영업 미팅에서 자주 묻는 "어떤 길이가 주로 분리되는가" 질문 답변.
 */
@Serializable
data class AdminDurationBucket(
    val bucket: String,          // '0-1m' / '1-5m' / '5-15m' / '15-60m' / '60m+'
    val count: Long,
)

/**
 * 진행 중 잡 — render+separation 통합 PROCESSING 목록. stuck 잡 탐지용.
 * v1 은 DB row 의 status='PROCESSING' 기준 — in-memory map 과 정확히 동기화되진 않지만
 * 서버 재시작 후 orphan 탐지엔 충분.
 */
@Serializable
data class AdminActiveJob(
    val jobType: String,         // 'render' / 'separation'
    val jobId: String,
    val userEmail: String,
    val sourceDurationMs: Long,
    val createdAt: String,
)

/**
 * 신규 가입자 일별 추세 + provider 분포. iOS-first 정책 검증용.
 */
@Serializable
data class AdminSignupDaily(
    val date: String,
    val googleCount: Long,
    val appleCount: Long,
)
