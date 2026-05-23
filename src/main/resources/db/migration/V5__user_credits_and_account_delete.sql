-- user_credits — 사용자별 크레딧 잔액.
--
-- StoreKit / Play Billing 영수증 검증 후 가산되는 IAP 구매 잔액. consume() 시 row 잠금
-- (`SELECT ... FOR UPDATE`) 으로 race condition 차단. 1 row / user — 거래 내역은 별도
-- credit_transactions (v2) 에서 추적 예정.
--
-- ON DELETE CASCADE — 회원탈퇴 시 잔액 row 도 함께 사라진다. 환불은 App Store / Play
-- Console 측 영수증 흐름으로 처리해야 하므로 BFF row 만으로 보존할 가치 없음.
CREATE TABLE user_credits (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- credit_transactions — IAP 영수증 1건 = 1 row. (platform, transactionId) UNIQUE 로 재시도
-- 중복 가산 차단. user_id 는 SET NULL (회원탈퇴해도 결제 이력은 보존 — 환불/감사 추적용).
CREATE TABLE credit_transactions (
    id             BIGSERIAL PRIMARY KEY,
    user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    platform       VARCHAR(16) NOT NULL CHECK (platform IN ('apple', 'google')),
    transaction_id VARCHAR(255) NOT NULL,
    product_id     VARCHAR(128) NOT NULL,
    credits        INTEGER NOT NULL CHECK (credits > 0),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (platform, transaction_id)
);

CREATE INDEX credit_transactions_user_idx ON credit_transactions (user_id, created_at DESC);

-- 회원탈퇴 후에도 사용량 분석은 익명 row 로 보존 (KPI 연속성 + 외부 API 비용 추정).
--
-- 기존 (V3) FK 는 ON DELETE 옵션 없음 (= NO ACTION) 이라 user 행 삭제가 FK 위반으로 실패.
-- 회원탈퇴 흐름이 동작하려면 cascade 또는 set null 중 하나 필요 — 분석 row 보존 위해 SET NULL.
-- user_id NULL 인 row 는 admin 대시보드에서 "탈퇴 사용자" bucket 으로 합산.

ALTER TABLE render_jobs ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE render_jobs DROP CONSTRAINT IF EXISTS render_jobs_user_id_fkey;
ALTER TABLE render_jobs ADD CONSTRAINT render_jobs_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE separation_jobs ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE separation_jobs DROP CONSTRAINT IF EXISTS separation_jobs_user_id_fkey;
ALTER TABLE separation_jobs ADD CONSTRAINT separation_jobs_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
