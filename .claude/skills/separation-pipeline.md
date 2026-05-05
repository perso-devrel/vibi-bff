---
name: separation-pipeline
description: v2 음원분리 기능 작업 시 사용. PersoClient, SeparationService, StemMixService, SeparationRoutes, SignedUrlService. 업로드 3-step, 진행률 폴링, stem 다운로드, HMAC 서명 URL, mix 완료 기반 자동 삭제.
user_invocable: true
trigger: 분리
---

# Separation pipeline

`/api/v2/separate/*` 는 Perso AI 더빙 파이프라인의 **부산물**(stem)을 꺼내 쓰는 구조. Perso 에 독립적인 "음원분리 API" 는 없고, translate 잡의 다운로드 targets 로 stem 을 얻음.

## 아키텍처 한 장 요약

```
클라이언트 업로드 ─▶ (spec.trim* 있으면 MediaTrimmer 로 ffmpeg stream-copy 컷)
                      │
                      ▼
                   PersoClient (SAS → Blob PUT → register)
                      │
                      ▼
                   submitTranslate ─▶ projectSeq 반환
                      │
                      ▼
                pollUntilComplete (5s 간격, progressReason="Completed")
                      │
                      ▼
                getDownloadInfo → 가용 stem 확인
                      │
                      ▼
                각 plan.persoTarget 에 대해 getDownloadLinks + streamDownload
                      │                                      (atomic tmp→rename)
                      ▼
                outputDir/{stemId}.mp3 저장 + SeparationJob.status="READY"
                      │
                      ▼
                클라이언트가 GET /{jobId} 로 서명된 stem URL 목록 받음
                      │
                      ▼
                POST /{jobId}/mix (reserveForMix atomic 전이)
                      │
                      ▼
                StemMixService.submit → ffmpeg amix → COMPLETED
                      │
                      ▼
                onCompleted 콜백이 SeparationService.dispose(jobId) 호출
                → outputDir 통째 삭제. 동일 jobId 재사용 불가.
```

## Perso stem ↔ 노출 stem 매핑

`SeparationService.buildStemPlans(numberOfSpeakers)` 가 계획을 만든다.

| `stemId`        | label       | Perso `target`            | `hasFor(DownloadInfo)` 플래그            |
|-----------------|-------------|---------------------------|------------------------------------------|
| `background`    | 배경음      | `backgroundAudio`         | `hasOriginalBackground`                  |
| `voice_all`     | 모든 화자   | `originalVoiceAudio`      | `hasOriginalVoiceOnly`                   |
| `speakers_zip`  | 화자별      | `originalVoiceSpeakers`   | `hasOriginalSpeakerAudioCollection`      |

`speakers_zip` 만 ZIP → `extractSpeakerZip` 으로 `speaker_0.mp3`, `speaker_1.mp3`… 로 쪼개서 `stemId = "speaker_N"` 로 노출. numberOfSpeakers ≥ 2 일 때만 plan 에 포함.

**Perso 는 "리액션만 / 순수 BGM 만" 을 별도로 분리하지 않음.** `backgroundAudio` 하나에 보컬 제외 전부(BGM + 효과음 + 리액션)가 뭉쳐서 온다. 클라이언트에 두 개로 보여주면 안 됨.

## Perso 업로드 3-step (순서 고정)

`PersoClient.uploadMedia(mediaType, file)` 가 세 호출을 감싸서 대신 해준다.

1. `GET /file/api/upload/sas-token?fileName=<URL-encoded>` → `blobSasUrl`, `expirationDatetime`
2. `PUT <blobSasUrl>` — **XP-API-KEY 금지**(SAS 가 이미 사전 서명), `x-ms-blob-type: BlockBlob` 필수. 500 MB 까지 받으므로 **힙 올리지 말고 스트리밍**. `OutgoingContent.ReadChannelContent` 로 `FileInputStream` 감싼 `ByteReadChannel` 을 준다.
3. `PUT /file/api/upload/{video|audio}` body `{ spaceSeq, fileUrl, fileName }` — `fileUrl` 은 SAS URL 에서 **`?query-string` 떼고** path-only 부분만. `seq` 반환 → 이게 `mediaSeq`.

