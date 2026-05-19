package com.vibi.bff.service.iap

import com.google.auth.oauth2.GoogleCredentials
import com.vibi.bff.config.GoogleIapConfig
import com.vibi.bff.plugins.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.encodeURLPath
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Google Play Developer API (`purchases.products.get`) 로 in-app product 구매 검증.
 *
 * 흐름:
 * 1. Service account JSON 으로 [GoogleCredentials] 인스턴스 생성, scope `androidpublisher`.
 *    refresh 는 라이브러리가 자체 캐싱·만료 검사.
 * 2. `GET /androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{purchaseToken}`
 *    호출 (Bearer access_token).
 * 3. 응답 [ProductPurchase] 검증:
 *    - `purchaseState == 0` (0=purchased, 1=cancelled, 2=pending)
 *    - `orderId` non-blank (Google 영수증 신뢰성 1차 표시)
 *
 * receipt 형식: 모바일이 `receipt` 필드에 Play Billing 의 `Purchase.purchaseToken` 을 박아
 * 보낸다는 가정. transactionId 는 `Purchase.orderId`.
 *
 * **현 시점 주의**: Android 측 `PurchaseLauncher.android.kt` 가 아직 mock 이라 본 검증 경로는
 * 실 호출되지 않는다 — 본 구현은 후속 작업에서 Play Billing 실연동 후 즉시 동작하도록 미리
 * 마련해 둔 것. Google IAP config 미설정 시 라우트에서 명시 거부 → stub 통과 없음.
 */
class GoogleReceiptVerifier(
    private val config: GoogleIapConfig,
    private val httpClient: HttpClient,
) {

    private val log = LoggerFactory.getLogger("GoogleReceiptVerifier")

    private val credentials: GoogleCredentials = GoogleCredentials
        .fromStream(config.serviceAccountJson.byteInputStream())
        .createScoped(listOf("https://www.googleapis.com/auth/androidpublisher"))

    suspend fun verify(purchaseToken: String, expectedProductId: String, expectedOrderId: String) {
        require(purchaseToken.isNotBlank()) { "purchaseToken must not be blank" }
        require(expectedProductId.isNotBlank()) { "expectedProductId must not be blank" }
        require(expectedOrderId.isNotBlank()) { "expectedOrderId must not be blank" }

        val token = withContext(Dispatchers.IO) {
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }

        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
            "${config.packageName.encodeURLPath()}/purchases/products/" +
            "${expectedProductId.encodeURLPath()}/tokens/${purchaseToken.encodeURLPath()}"

        val response = try {
            httpClient.get {
                url {
                    takeFrom(url)
                    protocol = URLProtocol.HTTPS
                }
                bearerAuth(token)
            }
        } catch (e: Exception) {
            log.warn("google verify network failure order={} cause={}", expectedOrderId, e.javaClass.simpleName)
            throw ReceiptVerifyFailure.HttpFailure(0, e.message)
        }

        val status = response.status.value
        if (status >= 500) {
            log.warn("google verify 5xx order={} status={}", expectedOrderId, status)
            throw ReceiptVerifyFailure.TransientUpstream(status)
        }
        if (status !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            log.warn("google verify non-2xx order={} status={} body_head={}", expectedOrderId, status, body?.take(120))
            throw ReceiptVerifyFailure.HttpFailure(status, body)
        }

        val purchase = AppJson.decodeFromString<ProductPurchase>(response.bodyAsText())
        if (purchase.purchaseState != 0) {
            throw ReceiptVerifyFailure.PayloadMismatch(
                "purchaseState", "0(purchased)", purchase.purchaseState.toString(),
            )
        }
        val orderId = purchase.orderId.orEmpty()
        if (orderId != expectedOrderId) {
            throw ReceiptVerifyFailure.PayloadMismatch(
                "orderId", expectedOrderId, orderId,
            )
        }
    }

    @Serializable
    private data class ProductPurchase(
        /** 0=Purchased, 1=Cancelled, 2=Pending */
        @SerialName("purchaseState") val purchaseState: Int = -1,
        @SerialName("orderId") val orderId: String? = null,
        @SerialName("consumptionState") val consumptionState: Int? = null,
        @SerialName("productId") val productId: String? = null,
    )
}
