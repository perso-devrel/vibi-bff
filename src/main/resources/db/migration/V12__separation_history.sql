-- separation_jobs 에 Adobe 플러그인 history(목록/삭제/script) 지원 컬럼 추가.
--
-- Adobe 패널은 분리 결과를 "프로젝트별 카드"로 복원한다(로그아웃/다른 PC에서도). 그 카드 메타를
-- BFF가 소유하도록 컬럼을 추가한다:
--   - project_id  : Premiere 프로젝트별 history 버킷(모바일은 버킷팅 안 함 → NULL).
--   - file_name   : 카드에 표시할 원본 파일명.
--   - byte_length : 카드 메타(파일 크기).
-- 모두 nullable — 모바일 잡과 기존 row 는 NULL. plugin 제출 경로(submit 가 채우는 #4 쓰기측)가
-- 이후 값을 넣는다. 본 마이그레이션은 읽기측(목록/삭제/script 라우트)이 의존하는 스키마만 만든다.
ALTER TABLE separation_jobs ADD COLUMN IF NOT EXISTS project_id  TEXT;
ALTER TABLE separation_jobs ADD COLUMN IF NOT EXISTS file_name   TEXT;
ALTER TABLE separation_jobs ADD COLUMN IF NOT EXISTS byte_length BIGINT;

-- (owner, project, 최신순) history 목록 쿼리용 인덱스.
CREATE INDEX IF NOT EXISTS separation_jobs_owner_project_idx
    ON separation_jobs (user_id, project_id, created_at);
