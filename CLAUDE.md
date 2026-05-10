# CLAUDE.md — `vibi-bff`

## Project Overview

vibi BFF — Kotlin/Ktor backend. Perso AI (오디오 분리 + STT + 자동 더빙) 프록시 + Vertex AI Gemini (자막 번역 + 채팅 function calling) 프록시 + 로컬 ffmpeg 렌더 파이프라인 (dub audio mix, sticker overlays, multi-segment concat, subtitle burn-in).

- **Stack**: Kotlin 2.0, Ktor 3.0.3, Netty, kotlinx.serialization, JDK 21
- **Runtime deps**: `ffmpeg`, `ffprobe` on `PATH`
- 자체 git repo, 자체 gradle 빌드 (워크스페이스 루트의 KMP 멀티프로젝트와는 별개)
- 외부 사용자/온보딩용 풀 문서 + API 레퍼런스: [`README.md`](./README.md)

> 폴더는 `vibi-bff/` 로 통일됐지만 코드 패키지 (`com.vibi.bff.*`)
>
> 워크스페이스 지도와 형제 디렉터리(`vibi-mobile/`) 관계는 루트 `CLAUDE.md` 참조.

## Build & Run

```bash
./gradlew compileKotlin                                          # build
./gradlew test                                                   # all tests
./gradlew test --tests "com.vibi.bff.SeparationRoutesTest"    # single class
./gradlew run                                                    # server on :8080
```

Swagger UI at `/swagger`. 환경 변수 표 + API 상세는 `README.md`.

## Structure

- `Application.kt` — entry point, DI wiring, `PERSO_API_KEY` validation
- `config/AppConfig.kt` — HOCON + env var loading
- `plugins/` — CORS, serialization (`Json { ignoreUnknownKeys=true, encodeDefaults=true }`), error handling (StatusPages), routing
- `routes/` — `/api/v2` (multipart, spec-aligned — vibi-mobile (KMP/CMP) Android + iOS 클라이언트가 사용)
- `model/BffModels.kt` — BFF DTOs; `model/PersoModels.kt`, `model/ChatModels.kt` — upstream DTOs (`@SerialName` snake_case → camelCase)
- `service/` — `PersoClient`, `GeminiClient`, `FileStorageService`, `AudioUtils`, `RenderService`, `SeparationService`, `StemMixService`, `AutoSubtitleService`, `AutoDubService`, `SignedUrlService`

## Known BFF bug patterns

새 multipart endpoint 추가 시 / 외부 API 호출 추가 시 여기 먼저 확인. 새 버그 만나면 append.

### Perso STT / 음성분리는 전용 endpoint 사용 — `submitTranslate` 우회 트릭 폐기

**증상 (구)**: STT 받으려고 `submitTranslate` 에 source==target 보냈더니 `hasOriginalSubtitle=false`. 음성분리도 `submitTranslate` + `targetLanguageCodes` echo 트릭으로 우회.

**해결**: Perso 가 STT 와 음성분리에 **전용 endpoint** 제공.

- STT: `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/stt` body=`{mediaSeq, isVideoProject, title?}` → `GET .../stt/script` (paginated `nextCursorId`) → sentences[`offsetMs`,`durationMs`,`originalText`] 로 SRT 빌드.
- 음성분리: `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/audio-separation` → `GET .../download?target=originalVoiceSpeakers` 로 .tar 화자 collection, `GET .../download?target=originalSubBackground` 로 .wav background. `GET .../audio-separation/script` 의 utterance audioUrl 은 빈 값으로 와서 사용 폐기.

### Perso audio-separation `/download` 응답의 host 는 `portal-media.perso.ai`

**증상**: `/perso-storage/...` path 를 `https://api.perso.ai{path}` 로 GET 하면 404 + `Cache-Control: public, max-age=14400` (Cloudflare 가 응답 4시간 캐시) → fresh path 받아도 동일 path-host 조합은 4시간 락.

**원인**: `/download?target=...` 응답의 path 는 별도 storage host 의 것 (Azure Blob 기반 public CDN: `portal-media.perso.ai`). origin (api host) 은 그 path 모름. 인증 헤더 무관.

**해결 패턴**: `PersoConfig.storageBaseUrl = https://portal-media.perso.ai` 환경 변수 분리. `PersoClient.streamDownloadAuthorized` 가 path 가 `/perso-storage/...` 시작이면 storage host + 인증 헤더 X 로 분기. `getDownloadLinks` 의 path 매번 새로 받아도 cache key 는 동일 — 매 retry 마다 fresh path 받아 새 cache key 로 storage 직접 조회.

### Perso `originalSubBackground` 의미 화자 수에 따라 다름 — `originalBackgroundPath` 우선

**증상**: 화자 1명 영상 분리 시 `target=originalSubBackground` 결과가 화자 + BGM mix 그대로. 화자 2명+ 영상에선 BGM only 처럼 보임.

**원인**: Perso 의 `OriginalSubBackground` 의 'Sub' 가 화자 수에 따라 후처리 다름. 진짜 BGM only 는 file 명 `OriginalBaseBackground` (project info endpoint 의 `downloadPathInfo.originalBackgroundPath`).

**해결 패턴**: `SeparationService.downloadBackgroundStem` 이 `getProjectInfo(projectSeq).downloadPathInfo.originalBackgroundPath` 우선 시도, 누락 시 `originalSubBackground` fallback.

### Perso audio-separation 응답 모델은 translation 과 분리

**증상**: `PersoAudioFileLinks.voiceAudioDownloadLink` 가 audio-separation 응답에선 .tar archive (화자 collection), translation 응답에선 다른 의미 (legacy fallback).

