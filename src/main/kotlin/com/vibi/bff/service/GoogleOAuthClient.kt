package com.vibi.bff.service

import com.vibi.bff.model.GoogleTokenResponse
import com.vibi.bff.plugins.ApiErrorException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory

/**
 * Google OAuth 2.0 Authorization Code 플로우 클라이언트 (server-side, device-flow 콜백용).
 *
 * 모바일(AuthService.verifyGoogleIdToken)은 클라가 받은 id_token 을 검증만 하지만, UXP 패널은
 * 브라우저를 통한 server-side code 교환이 필요하다: [authUrl] 로 사용자를 consent 화면에 보내고,
 * 콜백의 `code` 를 [exchangeCodeForIdToken] 로 id_token 과 교환한다. 그 id_token 은 다시
 * AuthService.verifyGoogleIdToken 으로 넘겨 검증·upsert·가입보너스를 그대로 재사용한다.
 *
 * 이 [clientId] 는 web OAuth 클라이언트(client secret + redirect URI 보유)이며, 교환된 id_token
 * 의 aud 가 이 값이므로 AuthConfig.googleClientIds 에 포함되어야 검증을 통과한다(loadConfig 가 자동 병합).
 */
class GoogleOAuthClient(
    private val httpClient: HttpClient,
    val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun authUrl(state: String): String {
        val query = Parameters.build {
            append("client_id", clientId)
            append("redirect_uri", redirectUri)
            append("response_type", "code")
            append("scope", "openid email profile")
            append("state", state)
            append("prompt", "select_account")
        }.formUrlEncode()
        return "$AUTH_ENDPOINT?$query"
    }

    suspend fun exchangeCodeForIdToken(code: String): String {
        val response = httpClient.post(TOKEN_ENDPOINT) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("code", code)
                        append("client_id", clientId)
                        append("client_secret", clientSecret)
                        append("redirect_uri", redirectUri)
                        append("grant_type", "authorization_code")
                    },
                ),
            )
        }
        if (!response.status.isSuccess()) {
            log.warn("Google token exchange failed {}: {}", response.status.value, response.bodyAsText().take(200))
            throw ApiErrorException(HttpStatusCode.Unauthorized, "google_code_exchange_failed")
        }
        val body: GoogleTokenResponse = response.body()
        return body.idToken
            ?: throw ApiErrorException(HttpStatusCode.Unauthorized, "google_no_id_token")
    }

    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }
}
