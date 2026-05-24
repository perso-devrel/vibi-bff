-- separation stem 메타를 DB 로 영속화 — 인스턴스가 죽어도 R2 + DB 만으로 GET 응답 재구축.
--
-- 배경 (deploy churn / OOM / scale-down 으로 인스턴스가 READY 직후 죽는 사고):
--   * stems FLAC 은 인스턴스 local /tmp 에만 존재
--   * R2 업로드는 첫 GET 요청 시점에 ObjectStore.uploadIfAbsent 가 lazy 로 함
--   * GET 전에 인스턴스가 죽으면 stems 가 디스크와 함께 증발 — DB 는 READY 인데 실체 없음
--   * 새 인스턴스 boot resumption 은 PROCESSING 만 다루므로 영구 손실
--
-- 해결: runPipelineDownloadPhase 가 READY 마킹 전에 R2 로 eager upload + 본 컬럼들에 메타 persist
-- → 새 인스턴스의 SeparationService.getJob 이 in-memory miss 시 DB 에서 재구축 가능.
--
-- 컬럼 형태:
--   stems_json TEXT  — JSON 배열 [{stemId, label, ext}, ...]. R2 object key 는 ObjectKey.separationStem
--                      (jobId, stemId, ext) 로 계산되므로 별도 컬럼 불필요. JSON 으로 둔 이유는
--                      stem 개수가 가변 (1 ~ N 화자 + voice_all + background) + 추후 필드 (예: peakDb)
--                      추가 시 마이그레이션 없이 확장 가능.
--   actual_duration_ms BIGINT — speaker stem 의 ffprobe 측정 길이. 모바일 timeline 보정용.
--
-- 기존 row 호환을 위해 둘 다 NULL 허용. status=READY 이면서 stems_json 이 NULL 인 row 는
-- migration 이전에 만들어진 잡 — DB fallback 분기가 NULL 보면 in-memory miss 그대로 둠
-- (사용자한테는 새로 분리 요청 안내).

ALTER TABLE separation_jobs ADD COLUMN stems_json         TEXT;
ALTER TABLE separation_jobs ADD COLUMN actual_duration_ms BIGINT;