**해결 패턴**: 컨텍스트 분리. `PersoClient.getSeparationDownloadLinks(projectSeq, target)` → `PersoSeparationDownloadLinks` (audio-separation 한정). `getDownloadLinks` 는 translation 한정.

### Perso API path prefix — `/video-translator` 통일 (단, 일부 endpoint 만 `/file`)

**증상**: `getDownloadInfo` / `getDownloadLinks` 호출이 항상 404. body 비어있어 cause 불명.

**원인**: download-info / download 의 path prefix 가 `/file/api/v1/...` 가 아니라 `/video-translator/api/v1/...` 가 맞음. project lifecycle (submitTranslate, getProgress, getDownloadInfo, getDownloadLinks) 은 모두 `/video-translator` prefix. 반면 SAS / Blob upload / register 만 `/file/api/upload/...` 사용.

**해결 패턴**: 새 PersoClient method 추가 시 endpoint group 확인 — 영상 lifecycle 은 `/video-translator/api/v1/projects/.../spaces/<spaceSeq>/...`, blob 업로드는 `/file/api/upload/...`.

### Perso 일시 5xx (F5001 INTERNAL_SERVER_ERROR) 는 backoff retry

**증상**: `registerMedia` / `submitTranslate` 등이 가끔 `Perso API error 500: F5001 INTERNAL_SERVER_ERROR` 로 실패. 다시 시도하면 성공.

**해결**: PersoClient method 안에서 5xx 만 짧은 backoff (3s × 3회) 재시도. 4xx 는 즉시 throw.

**적용 사이트** (참고): `PersoClient.registerMedia`. 다른 PersoClient method 도 같은 증상 보이면 동일 패턴 적용.

### Ktor HttpClient default timeout 으로 큰 파일 업로드 못 함

**증상**: 50MB+ 영상 Perso 업로드 시 `ConnectTimeoutException [connect_timeout=60000 ms]`.

**해결**: `Application.kt` 의 HttpClient 에 `connectTimeoutMillis=120s, requestTimeoutMillis=600s, socketTimeoutMillis=600s` + CIO `engine.endpoint.connectAttempts=3`.

### Ktor 3.x `receiveMultipart()` default formFieldLimit 50MB

**증상**: 큰 영상 (50MB 이상) 업로드 시 `java.io.IOException: Multipart content length exceeds limit ... > 52428800` → 500 Internal Server Error.

**해결**: `receiveMultipart()` 호출 시 `formFieldLimit` 명시.
```kotlin
call.receiveMultipart(formFieldLimit = MAX_*_FILE_SIZE)
```

**적용 사이트** (참고): `SubtitleRoutes`, `AutoDubRoutes`, `SeparationRoutes`, `RenderRoutes`. 새 multipart route 추가 시 동일 패턴.

### Gemini 2.5 Flash 가 functionDeclarations 무시하고 tool_code 텍스트로 fallback

**증상**: `/api/v2/chat` 응답이 structured `functionCall` part 없이 `tool_code` 마크다운 + `print(default_api.<name>(args))` Python 스타일 텍스트로 옴. `parseChatResult` 가 TextResponse 분류 → 사용자에게 `kind: proposal / steps: tool_code / print(default_api.generate_subtitles(targetLanguageCodes=["en"]))` 같은 raw 텍스트가 그대로 노출 또는 ChatResponse(kind="text") 로 패스-스루.

**원인**: `gemini-2.5-flash` 가 학습된 code-execution 패턴 (Vertex `default_api` namespace) 으로 fallback. systemInstruction 만으론 100% 차단 불가 — 모델이 자유로 functionCall vs text 선택.

**해결 패턴 (3중 방어)**:
1. `ChatToolDefs.SYSTEM_INSTRUCTION` 첫 부분에 "structured functionCall response part" 명시 + `tool_code` 마크다운 / `print(default_api.foo(...))` 명시 금지.
2. `GeminiClient.chat` payload 에 `toolConfig.functionCallingConfig.mode = "AUTO"` 명시 (default 동일하지만 declarative).
3. `GeminiClient.parseChatResult` 에 fallback regex parser — text 에 `default_api.<name>(kwargs)` 발견 시 `recoverToolCallsFromText` 가 ToolCall 복원. Python literal (str/int/float/bool/list) 만 지원.

**적용 사이트** (참고): `service/ChatToolDefs.kt`, `service/GeminiClient.kt`. 다른 Gemini 모델 (예: 2.5-pro) 로 교체 또는 새 chat-style endpoint 추가 시 동일 패턴 적용.

## Error handling

`plugins/ErrorHandling.kt`: `NotFoundException` → 404, `IllegalArgumentException` → 400, `PersoApiException` → 402/429/4xx/502 by upstream status. All return `ErrorResponse(error, detail?)`.

## File serving

Render outputs **not** static-mounted — `GET /api/v2/render/{jobId}/download`. Separation stems · stem-mix outputs · auto-subtitle SRT · auto-dub audio require HMAC-signed tokens, served via endpoints (never static-mounted).

## Testing

Ktor `testApplication` + MockK. 테스트 파일은 라우트 파일과 1:1 (`SeparationRoutesTest.kt` ↔ `SeparationRoutes.kt`). `RenderService` 는 unit test 없음 — 실제 ffmpeg 스폰.

## Task-specific skills

해당 서브시스템 작업 시 로드 — 본 파일을 잡스럽게 만들지 않기 위해 분리:

- `render-pipeline` — ffmpeg 파이프라인, `RenderConfig`, segments, sticker overlays, Windows path escaping
- `separation-pipeline` — Perso upload 3-step, translate/poll/download, stem plan mapping, HMAC URL signing, mix-triggered dispose
- `review` — code review checklist (트리거: "리뷰")
