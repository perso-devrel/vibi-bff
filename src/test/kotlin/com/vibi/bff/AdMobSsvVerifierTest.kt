package com.vibi.bff

import com.vibi.bff.service.iap.AdMobSsvVerifier
import com.vibi.bff.service.iap.ReceiptVerifyFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * AdMob SSV 서명 검증 단위테스트. 실제 Google 키 대신 로컬에서 P-256 키쌍을 만들어 콜백을
 * 서명하고, 검증키 JSON 을 [MockEngine] 으로 돌려준다 — [AdMobSsvVerifier] 의 ECDSA-P256 서명
 * 검증 + 쿼리 파싱 경로(SSV 보안의 핵심)를 외부 의존 없이 검증한다.
 */
class AdMobSsvVerifierTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val keyId = 1234567890L

    /** 공개키를 Google verifier-keys.json 과 동일한 형태(keyId + X.509 PEM)로 직렬화. */
    private fun keysJson(): String {
        val b64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\\n$b64\\n-----END PUBLIC KEY-----"
        return """{"keys":[{"keyId":$keyId,"pem":"$pem"}]}"""
    }

    /** content 를 개인키로 ECDSA-SHA256 서명 → base64url(no-pad). */
    private fun sign(content: String): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(content.toByteArray(Charsets.US_ASCII))
        }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private fun verifier(): AdMobSsvVerifier {
        val engine = MockEngine {
            respond(keysJson(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AdMobSsvVerifier(HttpClient(engine))
    }

    // AdMob 규약: signature·key_id 가 마지막 두 파라미터(그 순서), 그 앞이 서명 대상 content.
    private fun content(userId: String, txId: String) =
        "ad_network=494954c14ff03b09&ad_unit=9999&reward_amount=1&reward_item=credit" +
            "&timestamp=1700000000000&transaction_id=$txId&user_id=$userId"

    @Test
    fun `valid signature passes and returns parsed params`() = runBlocking {
        val userId = "11111111-2222-3333-4444-555555555555"
        val txId = "tx-abc"
        val c = content(userId, txId)
        val raw = "$c&signature=${sign(c)}&key_id=$keyId"

        val params = verifier().verify(raw)

        assertEquals(userId, params["user_id"])
        assertEquals(txId, params["transaction_id"])
    }

    @Test
    fun `tampered content fails verification`() = runBlocking {
        val c = content("11111111-2222-3333-4444-555555555555", "tx-abc")
        val signature = sign(c)
        // 서명은 원본 content 로 만들고, 전송되는 content 의 user_id 를 다른 값으로 바꿔치기.
        val tampered = content("99999999-9999-9999-9999-999999999999", "tx-abc")
        val raw = "$tampered&signature=$signature&key_id=$keyId"

        assertFailsWith<ReceiptVerifyFailure.PayloadMismatch> {
            verifier().verify(raw)
        }
    }

    @Test
    fun `missing signature fails`() = runBlocking {
        val c = content("11111111-2222-3333-4444-555555555555", "tx-abc")
        assertFailsWith<ReceiptVerifyFailure.PayloadMismatch> {
            verifier().verify(c) // no &signature=
        }
    }

    @Test
    fun `unknown key_id fails`() = runBlocking {
        val c = content("11111111-2222-3333-4444-555555555555", "tx-abc")
        val raw = "$c&signature=${sign(c)}&key_id=999" // 검증키 JSON 에 없는 keyId

        assertFailsWith<ReceiptVerifyFailure.PayloadMismatch> {
            verifier().verify(raw)
        }
    }
}