## Submit / 폴링

- `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/translate` — `numberOfSpeakers` 는 **필수 + auto 없음**. 번역을 쓰진 않지만 API 가 `targetLanguageCodes` 를 요구하므로 `sourceLanguageCode` 를 에코(`"auto"` 면 `"en"` 대체)해 낭비 최소화.
- 응답은 `{ "result": { "startGenerateProjectIdList": [N] } }` — 첫 원소를 `projectSeq` 로.
- `GET .../progress` 를 `PERSO_POLL_INTERVAL_MS`(기본 5s) 간격으로. `progressReason == "Completed"` 면 종료, `== "Failed"` 또는 `hasFailed=true` 면 예외. `PERSO_MAX_POLL_MINUTES` 초과 시 타임아웃.

## 상태 머신

```
PENDING → UPLOADING_UPSTREAM → SUBMITTED → PROCESSING → DOWNLOADING → READY
                                                              └→ FAILED
READY + consumedByMixJobId == null → mix 예약 가능
READY + consumedByMixJobId != null → 409 (이미 mix 진행 중)
```

- `reserveForMix(jobId, mixJobId)` 는 `synchronized(job)` 블록에서 상태+슬롯을 원자 전이. 두 번째 호출은 null.
- `releaseReservation(jobId)` 는 stem ID 유효성 검사 실패 시 롤백용.
- `dispose(jobId)` 는 `jobs` 맵에서 제거하고 `outputDir.deleteRecursively()`.

## 수명 정책

- **READY 방치**: `SEPARATION_ABANDON_TTL_MS`(기본 30분) 후 `cleanupAbandoned` 가 회수.
- **FAILED**: 별도 `FAILED_JOB_TTL_MS = 5분`(코드 상수). 클라이언트가 에러 이유를 읽을 시간만 확보 후 삭제.
- **mix 성공 시**: TTL 무관하게 즉시 `dispose(jobId)`. stem 을 미리 받아두지 않았으면 손실됨 — 클라이언트에 안내 필수.
- **mix 결과물**: `{basePath}/separation/mix/{mixJobId}.mp3` 에 저장, `SEPARATION_MIX_TTL_MS`(기본 10분) 후 삭제. 다운로드 1회는 충분한 수명.

## 서명 URL (`SignedUrlService`)

HMAC-SHA256 으로 `{jobId, resourceId, expiresAt}` 을 서명. 토큰 포맷: `"{expiresAtEpochSec}.{urlSafeBase64}"`.

- `SEPARATION_SIGNING_SECRET` 는 ≥ 32자 필수. 부팅 시점 검증. `openssl rand -hex 32` 출력 사용.
- 검증은 `MessageDigest.isEqual` 로 constant-time — 타이밍 사이드채널 차단.
- stem URL 수명: `SEPARATION_URL_TTL_SEC`(기본 1800s). mix URL 수명: `SEPARATION_MIX_URL_TTL_SEC`(기본 600s).
- 클라이언트는 TTL 만료 시 `GET /{jobId}` 재호출로 새 토큰 발급받음.
- **로테이션 주의**: secret 을 바꾸면 outstanding 토큰이 전부 무효. 운영 중 교체 금지.

## ffmpeg mix (`StemMixService`)

`buildStemMixCommand(stemFiles, outputFile)` — 순수 함수, 테스트 대상.

```
ffmpeg -y -i a.mp3 -i b.mp3 -i c.mp3 \
  -filter_complex "[0:a]volume=0.5[a0];[1:a]volume=1.2[a1];[2:a]volume=1.0[a2];[a0][a1][a2]amix=inputs=3:duration=longest:dropout_transition=0[aout]" \
  -map "[aout]" -c:a libmp3lame -b:a 192k out.mp3
```

