-- 사용량 분석용 잡 영속화.
--
-- 기존 RenderService.jobs / SeparationService.jobs 는 ConcurrentHashMap in-memory 라
-- 서버 재시작 시 소실 → 누가 얼마나 영상을 올렸는지, 영상 당 분리 몇 번 돌렸는지 추적 불가.
-- 본 테이블은 in-memory 상태와 병행 유지되며 admin 대시보드 (V2 surface) 가 읽는다.
--
-- 잡 ID 는 in-memory 의 텍스트 ID 와 1:1 ("render-<uuid>", "sep-<uuid>"). 별도 surrogate
-- UUID PK 안 두는 이유: in-memory ↔ DB 매핑 1줄로 단순화. 외부 surface 에 노출되는 ID 라
-- TEXT PK 가 join/lookup 모두 자연스럽다.
--
-- source_duration_ms 는 사용자가 올린 입력 길이 (segments trim 합산 또는 spec.trim 윈도우).
-- 대시보드의 "영상 길이" KPI = SUM(source_duration_ms) GROUP BY user_id / GROUP BY date.
--
-- finished_at 은 status ∈ {COMPLETED, FAILED} 진입 시점. NULL 이면 아직 진행중 또는
-- 진행중에 서버가 죽은 잡 (orphan). 대시보드는 finished_at - created_at 으로 처리 시간
-- 분포도 그릴 수 있다 (v2).

CREATE TABLE render_jobs (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    source_duration_ms BIGINT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX render_jobs_user_created_idx ON render_jobs (user_id, created_at DESC);
CREATE INDEX render_jobs_created_idx ON render_jobs (created_at);

-- separation_jobs.render_job_id 가 NULL 이면 legacy multipart 직접 업로드 분기,
-- NOT NULL 이면 spec.editedRenderJobId 경유. 영상 당 분리 횟수 = GROUP BY render_job_id.
CREATE TABLE separation_jobs (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    render_job_id TEXT REFERENCES render_jobs(id),
    source_duration_ms BIGINT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX separation_jobs_user_created_idx ON separation_jobs (user_id, created_at DESC);
CREATE INDEX separation_jobs_render_idx ON separation_jobs (render_job_id);
CREATE INDEX separation_jobs_created_idx ON separation_jobs (created_at);
