package com.vibi.bff.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.model.DeviceAuthError
import com.vibi.bff.model.DevicePollAuthorizedResponse
import com.vibi.bff.model.DevicePollPendingResponse
import com.vibi.bff.model.DevicePollRequest
import com.vibi.bff.model.DeviceStartResponse
import com.vibi.bff.plugins.RL_AUTH
import com.vibi.bff.plugins.RL_DEVICE
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.DeviceCodeRepository
import com.vibi.bff.service.DeviceStatus
import com.vibi.bff.service.GoogleOAuthClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.vibi.bff.routes.DeviceAuthRoutes")

// OAuth `state` 토큰 전용 audience — access token(aud=vibi-mobile)으로 재생되는 것을 차단.
private const val STATE_AUDIENCE = "vibi-oauth-state"
private const val STATE_TTL_MS = 600_000L // 10분

/**
 * RFC 8628 device-code 로그인 라우트 (UXP 패널용). plugin server/ 의 `routes/auth.ts` 를 포팅.
 *
 * 발급 access token 은 **BFF 네이티브 iss/aud(vibi-bff/vibi-mobile)** 로 [AuthService.issueAccessToken]
 * 가 찍는다 — 그래야 분리·크레딧 등 protected 라우트의 `requireUser` 검증을 그대로 통과한다.
 * (plugin 클라는 토큰을 디코딩하지 않고 응답 body 의 accessToken/expiresAt/user 만 읽으므로 iss/aud 무관.)
 *
 * 레이트리밋: device/start·poll 은 폴링(2초)이 RL_AUTH(10/분)에 막히지 않도록 RL_DEVICE(관대).
 * 가입 보너스 크레딧을 만들 수 있는 google/start·callback 만 RL_AUTH(10/분, IP)로 보호한다.
 */
