package com.vibi.bff.service

import com.vibi.bff.db.CreditLedgerTable
import com.vibi.bff.db.CreditTransactionsTable
import com.vibi.bff.db.UserCreditsTable
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

/**
 * 신규 가입 시 자동 지급되는 보너스 크레딧. 크레딧당 1분 분리([CreditCost.BILLABLE_BLOCK_MS])
 * 이므로 10 크레딧 = 첫 사용 무료 10분. 변경 시 모바일 카피 + App Store 설명문도 함께 갱신.
 */
const val SIGNUP_BONUS_CREDITS: Int = 10

/**
 * 잔액 부족 시 [CreditRepository.reserve] 가 throw. 라우트가 잡아 402 (Payment Required) 로 매핑.
 * required/balance 는 모바일 팝업이 표시할 정확한 부족분 — sanitize 규약 위반 아님 (사용자
 * 본인의 잔액이라 노출 OK).
 */
class InsufficientCreditsException(
    val required: Int,
    val balance: Int,
) : RuntimeException("insufficient credits: required=$required balance=$balance")

/**
 * 잡 길이 → 크레딧 비용. **시작된 1분 블록당 1 크레딧** (`ceil(durationMs / 1분)`, 최소 1).
 * 라우트(선차감)와 `/credits/cost` endpoint (견적) 양쪽이 이 단일 source 를 쓰므로
 * 견적·차감·402 required 가 항상 같은 값. 모바일 앱과 Adobe 플러그인이 같은 BFF·같은 DB 를
 * 쓰므로 두 클라이언트의 분리 단가가 이 단일 공식으로 통일된다(분당 1 크레딧).
 *
 * 길이 비례인 이유: Perso 분리 API 원가가 분당 과금이라, 고정 1 크레딧이면 롱폼(영상·팟캐스트)
 * 에서 매출은 그대로인데 원가만 비례해 마진이 침식된다. 1분 블록 단위로 끊어 매출을 원가에
 * 정렬한다 — 크레딧당 변동원가 = 분당 Perso 원가(≈$0.01). durationMs 는
 * [com.vibi.bff.routes.computeSeparationSourceDurationMs] 가 ffprobe (실패 시 size 기반 보수
 * 추정) 로 산출하므로, corrupt-header 로 probe 만 막아 undercharge 하는 우회가 차단된다.
 *
 * **경계 grace ([BOUNDARY_GRACE_MS])**: 견적(`/credits/cost`)은 모바일 trim 윈도우 길이(clean
 * ms) 기준이지만 실제 차감은 업로드된 추출 m4a 의 ffprobe 길이 기준 — AAC 인코더 패딩으로
 * 후자가 수십~수백 ms 더 길게 측정될 수 있다. grace 없이는 정확히 분 배수 경계에서 견적 1
 * vs 차감 2 처럼 사용자가 동의한 것보다 더 빠지는 off-by-one 이 가능하다. block 경계를
 * [BOUNDARY_GRACE_MS] 만큼 뒤로 밀어 패딩을 흡수한다 (사용자에게 유리한 방향 — 블록당 최대 1초
 * 추가 무료).
 *
 * 경계: duration ≤ 0 (probe 완전 실패 / 0-byte) → floor 1 크레딧. (1분+1초) 까지 → 1,
 * (1분+1초)+1ms → 2.
 */
object CreditCost {
    /** 1 크레딧이 커버하는 audio 길이 = 1분. Perso 분당 원가(≈$0.01) = 크레딧당 변동원가. */
    const val BILLABLE_BLOCK_MS: Long = 60 * 1000L

    /**
     * block 경계 grace — 인코더 패딩으로 ffprobe 길이가 trim 윈도우보다 약간 길어 다음 블록으로
     * 넘어가는 off-by-one 차단. 1초는 일반 AAC 패딩(수십 ms)의 10배 이상 여유. 견적-차감 불일치
     * 방지가 목적이라 사용자에게 유리한(undercharge) 방향으로만 1초 흡수.
     */
    const val BOUNDARY_GRACE_MS: Long = 1_000L

    fun forSeparation(durationMs: Long): Int {
        if (durationMs <= 0L) return 1
        val billable = (durationMs - BOUNDARY_GRACE_MS).coerceAtLeast(1L)
        return ceil(billable.toDouble() / BILLABLE_BLOCK_MS).toInt().coerceAtLeast(1)
    }
}

