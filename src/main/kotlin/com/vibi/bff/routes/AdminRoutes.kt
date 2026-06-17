package com.vibi.bff.routes

import com.vibi.bff.model.AdminUserJobsResponse
import com.vibi.bff.model.AdminUsersResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.NotFoundException
import com.vibi.bff.plugins.requireAdmin
import com.vibi.bff.service.AdminRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `/api/v2/admin/...` — read-only 분석 surface. 모든 라우트 진입 시 [requireAdmin] 으로
 * role=admin JWT 강제. URL slug 숨김 (landing middleware) + role 검사 이중 방어.
 *
 * v1 은 KPI / 일별 추세 / 사용자별 사용량 / 사용자별 잡 4개. mutating action 없음.
 *
 * 주의 — KDoc 안에 slash + asterisk 시퀀스는 nested comment 로 파싱돼 컴파일 깨짐.
 * 와일드카드 표현 필요 시 "..." 으로 대체.
 */
fun Route.adminRoutes(
    adminRepository: AdminRepository,
    jwtSecret: String,
) {
    route("/admin") {

        // 상단 KPI 카드 — 전체 사용자/잡 카운트 + 누적 분량 + 최근 7일 active user.
        get("/overview") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) { adminRepository.getOverview() }
            call.respond(HttpStatusCode.OK, data)
        }

        // 일별 추세. from / to 는 ISO date (YYYY-MM-DD). 누락 시 default 최근 30일.
        // from inclusive, to exclusive (Postgres date_trunc 와 동일 의미).
        get("/stats/daily") {
            call.respondDailyRange(jwtSecret, adminRepository::getDailyStats)
        }

        // 사용자 목록 — 최근 활동 desc. limit 1..200, offset >= 0.
        // q non-blank 면 email/name 부분일치 검색.
        get("/users") {
            call.requireAdmin(jwtSecret)
            val (limit, offset) = call.parsePagination()
            val query = call.request.queryParameters["q"]
            val (rows, total) = withContext(Dispatchers.IO) {
                adminRepository.getUsersOverview(limit, offset, query)
            }
            call.respond(HttpStatusCode.OK, AdminUsersResponse(users = rows, total = total))
        }

        // 외부 API (Perso) 일별 호출 카운트 + 실패율 + p95 latency.
        // 비용 예측 + 안정성 가시화.
        get("/stats/external-calls") {
            call.respondDailyRange(jwtSecret, adminRepository::getExternalCallsDaily)
        }

        // 영상 길이 분포 히스토그램. 5 buckets, 항상 5 칸 반환 (0 행도 명시적으로).
        get("/stats/duration-histogram") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) {
                adminRepository.getDurationHistogram()
            }
            call.respond(HttpStatusCode.OK, data)
        }

        // 진행 중 잡 (render + separation 통합). stuck 탐지.
        get("/jobs/active") {
            call.requireAdmin(jwtSecret)
            val data = withContext(Dispatchers.IO) {
                adminRepository.getActiveJobs()
            }
            call.respond(HttpStatusCode.OK, data)
        }

        // 일별 신규 가입자 + provider (google/apple) 분포. iOS-first 정책 검증.
        get("/stats/signups") {
            call.respondDailyRange(jwtSecret, adminRepository::getSignupDaily)
        }

        // 사용자별 render 잡 + 영상 당 분리 횟수.
        get("/users/{userId}/jobs") {
            call.requireAdmin(jwtSecret)
            val userIdParam = call.parameters["userId"]
                ?: throw NotFoundException("userId required")
            val userId = try {
                UUID.fromString(userIdParam)
            } catch (e: IllegalArgumentException) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_user_id")
            }
            val (limit, offset) = call.parsePagination()
            val (rows, total) = withContext(Dispatchers.IO) {
                adminRepository.getUserJobs(userId, limit, offset)
            }
            call.respond(HttpStatusCode.OK, AdminUserJobsResponse(jobs = rows, total = total))
        }
    }
}

/**
 * limit / offset 쿼리 파라미터 파싱 — limit 1..200 (default 50), offset >= 0 (default 0).
 * `/users` 와 `/users/{userId}/jobs` 가 공유.
 */
private fun ApplicationCall.parsePagination(): Pair<Int, Int> {
    val limit = (request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
    val offset = (request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    return limit to offset
}

/**
 * admin daily-range 핸들러 공통 골격 — requireAdmin + from/to 파싱 + IO dispatch + respond.
 * `/stats/daily`, `/stats/external-calls`, `/stats/signups` 세 핸들러가 공유.
 */
private suspend fun ApplicationCall.respondDailyRange(
    jwtSecret: String,
    fetch: (Instant, Instant) -> Any,
) {
    requireAdmin(jwtSecret)
    val (from, to) = parseDailyStatsRange(
        request.queryParameters["from"],
        request.queryParameters["to"],
    )
    val data = withContext(Dispatchers.IO) { fetch(from, to) }
    respond(HttpStatusCode.OK, data)
}

/**
 * `from` / `to` 를 ISO date (YYYY-MM-DD) 로 받아 UTC midnight Instant pair 로 변환.
 * 둘 다 누락 시 [최근 30일 전, 내일] 윈도우 default (오늘까지 포함).
 *
 * 잘못된 포맷 / from >= to / 91일 초과 윈도우 모두 400. 90일 cap 은 admin 쿼리 비용 가드.
 */
private fun parseDailyStatsRange(fromParam: String?, toParam: String?): Pair<Instant, Instant> {
    val today = LocalDate.now(ZoneOffset.UTC)
    val toDate = try {
        toParam?.let { LocalDate.parse(it) } ?: today.plusDays(1)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_to_date")
    }
    val fromDate = try {
        fromParam?.let { LocalDate.parse(it) } ?: toDate.minusDays(30)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_from_date")
    }
    if (!fromDate.isBefore(toDate)) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "from_must_be_before_to")
    }
    if (java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) > 90) {
        throw ApiErrorException(HttpStatusCode.BadRequest, "range_too_wide", "max 90 days")
    }
    return fromDate.atStartOfDay(ZoneOffset.UTC).toInstant() to
        toDate.atStartOfDay(ZoneOffset.UTC).toInstant()
}
