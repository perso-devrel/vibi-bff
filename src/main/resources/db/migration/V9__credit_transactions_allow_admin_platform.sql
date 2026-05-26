-- credit_transactions.platform CHECK 를 ('apple','google','admin') 으로 확장.
--
-- V5 가 ('apple','google') 만 허용하도록 inline CHECK 로 만들었는데, admin-grant
-- 라우트 (CreditRoutes.kt:158) 가 platform='admin' 으로 grantPurchase 를 호출해
-- prod Postgres 에서 `credit_transactions_platform_check` 위반 → 500 ServerError.
--
-- V8 는 이 문제를 우회하려고 credit_ledger 별도 테이블을 만들었지만 admin 경로는
-- 여전히 credit_transactions 를 사용. ledger 의 의도 (IAP audit trail) 와도 일치
-- 하므로 (admin grant 는 IAP 우회로 발급된 회계 row), CHECK 자체를 확장하는 게
-- 자연스러움.
--
-- Postgres: V5 의 inline CHECK 는 'credit_transactions_platform_check' 규약명으로
-- 생성됨. DROP IF EXISTS 로 안전하게 제거 후 동일 명으로 재생성.
--
-- H2 (test): 같은 inline CHECK 가 CONSTRAINT_<HASH> 로 생성돼 DROP IF EXISTS 가
-- match 안 됨 → no-op. ADD 는 성공해 새 named 제약이 추가되지만, 원래 hash-named
-- 제약은 그대로 'admin' 거부. 단, 현 test 스위트는 admin 경로를 커버하지 않으므로
-- (CreditRepositoryTest 는 'apple'/'google' 만) 회귀 없음. H2 에서도 admin 을 테스트
-- 하려면 별도 Java-based migration 으로 hash 제약 dynamic discovery 필요.

ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS credit_transactions_platform_check;
ALTER TABLE credit_transactions ADD CONSTRAINT credit_transactions_platform_check
    CHECK (platform IN ('apple', 'google', 'admin'));