/**
 * 잔액 관리 + 모든 변동 ledger 영속화.
 *
 * 두 ledger 분리:
 *   - [CreditTransactionsTable] — IAP 가산 (apple/google/admin).
 *   - [CreditLedgerTable]       — signup 보너스 + consume/refund.
 *
 * 잔액 source-of-truth 는 [UserCreditsTable.balance]. ledger 두 곳 어디서도 SUM 으로
 * 재구성하지 않는다 — race 안전성 위해 `SELECT ... FOR UPDATE` + UPDATE 로 단일 컬럼 갱신.
 *
 * 메서드:
 * - [balance] — 잔액 조회. row 가 없으면 0.
 * - [grantPurchase] — IAP 영수증 1건의 idempotent 가산.
 * - [grantSignupBonus] — 신규 가입 1회 보너스. ref_id="signup-<userId>" UNIQUE 로 중복 차단.
 * - [reserve] — 잡 시작 전 선차감. `SELECT ... FOR UPDATE` + 부족 시 throw +
 *   credit_ledger 에 consume row 기록. ref_id="consume-<jobId>" UNIQUE 라 같은 잡 두 번
 *   reserve 호출도 멱등 (두 번째는 첫 번째와 동일 ReserveOutcome 반환, 잔액 변화 없음).
 * - [refund] — 잡 실패 시 [reserve] 가 차감한 만큼 복원. ref_id="refund-<jobId>" UNIQUE 로
 *   이중 환불 차단.
 */
class CreditRepository {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    data class PurchaseOutcome(
        val granted: Int,
        val balance: Int,
    )

    data class ReserveOutcome(
        val charged: Int,
        val balance: Int,
    )

    fun balance(userId: UUID): Int = transaction {
        readBalance(userId)
    }

    /**
     * [userId] 가 [since] 이후 platform='admin' 으로 적립받은 크레딧 합. admin-grant 의 일일
     * 상한 enforcement 용 — admin JWT 가 유출/탈취되어도 무제한 자가 적립을 막는다. 잔액
     * mutating 이 아닌 단순 SUM 조회라 lock 불필요.
     */
    fun adminGrantedCreditsSince(userId: UUID, since: Instant): Int =
        grantedCreditsSince(userId, "admin", since)

    /**
     * [userId] 가 [since] 이후 platform='admob' 으로 적립받은 크레딧 합 (= 보상형 광고 시청 횟수,
     * 1회당 1 크레딧). 광고 보상의 일일 상한 enforcement 용 — 하루에 광고로 받을 수 있는 크레딧을
     * 제한해 서버 분리 비용 과지출/어뷰징을 막는다.
     */
    fun admobGrantedCreditsSince(userId: UUID, since: Instant): Int =
        grantedCreditsSince(userId, "admob", since)

    /**
     * [userId] 가 [since] 이후 특정 [platform] 으로 적립받은 크레딧 합. admin/admob 일일 상한
     * enforcement 가 공유. 잔액 mutating 이 아닌 단순 SUM 이라 lock 불필요.
     */
    private fun grantedCreditsSince(userId: UUID, platform: String, since: Instant): Int = transaction {
        CreditTransactionsTable
            .select(CreditTransactionsTable.credits)
            .where {
                (CreditTransactionsTable.userId eq userId) and
                    (CreditTransactionsTable.platform eq platform) and
                    (CreditTransactionsTable.createdAt greaterEq since)
            }
            .sumOf { it[CreditTransactionsTable.credits] }
    }

