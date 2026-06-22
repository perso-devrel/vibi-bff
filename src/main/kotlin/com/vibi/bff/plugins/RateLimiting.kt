package com.vibi.bff.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.service.AuthService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.header
import kotlin.time.Duration.Companion.minutes

/**
 * 인-프로세스 레이트리밋 — 비용 폭주(악의적 대량 호출)를 외부 인프라 없이 둔화한다.
 *
 * **설계 의도**: 이 BFF 의 1차 비용 방어선은 크레딧 시스템(`/separate` 가 Perso 호출 전
 * `reserve`)이다. 레이트리밋은 크레딧이 막지 못하는 두 비용 증폭 경로를 보조 차단:
 *   1. [RL_AUTH]  — 무인증 auth 로그인 대량 호출 → 가입 보너스 크레딧 양산 → Perso 비용.
 *   2. [RL_RENDER] — render submit 은 크레딧 미차감 → ffmpeg CPU 폭주 → Cloud Run vCPU 과금.
 *   3. [RL_SEPARATE] — 크레딧이 1차 방어지만 submit 자체의 보조 상한.
 *
 * **적용 안 하는 곳**: 상태 폴링 GET / stem·render 다운로드 — CPU 거의 안 쓰고, 막으면
 * 모바일이 결과 대기 중 429 를 맞아 UX 만 나빠진다.
 *
 * **한계**: Cloud Run min=0 + max-instances 분산이라 토큰버킷이 인스턴스별로 흩어지고
 * scale-to-zero 시 초기화된다. "무료로 폭주를 둔화"하는 목적엔 충분하고, 정확한 분산
 * 카운팅(Redis 등)은 비용이 들어 현 단계엔 과하다.
 */

val RL_AUTH = RateLimitName("auth")
val RL_RENDER = RateLimitName("render")
val RL_SEPARATE = RateLimitName("separate")

fun Application.configureRateLimiting(jwtSecret: String) {
    install(RateLimit) {
        // 무인증 로그인 게이트웨이 — IP 키. 정상 로그인은 분당 한두 번이라 10 이면 넉넉.
        register(RL_AUTH) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIpKey() }
        }
        // render submit — ffmpeg CPU 직결. userId 키(없으면 IP fallback).
        register(RL_RENDER) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.userOrIpKey(jwtSecret) }
        }
        // separation submit — 크레딧이 1차 방어, 여기선 보조 상한이라 조금 더 여유.
        register(RL_SEPARATE) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.userOrIpKey(jwtSecret) }
        }
    }
}

/**
 * 신뢰 프록시 hop 수 — `X-Forwarded-For` 의 **오른쪽에서** 이만큼 건너뛴 위치를 실제 클라 IP 로
 * 본다. leftmost 는 클라가 임의 위조 가능하므로(가입 보너스 크레딧 파밍 우회), Cloud Run 처럼
 * LB 가 신뢰 IP 를 오른쪽에 append 하는 환경에서는 이 값을 설정해 위조 불가 위치를 키로 쓴다.
 *
 *  - 미설정(기본 0): 기존 동작(leftmost) 유지 — 하위호환. 단 위조 가능하므로 운영에선 설정 권장.
 *  - Cloud Run: 보통 1 (맨 오른쪽 GFE hop 을 건너뛴 그 앞이 LB 가 기록한 신뢰 클라 IP).
 *    실제 프록시 체인 길이에 맞춰 조정.
 */
private val TRUSTED_PROXY_HOPS: Int =
    System.getenv("RATE_LIMIT_TRUSTED_PROXY_HOPS")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

/**
 * 레이트리밋 버킷 키용 클라이언트 IP. 헤더 부재(로컬 dev) 시 소켓 remote host fallback.
 * [TRUSTED_PROXY_HOPS] 로 위조 불가 위치를 선택 (env 미설정 시 leftmost — 기존 동작).
 */
private fun ApplicationCall.clientIpKey(): String {
    val xff = request.header("X-Forwarded-For")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
    val ip = when {
        xff.isNullOrEmpty() -> request.origin.remoteHost
        TRUSTED_PROXY_HOPS > 0 -> xff.getOrNull(xff.size - 1 - TRUSTED_PROXY_HOPS) ?: xff.last()
        else -> xff.first()
    }
    return "ip:$ip"
}

/**
 * 인증된 호출은 JWT `sub`(userId) 로 키 — 한 계정이 IP 를 바꿔도 동일 버킷. 서명 검증을 거쳐
 * 위조 sub 로 버킷을 분산시키는 우회를 막는다. 토큰 부재/검증 실패 시 IP 키로 fallback.
 */
private fun ApplicationCall.userOrIpKey(jwtSecret: String): String {
    val token = request.header("Authorization")
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (token != null) {
        val sub = runCatching {
            JWT.require(Algorithm.HMAC256(jwtSecret))
                .withIssuer(AuthService.ISSUER)
                .withAudience(AuthService.AUDIENCE)
                .build()
                .verify(token)
                .subject
        }.getOrNull()
        if (!sub.isNullOrBlank()) return "user:$sub"
    }
    return clientIpKey()
}
