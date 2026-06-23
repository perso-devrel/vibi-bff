package com.vibi.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RFC 8628 device-flow DTO. 필드명은 plugin 클라이언트(`src/auth/bffClient.ts`)가 읽는 shape 와
 * 1:1 (camelCase) — 클라는 JWT 를 디코딩하지 않고 응답 body 의 accessToken/expiresAt/user 만 읽는다.
 */

@Serializable
data class DeviceStartResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Long,
    val interval: Int,
)

@Serializable
data class DevicePollRequest(
    val deviceCode: String? = null,
)

/** 아직 미승인. 클라는 status != "authorized" 를 pending 으로 처리. */
@Serializable
data class DevicePollPendingResponse(
    val status: String = "authorization_pending",
)

/**
 * 승인 완료 — access token 발급. expiresAt 은 ms(클라 tokenStore 가 Date.now() 와 비교).
 * AuthResponse 와 동일 필드(accessToken/expiresAt/user) + status 만 추가.
 */
@Serializable
data class DevicePollAuthorizedResponse(
    val status: String = "authorized",
    val accessToken: String,
    val expiresAt: Long,
    val user: AuthUser,
)

/** 404(invalid_device_code)/410(expired) 의 body. 클라는 status code 로 분기하고 body 는 무시. */
@Serializable
data class DeviceAuthError(
    val error: String,
)

/** Google token endpoint 응답 — 교환 후 id_token 만 사용. */
@Serializable
data class GoogleTokenResponse(
    @SerialName("id_token") val idToken: String? = null,
)
