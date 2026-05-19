package com.vibi.bff.routes

import com.vibi.bff.model.AdminGrantRequest
import com.vibi.bff.model.CreditBalanceResponse
import com.vibi.bff.model.CreditCatalog
import com.vibi.bff.model.CreditPurchaseRequest
import com.vibi.bff.model.CreditPurchaseResponse
import com.vibi.bff.plugins.ApiErrorException
import com.vibi.bff.plugins.requireAdmin
import com.vibi.bff.plugins.requireUser
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.iap.AppleReceiptVerifier
import com.vibi.bff.service.iap.GoogleReceiptVerifier
import com.vibi.bff.service.iap.ReceiptVerifyFailure
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CreditRoutes")

/**
 * 크레딧 잔액 조회 + IAP 영수증 가산 + 관리자 무료 충전.
 *
 * - `GET /credits` — 인증된 사용자의 현재 잔액.
 * - `POST /credits/purchase` — StoreKit2 / Play Billing 영수증을 실제 Apple/Google 서버로 검증
 *   ([AppleReceiptVerifier] / [GoogleReceiptVerifier]) 후 잔액 가산. **검증 실패 / verifier 미설정은
 *   모두 외부에 `receipt_invalid` 로만 노출** (sanitize 규약 — 환불 vs 잘못된 productId 같은
 *   세부 사유를 클라이언트에 흘리지 않음).
 * - `POST /credits/admin-grant` — admin role 만 호출 가능, 영수증 검증 없이 잔액 가산. 운영자
 *   테스트·시연용. `(platform="admin", transactionId=서버 UUID)` 로 영수증 경로와 같은
 *   idempotency UNIQUE 를 그대로 활용 — 중복 grant 는 없지만 동일 row 가 transactions
 *   감사 로그에 남음.
 */
fun Route.creditRoutes(
    creditRepository: CreditRepository,
    appleVerifier: AppleReceiptVerifier?,
    googleVerifier: GoogleReceiptVerifier?,
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

            // Receipt 검증 — verifier 미설정 시 stub 통과 없이 명시 400. 검증 실패 메시지는 모두
            // 단일 외부 메시지로 매핑 (내부 로그에만 상세).
            try {
                when (platform) {
                    "apple" -> {
                        val v = appleVerifier
                            ?: throw ApiErrorException(HttpStatusCode.BadRequest, "iap_unconfigured")
                        v.verify(transactionId = req.transactionId, expectedProductId = req.productId)
                    }
                    "google" -> {
                        val v = googleVerifier
                            ?: throw ApiErrorException(HttpStatusCode.BadRequest, "iap_unconfigured")
                        v.verify(
                            purchaseToken = req.receipt,
                            expectedProductId = req.productId,
                            expectedOrderId = req.transactionId,
                        )
                    }
                }
            } catch (e: ReceiptVerifyFailure.TransientUpstream) {
                // Apple/Google 일시 장애 — 클라이언트가 재시도해도 결과 동일할 수 있어 502 가 정직.
                // 단 idempotency 보장된 후속 호출이 동일 transactionId 로 들어오면 통과 가능.
                log.warn("receipt verify transient user={} tx={} status={}", principal.userId, req.transactionId, e.status)
                throw ApiErrorException(HttpStatusCode.BadGateway, "receipt_verify_unavailable")
            } catch (e: ReceiptVerifyFailure) {
                log.warn("receipt verify failed user={} tx={} cause={}", principal.userId, req.transactionId, e.message)
                throw ApiErrorException(HttpStatusCode.BadRequest, "receipt_invalid")
            }

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

        // 관리자 무료 충전 — receipt 검증 없이 잔액 가산. requireAdmin 가드.
        // 동일 사용자가 같은 productId 를 반복 호출하면 매번 새 transactionId (서버 UUID) 라
        // 매번 가산되는 동작이 의도 (운영자 테스트 시 부족하면 다시 누름).
        post("/admin-grant") {
            val principal = call.requireAdmin(jwtSecret)
            val req = call.receive<AdminGrantRequest>()
            val credits = CreditCatalog.creditsFor(req.productId)
                ?: throw ApiErrorException(HttpStatusCode.BadRequest, "unknown_product")

            val txId = "admin-${UUID.randomUUID()}"
            val outcome = withContext(Dispatchers.IO) {
                creditRepository.grantPurchase(
                    userId = principal.userId,
                    platform = "admin",
                    transactionId = txId,
                    productId = req.productId,
                    credits = credits,
                )
            }
            log.info(
                "admin grant: user={} tx={} product={} +{}",
                principal.userId, txId, req.productId, outcome.granted,
            )
            call.respond(
                HttpStatusCode.OK,
                CreditPurchaseResponse(
                    granted = outcome.granted,
                    balance = outcome.balance,
                    transactionId = txId,
                )
            )
        }
    }
}

private val ALLOWED_PLATFORMS = setOf("apple", "google")
