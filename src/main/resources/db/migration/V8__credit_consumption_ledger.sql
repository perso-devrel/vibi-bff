-- credit_ledger — IAP 외 잔액 변동 (signup 보너스 + 잡 소비/환불) 추적.
--
-- 별도 테이블로 만든 이유: V5 의 credit_transactions.platform CHECK 가 ('apple','google')
-- 만 허용하는데, 그 CHECK 의 auto-generated 이름이 H2 vs Postgres 가 달라 (Postgres 는
-- 규약명 credit_transactions_platform_check, H2 는 CONSTRAINT_<hash>) 단일 ALTER 로
-- 확장 불가능. CHECK 를 dynamic discovery 로 dropping 하는 Flyway Java migration 까지 갈
-- 가치는 없어 — 별도 테이블이 schema 도 더 깔끔 (IAP ledger vs 내부 사용 ledger 분리).
--
-- kind 종류:
--   signup  — 신규 가입 1회 보너스. ref_id = "signup-<userId>" UNIQUE 로 중복 가산 차단.
--   consume — /separate 잡 시작 시 차감. ref_id = "consume-<jobId>" UNIQUE.
--   refund  — 잡 실패/취소 시 환불. ref_id = "refund-<jobId>" UNIQUE (이중 환불 차단).
--
-- credits 는 항상 양수 magnitude. 잔액 방향은 kind 로 판별 (signup/refund 는 +, consume 은 -).
-- 잔액 source-of-truth 는 user_credits.balance — ledger SUM 으로 재구성하지 않음 (race 안전성).

CREATE TABLE credit_ledger (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    kind       VARCHAR(16) NOT NULL CHECK (kind IN ('signup', 'consume', 'refund')),
    ref_id     VARCHAR(64) NOT NULL,
    credits    INTEGER NOT NULL CHECK (credits > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (kind, ref_id)
);

CREATE INDEX credit_ledger_user_idx ON credit_ledger (user_id, created_at DESC);

-- ── 기존 사용자 backfill ─────────────────────────────────────────────────────
-- V8 적용 전에 가입한 사용자들에게도 신규 가입 보너스 (3 크레딧) 일괄 지급. backfill
-- 없으면 TestFlight 베타 테스터 등 기존 모든 사용자가 첫 /separate 호출 시 곧장 402 로
-- 떨어져 사용자 경험 망가짐.
--
-- ref_id 가 'signup-<userId>' 로 신규 가입 grantSignupBonus 와 동일 키 — 본 마이그레이션
-- 으로 row 가 들어간 사용자가 추후 (어쩌다) 신규 가입 분기로 들어가도 UNIQUE 가 막아
-- 중복 가산 차단. credit_ledger 는 본 마이그레이션 직전까지 테이블 자체가 없었으므로
-- ON CONFLICT 없이 단일 INSERT 로 충분 (Flyway 가 1회 실행 보장).
--
-- 이미 IAP 결제 / admin-grant 로 잔액이 있는 사용자도 +3 받음 — 사용자에게 이득이고 (불만
-- 없음) 회계 정확성 측면에서는 모두 동일 보너스 ledger row 보유 (감사 일관성).
--
-- 보너스 크레딧 수는 src/main/kotlin/com/vibi/bff/service/CreditRepository.kt 의
-- SIGNUP_BONUS_CREDITS 상수와 동기 — 두 값 변경 시 V9 마이그레이션으로 retro-adjust.

-- credit_ledger 는 본 V8 직전까지 테이블 자체가 없었으므로 ON CONFLICT 없이 단일 INSERT
-- 로 충분. UUID → text concatenation 은 explicit CAST 로 H2/Postgres 양쪽 안전.
INSERT INTO credit_ledger (user_id, kind, ref_id, credits, created_at)
SELECT id, 'signup', 'signup-' || CAST(id AS VARCHAR), 3, CURRENT_TIMESTAMP
FROM users;

-- user_credits 잔액 가산은 두 단계 — H2 PostgreSQL mode 가 `INSERT ... SELECT ... ON CONFLICT`
-- 조합을 지원 안 해서 (`INSERT ... VALUES ... ON CONFLICT` 만 가능). Postgres 도 동일 SQL 로
-- 동작하도록 단순 INSERT (row 없는 사용자만) + UPDATE (모든 기존 사용자) 분리:
INSERT INTO user_credits (user_id, balance, updated_at)
SELECT id, 0, CURRENT_TIMESTAMP FROM users
WHERE NOT EXISTS (
    SELECT 1 FROM user_credits WHERE user_credits.user_id = users.id
);

UPDATE user_credits
SET balance = balance + 3, updated_at = CURRENT_TIMESTAMP
WHERE user_id IN (SELECT id FROM users);
