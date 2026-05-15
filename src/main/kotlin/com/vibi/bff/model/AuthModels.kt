package com.vibi.bff.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth provider 식별자. `users.provider` 컬럼의 단일 소스 — SQL `CHECK` constraint
 * (V1__users.sql) 도 이 enum 의 [dbValue] 와 동기.
 */
enum class AuthProvider(val dbValue: String) {
    GOOGLE("google"),
    APPLE("apple"),
}

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
)

/**
 * Apple Sign In 으로 받은 ID Token 교환 요청.
 *
 * - [fullName] — Apple 은 사용자 동의 흐름에서 **최초 1회만** fullName 을 제공한다.
 *   iOS 클라이언트가 그 시점에 받은 값을 그대로 전달; 두 번째 로그인부터는 null.
 *   서버는 신규 가입 시에만 fullName 을 user.name 으로 채우고, 이후엔 DB 의 기존
 *   name 을 보존한다.
 */
@Serializable
data class AppleAuthRequest(
    val idToken: String,
    val fullName: String? = null,
)

/**
 * BFF 가 발급하는 access token 의 user 식별자.
 *
 * - [sub] — **internal UUID 문자열**. user 테이블 PK. Google/Apple 의 native sub 가
 *   아니라 BFF 가 가입 시 발급한 UUID. 향후 IAP `appAccountToken` 으로도 재사용.
 */
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
