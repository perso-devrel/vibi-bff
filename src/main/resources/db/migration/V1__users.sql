-- users — OAuth provider (Google / Apple) ↔ internal UUID 매핑.
-- internal UUID 는 JWT sub 로 발급되고, 향후 IAP `appAccountToken` 으로 그대로 재사용된다
-- (StoreKit 2 의 appAccountToken 은 UUID 만 받음).
--
-- (provider, provider_sub) UNIQUE — 같은 provider sub 가 두 row 로 분기되지 않도록 보장.
-- email 은 UNIQUE 아님: Google 과 Apple 의 같은 이메일이 별도 row 로 들어올 수 있음
-- (account linking 정책은 v2 이후 결정).
CREATE TABLE users (
    id UUID PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    provider_sub VARCHAR(255) NOT NULL,
    email VARCHAR(320) NOT NULL,
    name VARCHAR(255) NOT NULL,
    picture VARCHAR(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, provider_sub)
);

CREATE INDEX users_email_idx ON users (email);
