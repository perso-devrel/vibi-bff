-- 외부 API 호출 추적 — DevRel 비용 예측 + 안정성 가시성.
--
-- 추적 granularity: "사용자 1명이 1회 호출하는 logical operation" 단위. Perso 의 내부
-- polling/retry/upload 는 단일 row 로 묶는다 — billable unit 과 일치하므로 비용 추론 직결.
--   • 'perso' / 'audio-separation' — SeparationService.runPipeline 1회 (upload+submit+poll+download 통합)
--   • 'gemini' / 'chat'            — GeminiClient.chat 1회
--
-- 새 provider 추가 시: client wrapper 측에서 `withExternalCall(provider, endpoint)` 한 번
-- 감싸기만 하면 됨.

CREATE TABLE external_api_calls (
    id BIGSERIAL PRIMARY KEY,
    provider TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    status_code INTEGER,
    success BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL,
    error_class TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX external_api_calls_provider_created_idx ON external_api_calls (provider, created_at);
CREATE INDEX external_api_calls_created_idx ON external_api_calls (created_at);
