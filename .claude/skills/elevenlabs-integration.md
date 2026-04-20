---
name: elevenlabs-integration
description: ElevenLabs 업스트림 프록시 작업 시 사용. ElevenLabsClient, voice 목록/TTS/lip-sync dubbing 호출. 엔드포인트, 페이지네이션, 상태 흐름, 에러 매핑.
user_invocable: true
trigger: elevenlabs
---

# ElevenLabs 연동

모든 업스트림 호출은 `service/ElevenLabsClient.kt`에 Ktor CIO 기반으로 래핑. 응답 DTO는 `model/ElevenLabsModels.kt`에서 `@SerialName`으로 snake_case → camelCase 변환.

## 사용 엔드포인트

| 기능         | Method | Path                                                   |
|--------------|--------|--------------------------------------------------------|
| Voices       | GET    | `/v2/voices` (페이지네이션, `page_size=100`)           |
| TTS          | POST   | `/v1/text-to-speech/{voice_id}`                        |
| Dubbing 시작 | POST   | `/v1/dubbing` (`target_lang` 필수, `mode=automatic`, `start_time`/`end_time` 초단위) |
| Dubbing 상태 | GET    | `/v1/dubbing/{dubbing_id}`                             |
| Dubbed 오디오| GET    | `/v1/dubbing/{dubbing_id}/audio/{language_code}`       |

## Dubbing 상태 전이

`dubbing` → `dubbed` (성공, terminal) / `failed` (terminal). BFF의 `/lipsync/{jobId}/status` 폴링이 이 값을 그대로 노출.

## 에러 매핑

`plugins/ErrorHandling.kt`의 `ElevenLabsApiException`이 업스트림 상태 코드를 BFF 응답에 그대로 전달. 바디는 `ErrorResponse(error, detail?)`.

## 인증

`ELEVENLABS_API_KEY` 환경변수. `Application.kt` 기동 시 `isNotBlank()` 검증 — 키 없으면 기동 실패.

## v1/v2 차이

- `/api/v1/voices`: 원본 응답 그대로
- `/api/v2/voices`: ElevenLabs `labels`에서 `language` 필드 추출해 DTO에 추가
- `/api/v1/tts`: 오디오 파일만 반환
- `/api/v2/tts`: `durationMs`도 포함 (MP3 frame header 파싱 via `AudioUtils`)
- `/api/v1/lipsync`: JSON + `blobPath` (사전 `/upload` 필요)
- `/api/v2/lipsync`: 멀티파트 단일 호출
