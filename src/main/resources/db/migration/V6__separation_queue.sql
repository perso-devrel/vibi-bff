-- Perso audio-separation 동시성 큐.
--
-- Perso 측 제약: audio-separation 잡은 한 번에 1개만 실행, 큐는 2-3개. Perso space 가
-- 우리 전용이라 정확히 추적 가능. 본 BFF 가 자체 큐를 들어 dispatcher 코루틴이
-- SKIP LOCKED 로 한 줄씩 claim → Perso 호출 → status 전이.
--
-- 멀티 인스턴스 (Cloud Run max-instances=2) 안전성:
--   - capacity 카운트는 모든 인스턴스의 SUBMITTING+PROCESSING 합산 (공유 자원)
--   - claim 은 자기 인스턴스가 enqueue 한 QUEUED row 만 (bff_instance_id 매칭) — 소스 파일이
--     해당 인스턴스 로컬 /tmp 에 있기 때문. 이 제약을 풀려면 R2 로 소스 업로드 단계 추가 필요.
--   - 카운트 + 클레임이 한 트랜잭션 + SELECT FOR UPDATE SKIP LOCKED → race 차단
--
-- 인스턴스 재시작 시 trade-off (MVP):
--   - 자기 인스턴스의 QUEUED row 는 30분 후 stale reaper 가 FAILED 마킹 (소스 파일 손실)
--   - SUBMITTING 은 60초 timeout → QUEUED 로 복귀 + attempt_count++
--   - PROCESSING (Perso 가 받은 상태) 은 별도 polling resumption 필요 — 후속 작업
--
-- status 값 (text):
--   QUEUED       : BFF 큐 대기 (Perso 미호출)
--   SUBMITTING   : dispatcher 가 Perso submitAudioSeparation 호출 중 (수초)
--   PROCESSING   : Perso 가 받음, 폴링 중 (기존 UPLOADING_UPSTREAM/SUBMITTED/DOWNLOADING 모두 포함)
--   READY        : 완료
--   FAILED       : 실패

-- 컬럼은 개별 ALTER 로 — H2 PostgreSQL mode 가 comma-separated ADD COLUMN 미지원 (테스트 시
-- Flyway 가 같은 마이그레이션 실행).
ALTER TABLE separation_jobs ADD COLUMN bff_instance_id   TEXT;
ALTER TABLE separation_jobs ADD COLUMN perso_project_seq BIGINT;
ALTER TABLE separation_jobs ADD COLUMN queued_at         TIMESTAMP WITH TIME ZONE;
ALTER TABLE separation_jobs ADD COLUMN dispatched_at     TIMESTAMP WITH TIME ZONE;
ALTER TABLE separation_jobs ADD COLUMN attempt_count     INTEGER NOT NULL DEFAULT 0;

-- dispatcher 가 매 tick 마다 도는 두 쿼리 (capacity count + claim) 최적화.
-- 본래 PARTIAL INDEX (WHERE status IN ...) 가 이상적이지만 H2 PostgreSQL mode 가 partial
-- index 미지원이라 full index. active 잡 (보통 0~수개) 이외엔 row 가 적어 비용 차이 무시 가능.
CREATE INDEX separation_jobs_dispatcher_idx
    ON separation_jobs (status, bff_instance_id, queued_at);

-- capacity 쿼리: COUNT(*) WHERE status IN ('SUBMITTING','PROCESSING'). status 컬럼 단독 인덱스.
CREATE INDEX separation_jobs_status_idx
    ON separation_jobs (status);
