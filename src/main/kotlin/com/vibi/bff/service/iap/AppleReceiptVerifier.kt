package com.vibi.bff.service.iap

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.vibi.bff.config.AppleIapConfig
import com.vibi.bff.plugins.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Apple App Store Server API 로 transaction 검증.
 *
 * 흐름:
 * 1. `.p8` PEM 을 [ECPrivateKey] 로 파싱 (한 번만, 생성자에서).
 * 2. ES256 JWT 발급 (iss=issuerId, aud=appstoreconnect-v1, bid=bundleId, exp=iat+3600).
 *    JWT 는 max 1시간 유효 → [signedJwtCache] 에 atomic 캐시. 만료 60초 전이면 재발급.
 * 3. `GET {apiHost}/inApps/v1/transactions/{transactionId}` 호출.
 * 4. 응답의 `signedTransactionInfo` (JWS) 의 payload 부분만 base64-url 디코드해 검증:
 *    - `transactionId` 일치 (request 와)
 *    - `bundleId` 일치 ([config].bundleId)
 *    - `productId` 일치 (호출 시 인자)
 *    - `inAppOwnershipType == "PURCHASED"` (FAMILY_SHARED 거부 — 가족 공유로 받은 영수증은 비활성)
 *    - `revocationDate` 없음 (환불/취소된 transaction)
 *
 * **JWS signature 검증 생략 이유**: App Store Server API 자체가 우리 ES256 JWT 로 인증된
 * 채널이고, Apple 이 직접 응답을 서명해 보낸다. 응답 body 의 추가 JWS x.509 chain 검증은
 * Apple webhook (server-to-server notifications) 처럼 BFF 가 push 받는 경로에서만 필요.
 * 우리는 pull 호출이라 응답 자체를 트러스트 앵커로 본다 (StoreKit2 공식 권장 흐름).
 *
 * 실패 시 [ReceiptVerifyFailure] 의 하위 타입 throw — 라우트가 `receipt_invalid` 로 매핑.
 */