- 각 input 에 `volume=<sel.volume>` 필터를 붙여 개별 볼륨 조절.
- `duration=longest` — 길이가 다른 stem 을 잘라내지 않고 짧은 쪽에 무음 padding.
- `dropout_transition=0` — amix 기본 전이 효과(동적 정규화) 제거해 볼륨이 stem 선택과 무관하게 일정.
- 출력은 `libmp3lame 192k` mp3.

## 보안

- 분리 결과는 **정적 마운트 금지**. `/files/separation` 같은 경로 만들지 말 것. HMAC 서명 토큰 엔드포인트로만 노출.
- `CallLogging` format 이 `?token=...` 를 `?token=***` 로 마스킹. 추가로 Ktor Client `Logging.level = NONE` — `XP-API-KEY` 헤더와 SAS URL 이 평문 로깅되던 것 전부 차단.
- `extractSpeakerZip` 은 우리가 파일명을 결정하지만(`speaker_{idx}.mp3`), canonical-path check 로 ZIP Slip 방어층 추가 — 미래에 `entry.name` 을 채택하는 리팩터가 들어와도 안전.
- Path traversal: `FileStorageService.getUploadFile` 과 동일 패턴. `separationDir` 의 canonical prefix 체크로 escape 차단.

## 실제 호출 전 확인 필요 항목

1. **`originalVoiceSpeakersDownloadLink` 실제 응답 키** — 문서에 없음. 첫 실호출 때 Perso 응답의 정확한 키 이름 확인 후 `PersoAudioFileLinks` 필드명 맞출 것. `ignoreUnknownKeys = true` 덕에 틀려도 다른 stem 은 정상, 이 stem 만 null 로 떨어짐.
2. **ZIP 내부 파일명 컨벤션** — `extractSpeakerZip` 은 `.mp3` 만 필터해 순차 인덱스 배정. Perso 가 `speaker_A.mp3` 같은 이름을 주면 A→`speaker_0`, B→`speaker_1` 순으로 매핑됨. 화자별 정렬 유지되는지 실호출로 확인 권장.
3. **Perso 쿼터** — translate 를 쿼터 차감 없이 분리만 하는 "separation-only" 모드는 문서에 없음. 사용량 모니터링 필요.

## Pre-upload trim (`spec.trimStartMs` / `trimEndMs`)

`SeparationSpec` 에 두 필드 모두 있으면 `maybeTrim` (in `SeparationRoutes.kt`) 이
`MediaTrimmer.probeDurationMs` 로 실제 duration 을 재 본 뒤 범위 초과면
`400 trim_end_exceeds_duration` 로 거절, 통과하면 `MediaTrimmer.trim` 으로
`-c copy` 스트림 카피 컷을 만들어 원본을 삭제하고 잘린 파일을 파이프라인에
전달한다. static validation (범위/최소 500ms/부분 지정) 은 `SeparationSpec.init` 에서
수행하며 에러 코드는 `partial_trim_range`, `trim_range_invalid`,
`trim_range_too_short`, `trim_start_negative`. ffprobe/ffmpeg 실패는
`500 ffmpeg_error`. 두 필드 모두 null 이면 기존과 완전히 동일 (backward compatible).
비용은 제공자 쪽에서 duration-based 로 자동 감소 — 별도 청구 로직 없음.

## 주요 파일

- `service/PersoClient.kt` — 업로드 3-step, translate, progress, download
- `service/SeparationService.kt` — Job 관리, 파이프라인 오케스트레이션, dispose
- `service/StemMixService.kt` — ffmpeg amix 합성, `buildStemMixCommand`
- `service/SignedUrlService.kt` — HMAC 서명
- `service/MediaTrimmer.kt` — pre-upload ffprobe + ffmpeg stream-copy trim
- `routes/SeparationRoutes.kt` — `/api/v2/separate/*` 엔드포인트 (maybeTrim 포함)
- `model/PersoModels.kt` — 업스트림 DTO
- `model/BffModels.kt` — `SeparationSpec`, `StemInfo`, `StemMixRequest` 등
