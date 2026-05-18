package com.vibi.bff.routes

import com.vibi.bff.model.CreditBalanceResponse
import com.vibi.bff.model.CreditCatalog
import com.vibi.bff.model.CreditPurchaseRequest
import com.vibi.bff.model.CreditPurchaseResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.CreditRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CreditRoutes")

/**
 * 크레딧 잔액 조회 + IAP 영수증 가산.
 *
 * - `GET /credits` — 인증된 사용자의 현재 잔액.
 * - `POST /credits/purchase` — StoreKit2 / Play Billing 영수증 검증 + 잔액 가산.
 *
 * **영수증 검증 stub**: v1 은 receipt 가 비어있지 않으면 통과로 간주한다. 출시 전 반드시
 * [verifyReceiptStub] 를 실제 Apple App Store Server API / Google Play Developer API
 * 호출로 교체할 것 — 그렇지 않으면 클라이언트가 임의 영수증으로 잔액 충전 가능.
 */
fun Route.creditRoutes(
    creditRepository: CreditRepository,
    jwtSecret: String,
) {
    route("/credits") {
        get {
            val principal = call.requireUser(jwtSecret)
            val balance = withContext(Dispatchers.IO) { creditRepository.balance(principal.userId) }
            call.respond(CreditBalanceResponse(balance = balance))
        }

        post("/purchase") {
            val principal = call.requireUser(jwtSecret)
            val req = call.receive<CreditPurchaseRequest>()

            val platform = req.platform.lowercase().trim()
            if (platform !in ALLOWED_PLATFORMS) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "invalid_platform")
            }
            if (req.transactionId.isBlank() || req.receipt.isBlank()) {
                throw ApiErrorException(HttpStatusCode.BadRequest, "missing_receipt")
            }
            val credits = CreditCatalog.creditsFor(req.productId)
                ?: throw ApiErrorException(HttpStatusCode.BadRequest, "unknown_product")

            // TODO(IAP): replace with real receipt verification:
            //   - Apple: POST https://api.storekit.itunes.apple.com/inApps/v1/transactions/{transactionId}
            //            (JWT-signed App Store Server API). Verify productId / appAccountToken /
            //            originalTransactionId / revocationDate.
            //   - Google: GET https://androidpublisher.googleapis.com/androidpublisher/v3/
            //            applications/{packageName}/purchases/products/{productId}/tokens/{purchaseToken}
            //            (service account auth). Verify purchaseState=0 (purchased) / acknowledgementState.
            // 실패 시 ApiErrorException(BadRequest, "receipt_invalid") 로 통일.
            verifyReceiptStub(platform = platform, productId = req.productId, receipt = req.receipt)

            val outcome = withContext(Dispatchers.IO) {
                creditRepository.grantPurchase(
                    userId = principal.userId,
                    platform = platform,
                    transactionId = req.transactionId,
                    productId = req.productId,
                    credits = credits,
                )
            }
            if (outcome.granted == 0) {
                log.info("credit purchase duplicate: user={} tx={}", principal.userId, req.transactionId)
            } else {
                log.info(
                    "credit purchase granted: user={} tx={} product={} +{}",
                    principal.userId, req.transactionId, req.productId, outcome.granted,
                )
            }
            call.respond(
                HttpStatusCode.OK,
                CreditPurchaseResponse(
                    granted = outcome.granted,
                    balance = outcome.balance,
                    transactionId = req.transactionId,
                )
            )
        }
    }
}

private val ALLOWED_PLATFORMS = setOf("apple", "google")

/**
 * v1 stub — receipt 가 비어있지 않으면 통과. 출시 전 실제 StoreKit / Play 영수증 검증으로 대체.
 *
 * 실제 구현 시 throw [ApiErrorException] 로 검증 실패를 알리고, 절대 외부 메시지를 detail
 * 에 그대로 노출하지 말 것 (sanitization 가이드라인).
 */
private fun verifyReceiptStub(platform: String, productId: String, receipt: String) {
    // intentional no-op — placeholder so call sites already match the eventual signature.
}
