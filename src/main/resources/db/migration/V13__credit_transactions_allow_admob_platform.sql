-- credit_transactions.platform CHECK 를 ('apple','google','admin','admob') 으로 확장.
--
-- 보상형 광고(AdMob rewarded) 1회 시청 = +1 크레딧을 platform='admob' row 로 적재한다.
-- (platform, transaction_id) UNIQUE 가 AdMob SSV 의 transaction_id 를 멱등 키로 재활용 —
-- 동일 콜백 재시도/중복 가산을 DB 레벨에서 차단 (apple/google 영수증 경로와 동일 메커니즘).
--
-- V9 와 동일하게 named 제약을 DROP 후 재생성 (Postgres: 'credit_transactions_platform_check').
-- H2 (test) 의 hash-named inline CHECK 는 DROP IF EXISTS 가 match 안 돼 no-op 인 점도 V9 와 동일 —
-- admob 경로 테스트는 별도 grant API 를 직접 호출하므로 inline CHECK 영향 없음.
ALTER TABLE credit_transactions DROP CONSTRAINT IF EXISTS credit_transactions_platform_check;
ALTER TABLE credit_transactions ADD CONSTRAINT credit_transactions_platform_check
    CHECK (platform IN ('apple', 'google', 'admin', 'admob'));
