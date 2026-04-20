---
name: elevenlabs-integration
description: ElevenLabs 업스트림 프록시 작업 시 사용. ElevenLabsClient, voice 목록/TTS/lip-sync dubbing 호출. 엔드포인트, 페이지네이션, 상태 흐름, 에러 매핑.
user_invocable: true
trigger: elevenlabs
---

# ElevenLabs 연동

모든 업스트림 호출은 `service/ElevenLabsClient.kt`로 래핑. 클라이언트는 외부에서 `HttpClient`를 주입받음 (`Application.kt`에서 CIO 엔진으로 생성). 응답 DTO는 `model/ElevenLabsModels.kt`에서 `@SerialName`으로 snake_case → camelCase 변환.

## 사용 엔드포인트

| 기능         | Method | Path                                                   |
|--------------|--------|--------------------------------------------------------|
| Voices       | GET    | `/v2/voices` (`page_size=100`, 내부에서 모든 페이지 순회 후 합쳐 단일 응답으로 반환. `totalCount`는 누적 개수로 재계산. **결과는 in-memory TTL 캐시** — 기본 10분, single-flight Mutex로 동시 첫 요청 합침. 강제 갱신은 `invalidateVoicesCache()`) |
| TTS          | POST   | `/v1/text-to-speech/{voice_id}`                        |
| Dubbing 시작 | POST   | `/v1/dubbing` (`target_lang` 필수, `mode=automatic`. `start_time`/`end_time` 초단위 — BFF가 ms 입력을 `start_time = startMs/1000`, `end_time = (startMs+durationMs)/1000`으로 변환해 전송) |
| Dubbing 상태 | GET    | `/v1/dubbing/{dubbing_id}`                             |
| Dubbed 오디오| GET    | `/v1/dubbing/{dubbing_id}/audio/{language_code}`       |

## Dubbing 상태 전이

`dubbing` → `dubbed` (성공, terminal) / `failed` (terminal). BFF의 `/lipsync/{jobId}/status` 폴링이 이 값을 그대로 노출.

`getLipSyncStatus(id)`는 jobId별 in-memory 캐시 적용 — non-terminal은 짧은 TTL(기본 2초)로 폴링 동안 업스트림 QPS를 줄이고, terminal(`dubbed`/`failed`)은 긴 TTL(기본 1시간)로 결과가 변하지 않으니 거의 영구 캐시. 단일 mutex 없이 동작 — 2초 TTL 안에 발생하는 중복 fetch는 무해(GET idempotent). TTL은 생성자 파라미터(`lipSyncStatusTtlMs`, `lipSyncStatusTerminalTtlMs`)로 조정 가능.

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