fun Route.deviceAuthRoutes(
    deviceCodes: DeviceCodeRepository,
    authService: AuthService,
    googleOAuth: GoogleOAuthClient?,
    baseUrl: String,
    jwtSecret: String,
) {
    val algorithm = Algorithm.HMAC256(jwtSecret)

    route("/auth") {
        rateLimit(RL_DEVICE) {
            post("/device/start") {
                val record = withContext(Dispatchers.IO) { deviceCodes.create() }
                call.respond(
                    DeviceStartResponse(
                        deviceCode = record.deviceCode,
                        userCode = record.userCode,
                        verificationUri = "$baseUrl/device",
                        verificationUriComplete = "$baseUrl/device?code=${record.userCode}",
                        expiresIn = deviceCodes.ttlSeconds,
                        interval = 2,
                    ),
                )
            }

            post("/device/poll") {
                val req = runCatching { call.receive<DevicePollRequest>() }.getOrNull() ?: DevicePollRequest()
                val deviceCode = req.deviceCode?.takeIf { it.isNotBlank() } ?: ""
                val record = withContext(Dispatchers.IO) { deviceCodes.poll(deviceCode) }
                if (record == null) {
                    call.respond(HttpStatusCode.NotFound, DeviceAuthError("invalid_device_code"))
                    return@post
                }
                when (record.status) {
                    DeviceStatus.EXPIRED ->
                        call.respond(HttpStatusCode.Gone, DeviceAuthError("expired"))
                    DeviceStatus.PENDING ->
                        call.respond(DevicePollPendingResponse())
                    DeviceStatus.AUTHORIZED -> {
                        val user = record.user
                        if (user == null) {
                            call.respond(DevicePollPendingResponse())
                        } else {
                            val issued = authService.issueAccessToken(user)
                            // single-use: 발급 후 코드 소비 — 유출된 deviceCode 재폴링으로 추가 토큰 못 찍게.
                            withContext(Dispatchers.IO) { deviceCodes.delete(deviceCode) }
                            call.respond(
                                DevicePollAuthorizedResponse(
                                    accessToken = issued.accessToken,
                                    expiresAt = issued.expiresAt,
                                    user = issued.user,
                                ),
                            )
                        }
                    }
                }
            }
        }

        rateLimit(RL_AUTH) {
            // 브라우저 플로우 1단계: Google consent 로 redirect. state 는 device user-code 를 담은
            // 단기 서명 토큰 — 콜백이 Google identity 를 대기 중인 패널 세션에 묶는다.
            get("/google/start") {
                val userCode = call.request.queryParameters["code"]?.takeIf { it.isNotBlank() }
                if (userCode == null) {
                    call.respondText("missing code", status = HttpStatusCode.BadRequest)
                    return@get
                }
                // /device 페이지의 required 체크박스가 수집하는 명시 동의의 서버측 backstop
                // (RFC 8628 consent-relay 피싱 완화) — JS 끈/손수 만든 요청도 여기서 거부.
                if (call.request.queryParameters["ack"] != "on") {
                    call.respondText(
                        "Please confirm you started this sign-in from the Vibi Separate panel.",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val oauth = googleOAuth
                if (oauth == null) {
                    call.respondText(
                        "Google sign-in is not configured on the server.",
                        status = HttpStatusCode.InternalServerError,
                    )
                    return@get
                }
                val state = JWT.create()
                    .withAudience(STATE_AUDIENCE)
                    .withClaim("userCode", userCode)
                    .withClaim("n", UUID.randomUUID().toString())
                    .withExpiresAt(Date(System.currentTimeMillis() + STATE_TTL_MS))
                    .sign(algorithm)
                call.respondRedirect(oauth.authUrl(state))
            }

            // 2단계: Google 이 auth code 와 함께 redirect. code→id_token 교환 후 기존 네이티브
            // 검증 경로(verifyGoogleIdToken)를 재사용 — users upsert + 가입 보너스까지 그대로.
            get("/google/callback") {
                val code = call.request.queryParameters["code"]?.takeIf { it.isNotBlank() }
                val state = call.request.queryParameters["state"]?.takeIf { it.isNotBlank() }
                if (code == null || state == null) {
                    call.respondText(resultPage(false, "Missing code/state."), ContentType.Text.Html, HttpStatusCode.BadRequest)
                    return@get
                }
                val userCode = try {
                    val decoded = JWT.require(algorithm).withAudience(STATE_AUDIENCE).build().verify(state)
                    decoded.getClaim("userCode").asString()?.takeIf { it.isNotBlank() }
                        ?: error("no userCode in state")
                } catch (e: Exception) {
                    call.respondText(resultPage(false, "Invalid or expired sign-in state."), ContentType.Text.Html, HttpStatusCode.BadRequest)
                    return@get
                }
                val oauth = googleOAuth
                if (oauth == null) {
                    call.respondText(resultPage(false, "Google sign-in is not configured."), ContentType.Text.Html, HttpStatusCode.InternalServerError)
                    return@get
                }
                try {
                    val idToken = oauth.exchangeCodeForIdToken(code)
                    val authUser = authService.verifyGoogleIdToken(idToken)
                    val record = withContext(Dispatchers.IO) { deviceCodes.authorize(userCode, authUser) }
                    when {
                        record == null ->
                            call.respondText(resultPage(false, "Code not found. Restart from the plugin."), ContentType.Text.Html, HttpStatusCode.NotFound)
                        record.status == DeviceStatus.EXPIRED ->
                            call.respondText(resultPage(false, "Code expired. Restart from the plugin."), ContentType.Text.Html, HttpStatusCode.Gone)
                        else ->
                            call.respondText(resultPage(true, "Signed in. Return to the plugin — it continues automatically."), ContentType.Text.Html)
                    }
                } catch (e: Exception) {
                    log.error("google callback failed: {}", e.message, e)
                    call.respondText(resultPage(false, "Sign-in failed. Please try again."), ContentType.Text.Html, HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

/**
 * `/device` 로그인 페이지 (루트 레벨, 비-/api/v2). 사용자가 패널에서 본 코드와 일치함을 명시 동의
 * (required 체크박스)한 뒤 Google 로 진행한다 — RFC 8628 consent-relay 피싱 완화. 폼은 GET 으로
 * /api/v2/auth/google/start?code=..&ack=on 에 제출된다(체크박스 미선택 시 브라우저가 제출 차단,
 * 서버도 ack=on 재확인).
 */
fun devicePageHtml(code: String): String {
    val safeCode = escapeHtml(code)
    val body = if (code.isNotBlank()) {
        """
        <form method="get" action="/api/v2/auth/google/start">
          <input type="hidden" name="code" value="$safeCode" />
          <div class="codebox"><span class="label">Device code</span><span class="val">$safeCode</span></div>
          <p class="warn">⚠️ Only continue if you <b>just started sign-in from the Vibi Separate panel</b> in
            Premiere Pro and this code matches the one shown there. If you didn't start this, close this page.</p>
          <label class="confirm">
            <input type="checkbox" id="ack" name="ack" value="on" required />
            <span>I started this sign-in from the Vibi Separate panel and the code above matches.</span>
          </label>
          <button type="submit" class="gbtn dim" id="go">Continue with Google</button>
        </form>
        <script>
          (function () {
            var go = document.getElementById("go"), ack = document.getElementById("ack");
            function sync() { go.classList.toggle("dim", !ack.checked); }
            ack.addEventListener("change", sync); sync();
          })();
        </script>
        """.trimIndent()
    } else {
        """<p class="err">No device code provided. Start sign-in from the plugin.</p>"""
    }
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Sign in to vibi</title>
<style>
  body { background:#161616; color:#e8e8e8; font-family:-apple-system,Segoe UI,Roboto,sans-serif; margin:0; }
  .card { max-width:420px; margin:48px auto; padding:28px; background:#1f1f1f; border:1px solid #303030; border-radius:12px; }
  h1 { font-size:18px; margin:0 0 4px; }
  p.sub { margin:0 0 18px; font-size:13px; color:#a0a0a0; }
  .codebox { margin:0 0 16px; padding:12px; border:1px solid #3a3a3a; border-radius:8px; text-align:center; background:#1b1b1b; }
  .codebox .label { display:block; font-size:11px; color:#909090; margin-bottom:6px; }
  .codebox .val { font-size:22px; font-weight:700; color:#e8e8e8; font-family:ui-monospace,monospace; letter-spacing:0.22em; }
  .warn { margin:0 0 18px; padding:10px 12px; border-radius:8px; background:rgba(255,112,102,0.10); border:1px solid rgba(255,112,102,0.35); font-size:12px; line-height:1.5; color:#ffb3ad; }
  .confirm { display:flex; gap:8px; align-items:flex-start; margin:0 0 14px; font-size:12px; color:#b8b8b8; line-height:1.45; }
  .confirm input { margin-top:2px; }
  .gbtn { display:block; width:100%; box-sizing:border-box; background:#fff; color:#1f1f1f; text-align:center; text-decoration:none; border:0; cursor:pointer; border-radius:6px; padding:11px; font-size:14px; font-weight:600; }
  .gbtn.dim { opacity:0.45; }
  .err { margin-top:16px; font-size:13px; color:#ff7066; text-align:center; }
</style>
</head>
<body>
  <div class="card">
    <h1>vibi</h1>
    <p class="sub">Sign in to continue in the plugin. New accounts get free credits.</p>
    $body
  </div>
</body>
</html>"""
}

private fun resultPage(ok: Boolean, message: String): String {
    val color = if (ok) "#4fd18b" else "#ff7066"
    return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" /><title>vibi sign-in</title>
<style>body{background:#161616;color:#e8e8e8;font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:0;}
.card{max-width:420px;margin:48px auto;padding:28px;background:#1f1f1f;border:1px solid #303030;border-radius:12px;}
h1{font-size:18px;margin:0 0 8px;} .msg{margin:0;font-size:14px;color:$color}</style></head>
<body><div class="card"><h1>vibi</h1><p class="msg">${escapeHtml(message)}</p></div></body></html>"""
}

private fun escapeHtml(s: String): String = buildString {
    for (ch in s) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
