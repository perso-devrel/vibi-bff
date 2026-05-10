package com.vibi.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
)

@Serializable
data class AuthUser(
    val sub: String,
    val email: String,
    val name: String,
    val picture: String? = null,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val expiresAt: Long,
    val user: AuthUser,
)

/**
 * Google `tokeninfo` endpoint 응답. exp/iat 등 일부 필드는 문자열로 내려와 String 으로 받음.
 * 검증에 필요한 필드만 정의 — 나머지는 ignoreUnknownKeys 로 무시.
 */
@Serializable
data class GoogleTokenInfo(
    val sub: String,
    val email: String,
    val aud: String,
    val exp: String,
    @SerialName("email_verified") val emailVerified: String? = null,
    val name: String? = null,
    val picture: String? = null,
)
