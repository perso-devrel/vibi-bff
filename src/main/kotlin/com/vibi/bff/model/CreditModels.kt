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
 * 음원 분리 비용 견적 — 모바일 확인 팝업 ("X 크레딧 사용, 잔액 Y → Z, 진행?") 표시용.
 *
 * - [durationMs] — 견적 기준 입력 길이 (요청에서 echo back).
 * - [credits]    — [CreditCost.forSeparation] 결과. 현재 정책: 시작된 1분당 1 크레딧 (ceil).
 * - [balance]    — 호출 시점 잔액.
 * - [sufficient] — balance >= credits 여부. false 면 모바일은 "충전 필요" UI 분기.
 */
@Serializable
data class CreditCostResponse(
    val durationMs: Long,
    val credits: Int,
    val balance: Int,
    val sufficient: Boolean,
)

/**
 * StoreKit2 / Play Billing 결제 영수증 검증 요청.
 *
 * - [productId] — App Store Connect / Play Console 에 등록된 SKU. [CreditCatalog] 와 cross-check.
 * - [platform] — "apple" | "google". 각자 다른 검증 endpoint 호출.
 * - [receipt] — Apple 일 때는 transactions endpoint 가 transactionId 만 받으므로 검증에 직접 사용
 *   하지 않지만 (`Transaction.jsonRepresentation` base64 를 그대로 보내도 무관) BFF 는 미사용.
 *   Google 일 때는 Play Billing `Purchase.purchaseToken`. BFF 가 Google API 호출에 그대로 사용.
 * - [transactionId] — Apple `Transaction.id` / Google `Purchase.orderId`. idempotency UNIQUE
 *   key + 검증 양쪽에 사용.
 *
 * 검증 흐름: 라우트가 [com.vibi.bff.service.iap.AppleReceiptVerifier] /
 * [com.vibi.bff.service.iap.GoogleReceiptVerifier] 로 Apple/Google 서버 직접 호출 후 통과해야
 * 잔액 가산. 실패 시 외부 메시지는 `receipt_invalid` 로 단일화.
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
 * 관리자 무료 충전 요청. body 는 [productId] 만 — txId 는 서버가 생성 (admin-<UUID>) 해
 * 매 호출마다 새 idempotency key 로 가산된다. 운영자 테스트·시연용.
 */
@Serializable
data class AdminGrantRequest(
    val productId: String,
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