    /**
     * 영수증 1건의 idempotent 가산. 영수증 서버 검증 통과 직후 호출.
     *
     * `insertIgnore` 로 (platform, transactionId) UNIQUE 제약을 단일 statement 로 활용 —
     * 동시 호출 race 도 DB 가 처리 (select-then-insert 의 TOCTOU 없음). insertedCount 가 0
     * 이면 이미 처리된 영수증으로 간주하고 가산 skip.
     */
    fun grantPurchase(
        userId: UUID,
        platform: String,
        transactionId: String,
        productId: String,
        credits: Int,
    ): PurchaseOutcome = transaction {
        require(credits > 0) { "credits must be positive" }
        val now = Instant.now()

        val inserted = CreditTransactionsTable.insertIgnore {
            it[CreditTransactionsTable.userId] = userId
            it[CreditTransactionsTable.platform] = platform
            it[CreditTransactionsTable.transactionId] = transactionId
            it[CreditTransactionsTable.productId] = productId
            it[CreditTransactionsTable.credits] = credits
            it[CreditTransactionsTable.createdAt] = now
        }.insertedCount
        if (inserted == 0) {
            // (platform, transactionId) 충돌 — 이미 처리된 영수증. 같은 user 의 retry 면 정상
            // (멱등). 그러나 *다른* user 가 같은 transactionId 를 청구하면 영수증 공유/탈취
            // 시도일 수 있어 fraud 신호로 warn 로깅한다. 가산은 어느 쪽이든 skip (granted=0) —
            // 영수증 1건은 최초 청구자에게만 귀속되고 이중 적립은 없다.
            val ownerUserId = CreditTransactionsTable
                .select(CreditTransactionsTable.userId)
                .where {
                    (CreditTransactionsTable.platform eq platform) and
                        (CreditTransactionsTable.transactionId eq transactionId)
                }
                .singleOrNull()
                ?.get(CreditTransactionsTable.userId)
            if (ownerUserId != null && ownerUserId != userId) {
                log.warn(
                    "credit purchase cross-user replay: tx={} platform={} claimedBy={} originalOwner={} — granting 0",
                    transactionId, platform, userId, ownerUserId,
                )
            }
            return@transaction PurchaseOutcome(granted = 0, balance = readBalance(userId))
        }

        addToBalance(userId, credits, now)
        PurchaseOutcome(granted = credits, balance = readBalance(userId))
    }

    /**
     * 신규 가입 보너스 — [SIGNUP_BONUS_CREDITS] 만큼 1회 가산. (kind='signup', ref_id="signup-<userId>")
     * UNIQUE 라 같은 사용자에 대해 두 번 호출되어도 두 번째는 granted=0 으로 멱등.
     *
     * 호출자: [AuthService.completeSignIn] 가 [UpsertedUser.isNewUser] == true 일 때만.
     * UserRepository 의 race 케이스에서 isNewUser 가 false-negative 일 수 있어도 본 메서드
     * 자체는 idempotent — 안전망 한 겹 더 (false-positive 도 무해).
     */
    fun grantSignupBonus(userId: UUID): PurchaseOutcome = transaction {
        val refId = "signup-$userId"
        val now = Instant.now()
        val inserted = CreditLedgerTable.insertIgnore {
            it[CreditLedgerTable.userId] = userId
            it[CreditLedgerTable.kind] = "signup"
            it[CreditLedgerTable.refId] = refId
            it[CreditLedgerTable.credits] = SIGNUP_BONUS_CREDITS
            it[CreditLedgerTable.createdAt] = now
        }.insertedCount
        if (inserted == 0) {
            return@transaction PurchaseOutcome(granted = 0, balance = readBalance(userId))
        }
        addToBalance(userId, SIGNUP_BONUS_CREDITS, now)
        PurchaseOutcome(granted = SIGNUP_BONUS_CREDITS, balance = readBalance(userId))
    }

    /**
     * 잡 시작 전 선차감. atomic 흐름:
     *   1) (kind='consume', ref_id="consume-<jobId>") 멱등 체크 — 이미 차감됐으면 그 결과 반환
     *   2) `SELECT balance FROM user_credits WHERE user_id=? FOR UPDATE` — row 잠금
     *   3) 부족하면 [InsufficientCreditsException]
     *   4) 충분하면 balance -= [credits] + ledger row insert
     *
     * **멱등 보장**: 같은 [jobId] 로 두 번 호출하면 두 번째는 첫 번째 결과를 그대로 반환 —
     * 잔액 두 번 빠지지 않음. 라우트 핸들러가 멱등 재시도(같은 dedupKey 로 두 번째 요청)해도
     * 안전. **단** SELECT 의 결과로 consume 존재 확인은 동일 트랜잭션 안 — UNIQUE 가
     * insert-time 안전망.
     *
     * `SELECT ... FOR UPDATE` 가 row 가 없으면 잠글 게 없어 차감 불가 — user_credits row 부재 시
     * (가입 직후 보너스 grant 도 안 받은 사용자) InsufficientCreditsException(required, 0).
     */
    fun reserve(
        userId: UUID,
        jobId: String,
        credits: Int,
    ): ReserveOutcome = transaction {
        require(credits > 0) { "credits must be positive" }
        val refId = "consume-$jobId"
        val now = Instant.now()

        // 멱등 check: 같은 jobId 가 이미 차감됐으면 다시 차감하지 않음 (UNIQUE 가 한 번 더
        // 막아주지만 명시 분기로 InsufficientCredits 가 잘못 뜨지 않게 함).
        val alreadyConsumed = CreditLedgerTable
            .select(CreditLedgerTable.credits)
            .where {
                (CreditLedgerTable.kind eq "consume") and
                    (CreditLedgerTable.refId eq refId)
            }
            .singleOrNull()
        if (alreadyConsumed != null) {
            return@transaction ReserveOutcome(
                charged = alreadyConsumed[CreditLedgerTable.credits],
                balance = readBalance(userId),
            )
        }

        // FOR UPDATE row lock — 같은 사용자의 두 동시 reserve 가 직렬화. 다른 사용자는 영향 X.
        val current = UserCreditsTable
            .select(UserCreditsTable.balance)
            .where { UserCreditsTable.userId eq userId }
            .forUpdate()
            .singleOrNull()
            ?.get(UserCreditsTable.balance)
            ?: 0

        if (current < credits) {
            throw InsufficientCreditsException(required = credits, balance = current)
        }

        UserCreditsTable.update({ UserCreditsTable.userId eq userId }) {
            with(SqlExpressionBuilder) {
                it[UserCreditsTable.balance] = UserCreditsTable.balance - credits
            }
            it[UserCreditsTable.updatedAt] = now
        }

        CreditLedgerTable.insertIgnore {
            it[CreditLedgerTable.userId] = userId
            it[CreditLedgerTable.kind] = "consume"
            it[CreditLedgerTable.refId] = refId
            it[CreditLedgerTable.credits] = credits
            it[CreditLedgerTable.createdAt] = now
        }

        ReserveOutcome(charged = credits, balance = readBalance(userId))
    }

