package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * 크레딧 IAP 상품 카탈로그. App Store Connect / Play Console 의 productId 와 1:1.
 *
 * 본 상수는 클라이언트 ↔ BFF 단일 source — 클라이언트가 보낸 productId 가 [CreditCatalog]
 * 에 없으면 거부 (영수증 검증 통과해도 잔액 가산 안 함). 가격 라벨은 storefront 가 결정하므로
 * 본 BFF 카탈로그에는 두지 않는다 (지역/통화 차이는 OS 가 처리).
 */
object CreditCatalog {
    /**
     * productId → 가산 크레딧 수. 새 SKU 추가 시 본 맵 + App Store Connect / Play Console 동시 등록.
     * 모바일 [CreditProduct.DEFAULTS] (`vibi-mobile/shared`) 와도 동기.
     */
    val products: Map<String, Int> = mapOf(
        "vibi.credits.10" to 10,
        "vibi.credits.50" to 50,
        "vibi.credits.150" to 150,
    )

    fun creditsFor(productId: String): Int? = products[productId]
}

@Serializable
data class CreditBalanceResponse(
    val balance: Int,
)

/**
 * StoreKit2 / Play Billing 결제 영수증 검증 요청.
 *
 * - [productId] — App Store Connect / Play Console 에 등록된 SKU. [CreditCatalog] 와 cross-check.
 * - [platform] — "apple" | "google". 각자 다른 검증 endpoint 호출.
 * - [receipt] — StoreKit2 `Transaction.jsonRepresentation` (base64) 또는
 *   Play Billing `Purchase.purchaseToken`. BFF 가 그대로 Apple / Google 서버 검증 API 에 전달.
 *
 * v1 은 **검증 stub** — receipt 가 비어있지 않으면 통과로 간주하고 잔액 가산. 실서비스 출시 전
 * Apple App Store Server API (`/inApps/v1/transactions/{transactionId}`) 와 Google Play
 * Developer API (`purchases.products.get`) 호출로 교체 필요. TODO 주석으로 표시.
 */
@Serializable
data class CreditPurchaseRequest(
    val productId: String,
    val platform: String,
    val receipt: String,
    /** App Store/Play Store 의 unique transaction ID — idempotency key. */
    val transactionId: String,
)

/**
 * 결제 처리 결과.
 *
 * - [granted] — 이번 호출로 가산된 크레딧 수. **duplicate transactionId 면 0** (재호출 안전).
 * - [balance] — 가산 후 현재 잔액.
 */
@Serializable
data class CreditPurchaseResponse(
    val granted: Int,
    val balance: Int,
    val transactionId: String,
)
