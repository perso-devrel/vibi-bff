---
name: render-pipeline
description: v2 render 기능 작업 시 사용. RenderService, RenderRoutes, RenderConfig, ffmpeg 명령 구성, 스티커 오버레이(imageClips), 다중 세그먼트 concat, 더빙 오디오 믹싱(dubClips), ASS 자막 burn-in 관련.
user_invocable: true
trigger: 렌더
---

# Render pipeline

`/api/v2/render/*` 는 `RenderService`가 백그라운드 코루틴으로 ffmpeg를 실행해 멀티파트 업로드를 mp4로 만드는 구조.

## RenderConfig 두 경로

`segments` 필드 유무로 경로 결정:

- **Legacy** (`segments == null`) — 단일 `video` 입력 + `videoDurationMs`. 단일 ffmpeg 실행으로 끝.
- **Multi-segment** (`segments != null`) — 세그먼트별 정규화 → concat demuxer → 최종 ffmpeg(스티커+오디오+자막).

## 멀티파트 필드 규칙

- `video` (legacy) 또는 `video_0`, `video_1`, … — `segments[].sourceFileKey`로 참조
- `segment_image_0`, … — IMAGE 타입 세그먼트의 원본 이미지
- `image_0`, `image_1`, … — 스티커 오버레이 (`imageClips[]`로 참조)
- `audio_0`, `audio_1`, … — 더빙 트랙 (`dubClips[]`로 참조)
- `subtitles` — ASS 자막 (선택, 최종 단계에서 burn)
- `config` — `RenderConfig` JSON (FormItem, 파일 아님)

## 세그먼트 처리 규칙

모든 정규화된 세그먼트를 stream-compatible하게 만드는 게 핵심:

- **VIDEO**: `-ss`를 `-i` **앞에** 두는 입력측 seek + `-t duration`. 출력 해상도로 scale+letterbox, `anullsrc`로 무음 AAC 트랙 추가 (원본에 오디오가 없어도 concat 호환).
- **IMAGE**: `-loop 1` + letterbox (`scale=…:force_original_aspect_ratio=decrease, pad=W:H:(ow-iw)/2:(oh-ih)/2`) + 무음 AAC(128k).

## 출력 해상도

- Multi-segment: `segments[0].width` / `segments[0].height`
- Legacy: 원본 video에 대해 ffprobe

스티커의 `xPct/yPct/widthPct/heightPct`, 세그먼트의 `imageWidthPct/imageHeightPct` 모두 이 출력 해상도 대비 퍼센트로 계산.

## Concat

정규화된 모든 세그먼트가 동일한 codec(libx264)/fps(30)/해상도/AAC 오디오 → concat demuxer + `-c copy` 고속.

## 최종 ffmpeg 단계

순서: 자막 `ass` 필터 → 스티커 `overlay` 체인 (각 `enable='between(t,s,e)'`) → 오디오 믹싱.

오디오 분기:
- `dubClips` 빈 리스트: `[0:a]anull[aout]` 패스스루 (`amix=inputs=1`은 구버전 ffmpeg에서 문제)
- 아니면 `adelay=ms|ms, volume=v` 후 원본 오디오와 `amix=duration=first:dropout_transition=0`

## Windows 경로 주의

`escapeFilterPath`는 드라이브 문자 콜론 (`C:`)을 보존하고 이후 콜론만 `\:`로 이스케이프. 자막/이미지 경로가 필터 표현식에 임베드될 때 중요 — 드라이브 콜론을 통째로 이스케이프하면 ffmpeg가 파일을 못 찾음.

## Job 생명주기

- 상태: `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`
- 진행률: multi-segment는 단계 기반 0–95%, 최종 ffmpeg가 95–100%로 스케일
- TTL: 완료/실패 후 1시간 뒤 scheduled executor로 정리
- 예외 발생 시 `process.destroyForcibly()` + outputFile 삭제 + inputFilesToCleanup 일괄 삭제

## 주요 파일

- `service/RenderService.kt` — ffmpeg 오케스트레이션
- `routes/RenderRoutes.kt` — 멀티파트 파싱
- `model/BffModels.kt` — `RenderConfig`, `Segment`, `DubClip`, `ImageClip`
