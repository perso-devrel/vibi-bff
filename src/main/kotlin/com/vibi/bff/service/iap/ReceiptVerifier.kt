package com.vibi.bff.service.iap

/**
 * Receipt 검증 실패 사유. 라우트가 모두 동일한 외부 메시지(`receipt_invalid`) 로 매핑해 외부에는
 * 상세 사유를 노출하지 않는다 (sanitize 규약). 내부 로그/메트릭 분류용으로만 의미 있음.
 *
 * - [HttpFailure] — Apple/Google 서버가 4xx/5xx 응답. 만료 영수증·존재하지 않는 transactionId
 *   등은 보통 4xx. 5xx 는 일시 장애로 라우트에서 BadGateway 로 분리할 수도 있음 (현 v1 은
 *   모두 BadRequest 로 통일).
 * - [PayloadMismatch] — 서버는 응답 줬지만 bundleId / productId / revocation / state 가 기대와
 *   불일치. 클라이언트가 다른 앱의 영수증을 보냈거나 환불된 transaction 일 때 발생.
 */
sealed class ReceiptVerifyFailure(message: String) : RuntimeException(message) {
    class HttpFailure(val status: Int, body: String?) :
        ReceiptVerifyFailure("upstream http $status: ${body?.take(200)}")
    class PayloadMismatch(val field: String, val expected: String, val actual: String) :
        ReceiptVerifyFailure("payload mismatch: $field expected=$expected actual=$actual")
    class TransientUpstream(val status: Int) :
        ReceiptVerifyFailure("upstream transient $status")
}