    /**
     * [reserve] 가 차감한 만큼 잔액 복원. 잡 실패/취소 시 호출.
     *
     * **이중 환불 차단**: refund ledger UNIQUE (kind='refund', ref_id="refund-<jobId>") 가
     * 두 번째 환불을 막는다 (insertIgnore → insertedCount=0). 동일 잡이 두 번 환불 트리거를
     * 발생시켜도 잔액은 정확히 한 번만 복원.
     *
     * consume row 가 없으면 (애초에 차감되지 않은 잡 — 미인증 caller 분기 등) no-op (null).
     * 미인증 / 무료 잡 분기에서 환불 콜백이 무해하게 통과.
     */
    fun refund(jobId: String): PurchaseOutcome? = transaction {
        val consume = CreditLedgerTable
            .select(
                CreditLedgerTable.userId,
                CreditLedgerTable.credits,
            )
            .where {
                (CreditLedgerTable.kind eq "consume") and
                    (CreditLedgerTable.refId eq "consume-$jobId")
            }
            .singleOrNull()
            ?: return@transaction null

        val userId = consume[CreditLedgerTable.userId] ?: return@transaction null
        val credits = consume[CreditLedgerTable.credits]
        val now = Instant.now()

        val inserted = CreditLedgerTable.insertIgnore {
            it[CreditLedgerTable.userId] = userId
            it[CreditLedgerTable.kind] = "refund"
            it[CreditLedgerTable.refId] = "refund-$jobId"
            it[CreditLedgerTable.credits] = credits
            it[CreditLedgerTable.createdAt] = now
        }.insertedCount
        if (inserted == 0) {
            // 이미 환불됨 — 잔액은 그대로.
            return@transaction PurchaseOutcome(granted = 0, balance = readBalance(userId))
        }

        addToBalance(userId, credits, now)
        PurchaseOutcome(granted = credits, balance = readBalance(userId))
    }

    /**
     * user_credits row 가 없으면 [credits] 로 새로 만들고, 있으면 더한다. updated_at 도 갱신.
     * grantPurchase / grantSignupBonus / refund 가 공유.
     */
    private fun addToBalance(userId: UUID, credits: Int, now: Instant) {
        UserCreditsTable.upsert(
            UserCreditsTable.userId,
            onUpdate = {
                with(SqlExpressionBuilder) {
                    it[UserCreditsTable.balance] = UserCreditsTable.balance + credits
                }
                it[UserCreditsTable.updatedAt] = now
            },
        ) {
            it[UserCreditsTable.userId] = userId
            it[UserCreditsTable.balance] = credits
            it[UserCreditsTable.updatedAt] = now
        }
    }

    private fun readBalance(userId: UUID): Int =
        UserCreditsTable
            .select(UserCreditsTable.balance)
            .where { UserCreditsTable.userId eq userId }
            .singleOrNull()
            ?.get(UserCreditsTable.balance)
            ?: 0
}