class AppleReceiptVerifier(
    private val config: AppleIapConfig,
    private val httpClient: HttpClient,
) {

    private val log = LoggerFactory.getLogger("AppleReceiptVerifier")
    private val privateKey: ECPrivateKey = parsePkcs8Ec(config.privateKeyPem)
    private val algorithm: Algorithm = Algorithm.ECDSA256(
        // java-jwt 의 ECDSA256 은 sign 만 쓸 거라 public key 는 null 로 둘 수 없음 — dummy.
        // 본 verifier 는 outgoing JWT 서명만 수행하므로 verify 경로는 호출되지 않는다.
        DUMMY_EC_PUBLIC_KEY,
        privateKey,
    )

    private val signedJwtCache = AtomicReference<CachedJwt?>(null)

    /**
     * `transactionId` 영수증을 검증. 통과면 정상 반환, 실패면 [ReceiptVerifyFailure] throw.
     */
    suspend fun verify(transactionId: String, expectedProductId: String) {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(expectedProductId.isNotBlank()) { "expectedProductId must not be blank" }

        val token = currentBearerJwt()
        val url = "${config.apiHost}/inApps/v1/transactions/$transactionId"

        val response = try {
            httpClient.get {
                url {
                    takeFrom(url)
                    // takeFrom 만으로 보통 충분하지만 https 명시로 misconfig 방어.
                    protocol = URLProtocol.HTTPS
                }
                bearerAuth(token)
            }
        } catch (e: Exception) {
            log.warn("apple verify network failure tx={} cause={}", transactionId, e.javaClass.simpleName)
            throw ReceiptVerifyFailure.HttpFailure(0, e.message)
        }

        val status = response.status.value
        if (status >= 500) {
            // 5xx 는 일시 장애 — 호출자(routes) 가 분리 처리할 수 있게 별도 타입.
            log.warn("apple verify 5xx tx={} status={}", transactionId, status)
            throw ReceiptVerifyFailure.TransientUpstream(status)
        }
        if (status !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            log.warn("apple verify non-2xx tx={} status={} body_head={}", transactionId, status, body?.take(120))
            throw ReceiptVerifyFailure.HttpFailure(status, body)
        }

        val body = response.bodyAsText()
        val signedPayload = AppJson.decodeFromString<TransactionInfoResponse>(body).signedTransactionInfo
        val payload = decodeJwsPayload(signedPayload)

        // 모든 필드 검증 통과 후 정상 반환. payload 자체는 노출 안 함 (외부 메시지 sanitize).
        verifyField("transactionId", expected = transactionId, actual = payload.transactionId)
        verifyField("bundleId", expected = config.bundleId, actual = payload.bundleId)
        verifyField("productId", expected = expectedProductId, actual = payload.productId)
        val ownership = payload.inAppOwnershipType ?: ""
        if (ownership != "PURCHASED") {
            throw ReceiptVerifyFailure.PayloadMismatch("inAppOwnershipType", "PURCHASED", ownership)
        }
        if (payload.revocationDate != null) {
            throw ReceiptVerifyFailure.PayloadMismatch(
                "revocationDate", "null", payload.revocationDate.toString(),
            )
        }
    }

    private fun verifyField(field: String, expected: String, actual: String) {
        if (expected != actual) {
            throw ReceiptVerifyFailure.PayloadMismatch(field, expected, actual)
        }
    }

    /**
     * 현재 유효한 JWT 를 반환. 캐시된 token 의 만료가 60초 이내면 재발급.
     *
     * Apple 은 JWT max lifetime 1시간 (3600s) — 본 코드는 50분 동안 재사용해 호출당 서명 비용
     * 절감 (ES256 sign 자체는 µs 단위지만 cache 가 핫패스를 단순화).
     */
    private fun currentBearerJwt(): String {
        val now = Instant.now().epochSecond
        val cached = signedJwtCache.get()
        if (cached != null && cached.expiresAtEpoch - now > 60) {
            return cached.token
        }
        val iat = now
        val exp = iat + 3000 // 50분 — Apple 한도(3600)보다 짧게 잡아 시계 어긋남 대비.
        val token = JWT.create()
            .withKeyId(config.keyId)
            .withIssuer(config.issuerId)
            .withAudience("appstoreconnect-v1")
            .withIssuedAt(java.util.Date(iat * 1000L))
            .withExpiresAt(java.util.Date(exp * 1000L))
            .withClaim("bid", config.bundleId)
            .sign(algorithm)
        signedJwtCache.set(CachedJwt(token, exp))
        return token
    }

    private fun decodeJwsPayload(jws: String): JwsTransactionPayload {
        val parts = jws.split('.')
        if (parts.size != 3) {
            throw ReceiptVerifyFailure.HttpFailure(200, "malformed JWS (parts=${parts.size})")
        }
        val payloadJson = try {
            String(Base64.getUrlDecoder().decode(parts[1].padBase64()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw ReceiptVerifyFailure.HttpFailure(200, "malformed JWS payload base64")
        }
        // payload 의 transactionId/originalTransactionId 가 정수로 와도 string 으로 통일하기
        // 위해 JsonElement → 수동 변환. Apple 은 transactionId 를 string 으로 보내지만 일부
        // 필드(`revocationDate` 등)는 Long ms timestamp.
        val root = AppJson.parseToJsonElement(payloadJson).jsonObject
        return JwsTransactionPayload(
            transactionId = root.stringField("transactionId") ?: "",
            bundleId = root.stringField("bundleId") ?: "",
            productId = root.stringField("productId") ?: "",
            inAppOwnershipType = root.stringField("inAppOwnershipType"),
            revocationDate = root["revocationDate"]?.let { it.jsonPrimitive.longOrNull },
        )
    }

    private data class CachedJwt(val token: String, val expiresAtEpoch: Long)

    @Serializable
    private data class TransactionInfoResponse(
        @SerialName("signedTransactionInfo") val signedTransactionInfo: String,
    )

    private data class JwsTransactionPayload(
        val transactionId: String,
        val bundleId: String,
        val productId: String,
        val inAppOwnershipType: String?,
        val revocationDate: Long?,
    )

    companion object {
        /**
         * java-jwt 의 ECDSA256 algorithm 은 sign 만 사용해도 생성자가 public key 를 요구한다 (verify
         * 경로에서 throw 시키지 않으려고). 실제로 outgoing JWT 서명에만 쓰므로 verify 가 호출되면
         * NPE 가 나도 무방 — 호출 경로 없음. dummy public key 는 P-256 generator point 좌표
         * 그대로 (모두 0 이면 KeyFactory 가 거부).
         */
        private val DUMMY_EC_PUBLIC_KEY: ECPublicKey = run {
            // verify() 가 호출되지 않을 거라는 가정 하에 어떤 P-256 공개키든 무방. private key 의
            // 짝꿍 public 까지 계산하려면 BC 의존성이 필요해, 별도 hardcoded P-256 generator point
            // 를 KeyFactory 로 변환. P-256 curve 의 base point G (NIST SP 800-186).
            val kf = KeyFactory.getInstance("EC")
            val params = java.security.AlgorithmParameters.getInstance("EC").apply {
                init(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
            val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            val gx = java.math.BigInteger(
                "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16,
            )
            val gy = java.math.BigInteger(
                "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16,
            )
            kf.generatePublic(
                java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(gx, gy), spec),
            ) as ECPublicKey
        }

        private fun parsePkcs8Ec(pem: String): ECPrivateKey {
            val body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(body)
            val kf = KeyFactory.getInstance("EC")
            return kf.generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
        }

        private fun String.padBase64(): String {
            val mod = this.length % 4
            return if (mod == 0) this else this + "=".repeat(4 - mod)
        }

        private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String? =
            this[name]?.let { e: JsonElement ->
                runCatching { e.jsonPrimitive.contentOrNull }.getOrNull()
            }
    }
}
