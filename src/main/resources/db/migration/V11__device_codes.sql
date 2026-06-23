-- device_codes — RFC 8628 device authorization flow.
--
-- Adobe Premiere UXP 패널은 샌드박스라 네이티브 OAuth(앱스토어 SDK)를 못 쓴다. 그래서 패널은
-- device-code 플로우로 로그인한다: 패널이 device/user 코드를 받고(`device/start`), 사용자가
-- 브라우저(`/device` → Google)에서 승인하면 패널이 폴링(`device/poll`)으로 access token 을 받는다.
--
-- 이 테이블은 원래 vibi-adobe-plugin 의 server/ 가 런타임 ensureSchema() 로 만들던 것을 BFF
-- Flyway 소유로 승격한 것이다(server/ 통합·삭제 준비). 공유 Neon DB 에 plugin 이 이미 만들었을
-- 수 있으므로 CREATE/ALTER 모두 IF NOT EXISTS 로 비파괴 채택한다 — 기존 테이블이면 누락 컬럼만
-- 추가, 없으면 신규 생성. plugin 원본 컬럼은 모두 TEXT 였고, VARCHAR(n) 는 동일 값에 호환된다
-- (기존 TEXT 컬럼이 있으면 CREATE 가 skip 되어 타입 충돌 없음).
CREATE TABLE IF NOT EXISTS device_codes (
    device_code   VARCHAR(64)  PRIMARY KEY,
    user_code     VARCHAR(16)  NOT NULL UNIQUE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending',
    -- user_sub 는 users.id (internal UUID) 의 문자열. 승인 시점에 채워짐. 코드 수명이 짧고(10분)
    -- 사용 즉시 삭제(single-use)되므로 FK 는 두지 않는다(계정 삭제 race 회피).
    user_sub      VARCHAR(64),
    user_email    VARCHAR(320),
    user_name     VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- BFF 가 발급하는 access token 은 role/picture claim 을 싣는다(plugin 원본 토큰엔 없던 값).
-- 승인 콜백이 이 두 컬럼에 기록하고 device/poll 이 읽어 issueAccessToken 에 넘긴다.
ALTER TABLE device_codes ADD COLUMN IF NOT EXISTS user_role    VARCHAR(16);
ALTER TABLE device_codes ADD COLUMN IF NOT EXISTS user_picture VARCHAR(2048);

-- 만료 코드 sweep(created_at < now - TTL)용 인덱스.
CREATE INDEX IF NOT EXISTS device_codes_created_at_idx ON device_codes (created_at);
