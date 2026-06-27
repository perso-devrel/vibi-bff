package com.vibi.bff.service.iap

import com.vibi.bff.plugins.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * AdMob 보상형 광고 Server-Side Verification (SSV) 콜백의 서명을 검증한다.
 *
 * 광고 시청 완료 시 Google 이 우리 콜백 URL 로 GET 요청을 보낸다. 쿼리스트링의 **마지막 두
 * 파라미터는 항상 `signature` 와 `key_id`** (그 순서), 나머지가 검증 대상 content 다. 즉
 * `&signature=` 직전까지의 부분 문자열(raw, URL-encoded 그대로)이 서명 원문이다.
 *
 * 검증:
 * 1. content = rawQuery 의 `&signature=` 이전 substring.
 * 2. `key_id` 로 Google 공개 검증키 JSON([VERIFIER_KEYS_URL]) 에서 해당 P-256 공개키 조회
 *    (캐시. 미스 시 single-flight + throttle 로 refetch — 키 로테이션 대응, 위조 key_id 스팸 차단).
 * 3. `SHA256withECDSA` 로 content(US-ASCII) 에 대한 base64url `signature` 검증.
 *
 * 통과 시 파싱된 [Parameters] 를 반환해 호출자가 user_id/transaction_id 를 재파싱 없이 쓴다.
 * 실패 시 [ReceiptVerifyFailure] throw — 라우트가 잡아 grant 하지 않는다 (영수증 경로와 동일 규약).
 * 서명 검증은 비밀값 없이 공개키로만 수행하므로 별도 자격증명 불필요.
 *
 * 참고: Google "Rewarded ads SSV" 문서. 검증키 JSON 은 `keys[].keyId`(Long) + `pem`(X.509).
 */
class AdMobSsvVerifier(
    private val httpClient: HttpClient,
) {
    private val log = LoggerFactory.getLogger("com.vibi.bff.service.iap.AdMobSsvVerifier")

    // keyId -> P-256 공개키. 키 로테이션 시 미스가 나면 refetch 해서 통째 교체.
    private val keysCache = AtomicReference<Map<Long, ECPublicKey>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshAtMs = AtomicLong(0)

    /**
     * SSV 콜백의 raw 쿼리스트링(맨 앞 `?` 제외) 서명을 검증. 통과 시 파싱된 [Parameters] 반환,
     * 실패 시 [ReceiptVerifyFailure] throw.
     */
    suspend fun verify(rawQueryString: String): Parameters {
        val sigIdx = rawQueryString.indexOf("&signature=")
        if (sigIdx < 0) {
            throw ReceiptVerifyFailure.PayloadMismatch("signature", "present", "absent")
        }
        val content = rawQueryString.substring(0, sigIdx)

        val params = parseQueryString(rawQueryString)
        val signatureB64 = params["signature"]
            ?: throw ReceiptVerifyFailure.PayloadMismatch("signature", "present", "absent")
        val keyId = params["key_id"]?.toLongOrNull()
            ?: throw ReceiptVerifyFailure.PayloadMismatch("key_id", "long", params["key_id"].toString())

        val publicKey = publicKeyFor(keyId)
            ?: throw ReceiptVerifyFailure.PayloadMismatch("key_id", "known", keyId.toString())

        val signatureBytes = try {
            Base64.getUrlDecoder().decode(padBase64(signatureB64))
        } catch (e: IllegalArgumentException) {
            throw ReceiptVerifyFailure.PayloadMismatch("signature", "base64url", "malformed")
        }

        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(content.toByteArray(StandardCharsets.US_ASCII))
                verify(signatureBytes)
            }
        } catch (e: Exception) {
            log.warn("admob ssv verify error keyId={} cause={}", keyId, e.javaClass.simpleName)
            false
        }
        if (!valid) {
            throw ReceiptVerifyFailure.PayloadMismatch("signature", "valid", "invalid")
        }
        return params
    }

    /**
     * 캐시에서 [keyId] 조회. 미스면 single-flight 로 refetch (로테이션 대응). 단, 캐시가 이미
     * 차 있는데 이 keyId 만 없으면(위조 가능성) [MIN_REFRESH_INTERVAL_MS] throttle 로 매 요청
     * gstatic refetch 증폭을 막는다 — `/admob-ssv` 는 무인증이라 위조 key_id 스팸이 가능하므로.
     */
    private suspend fun publicKeyFor(keyId: Long): ECPublicKey? {
        keysCache.get()[keyId]?.let { return it }
        refreshMutex.withLock {
            keysCache.get()[keyId]?.let { return it } // 락 획득 사이 다른 코루틴이 채웠을 수 있음
            val cached = keysCache.get()
            val now = System.currentTimeMillis()
            if (cached.isNotEmpty() && now - lastRefreshAtMs.get() < MIN_REFRESH_INTERVAL_MS) {
                return null // 키는 있는데 이 keyId 만 없음 + 최근 refetch 함 → 위조로 보고 throttle
            }
            lastRefreshAtMs.set(now)
            refreshKeys()
        }
        return keysCache.get()[keyId]
    }

    private suspend fun refreshKeys() {
        val body = try {
            httpClient.get(VERIFIER_KEYS_URL).bodyAsText()
        } catch (e: Exception) {
            log.warn("admob verifier keys fetch failed cause={}", e.javaClass.simpleName)
            throw ReceiptVerifyFailure.TransientUpstream(0)
        }
        val parsed = try {
            AppJson.decodeFromString<VerifierKeysResponse>(body)
        } catch (e: Exception) {
            log.warn("admob verifier keys parse failed")
            throw ReceiptVerifyFailure.HttpFailure(200, "verifier keys parse")
        }
        val kf = KeyFactory.getInstance("EC")
        keysCache.set(
            parsed.keys.mapNotNull { k ->
                runCatching {
                    val der = Base64.getDecoder().decode(stripPem(k.pem))
                    k.keyId to (kf.generatePublic(X509EncodedKeySpec(der)) as ECPublicKey)
                }.getOrNull()
            }.toMap()
        )
    }

    @Serializable
    private data class VerifierKeysResponse(val keys: List<VerifierKey>)

    @Serializable
    private data class VerifierKey(val keyId: Long, val pem: String)

    private companion object {
        // Google 공개 검증키 — 고정 엔드포인트(키는 로테이션되지만 URL 은 불변). 환경변수로 뺄
        // 이유가 없어 상수.
        const val VERIFIER_KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json"

        // 캐시에 키가 있는데 미스인 경우의 최소 refetch 간격. 위조 key_id 스팸이 매 요청 upstream
        // fetch 를 유발하지 못하게 한다. 키 로테이션은 overlap 윈도우가 길어 이 지연을 견딘다.
        const val MIN_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        fun stripPem(pem: String): String = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")

        fun padBase64(s: String): String {
            val mod = s.length % 4
            return if (mod == 0) s else s + "=".repeat(4 - mod)
        }
    }
}
