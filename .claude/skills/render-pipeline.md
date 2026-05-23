---
name: render-pipeline
description: v2 render 기능 작업 시 사용. RenderService, RenderRoutes, RenderConfig, ffmpeg 명령 구성, 다중 세그먼트 concat, BGM atrim+amix, 분리 stem mix, outputKind=audio 경로 관련.
user_invocable: true
trigger: 렌더
---

# Render pipeline

`/api/v2/render/*` 는 `RenderService` 가 백그라운드 코루틴으로 ffmpeg 를 실행해 멀티파트 업로드를 mp4 (또는 m4a) 로 만드는 구조.

## RenderConfig 모드

- **`outputKind="video"`** (기본) — 멀티 세그먼트 정규화 → concat demuxer (`-c copy`) → 최종 ffmpeg `-c:v copy` 로 audio 만 BGM/분리 stem 과 amix. 본 패스는 video 재인코드 X.
- **`outputKind="audio"`** — segment 들의 audio 만 뽑아 concat filter (`v=0,a=1`) 로 합친 뒤 BGM 과 amix. 결과는 .m4a (AAC 192k). 분리 stem 은 audio 모드에선 적용 안 함 (분리 파이프라인의 source 로 쓰이는 용도).
- `segments == null` (legacy `videoDurationMs`) 경로는 router 에서 받지만 multi-segment 가 default — 모바일은 항상 segments 를 채워 보낸다.

## 멀티파트 필드

- `video` (legacy) 또는 `video_0`, `video_1`, … — `segments[].sourceFileKey` 로 참조
- `segment_image_0`, … — IMAGE 타입 세그먼트의 원본 이미지
- `bgm_0`, `bgm_1`, … — BGM 트랙 (`bgmClips[]` 로 참조). `video_*` 와 네임스페이스 분리.
- `config` — `RenderConfig` JSON (FormItem). 옛 모바일이 `dubClips/imageClips/audioOverrideKey/outputLanguageCode` 같은 절단된 필드를 섞어 보내도 `AppJson.ignoreUnknownKeys=true` 로 묵시 무시 (`RenderRoutes.kt` 의 `AppJson.decodeFromString`).
- `inputId` — `/render/inputs` 캐시 슬롯. 같은 영상을 N 번 재렌더할 때 multipart `video` 대신 캐시된 inputId 만 전달.

## Segment 정규화 (video 모드)

모든 정규화된 세그먼트가 동일 codec (libx264) / fps (30) / 해상도 / AAC (44.1 kHz stereo) 가 되어야 concat demuxer 가 `-c copy` 로 통과 가능:

- **VIDEO**: `-ss` 를 `-i` **앞에** 두는 입력측 seek + `-t duration`. 출력 해상도로 scale+letterbox, `anullsrc` 로 무음 AAC fallback (원본 audio 없는 영상도 concat 호환).
- **IMAGE**: `-loop 1` + letterbox + `anullsrc` 무음 AAC (128 k).

세그먼트 정규화는 `Semaphore(MAX_PARALLEL_SEGMENTS)` (기본 CPU/2) 로 동시 ffmpeg 프로세스 제한. 한 세그먼트 실패 시 `coroutineScope` 가 나머지 async 를 취소하고 `finally` 의 `tempDir.deleteRecursively()` 가 부분 파일 정리.

`audio` 모드 경로 (`runAudioOnlyRender`) 는 segment 별로 `extractAudioSegment` 가 audio 만 뽑고, `runSilentAudioFallback` 이 audio 없는 input 에 anullsrc 보조.

## 최종 ffmpeg (video 모드)

`-c:v copy` 라서 비디오는 concat 결과를 그대로 stream-copy. audio 만 `-filter_complex` 로 손봄:

1. `[0:a]` 원본 audio
2. 각 `bgmClips` → `atrim=start=…:end=…,asetpts=PTS-STARTPTS` (sourceTrimStartMs/EndMs sub-range) → `adelay=startMs|startMs,volume=v`
3. 각 살아있는 `separationDirectives` → 같은 패턴 (rangeStartMs window, stem URL 별 volume)
4. `amix=inputs=N:duration=first:dropout_transition=0:normalize=0` — **normalize=0 필수**, default normalize=1 은 silent input 도 N 으로 카운트해 모든 input 감쇠.
5. `alimiter=limit=0.95:attack=5:release=50[aout]` — amix 후 clipping 가드.

bgm/separation 모두 비면 `[0:a]anull[aout]` 패스스루 (`amix=inputs=1` 호환 이슈 회피).

## 분리 stem mix (separationDirective)

`SeparationDirectiveDto.selections[].audioUrl` 은 **BFF 자체 HMAC-signed URL** 만 허용 — 외부 URL reject (SSRF). `muteOriginalSegmentAudio=true` 이면 해당 range 의 원본 audio 는 `volume=0` 게이트.

`sourceOffsetMs` 는 모바일이 한 분리 결과를 range delete 로 split 했을 때 뒤쪽 piece 가 같은 stem audio 의 어느 지점부터 시작하는지. ffmpeg `atrim=start=sourceOffsetMs/1000` 로 처리.

## Job 생명 주기

- 상태: `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`
- 진행률: multi-segment 는 단계 기반 0–95%, 최종 ffmpeg 가 95–100% 로 스케일
- TTL: `jobTtlMs` 후 scheduled executor 가 정리. `acquireRenderOutputCopy` 호출 시 last-access 갱신 — 분리 파이프라인이 referenced 한 job 은 살아남는다.
- 예외 시 `process.destroyForcibly()` + outputFile 삭제 + `inputFilesToCleanup` 일괄 삭제

## R2 download

`R2_BUCKET` 설정 시 `/render/{id}/download` 는 산출물을 R2 에 upload 후 SigV4 presigned URL 로 302. blank 면 `respondFile` streaming (로컬 dev).

## 주요 파일

- `service/RenderService.kt` — ffmpeg 오케스트레이션 (video / audio 모드)
- `routes/RenderRoutes.kt` — 멀티파트 파싱 + `/render/inputs` 캐시 슬롯
- `service/RenderInputCacheService.kt` — `inputId` 슬롯 (sha256, TTL sweep)
- `model/BffModels.kt` — `RenderConfig`, `Segment`, `BgmClip`, `SeparationDirectiveDto`
