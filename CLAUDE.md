# CLAUDE.md — `vibi-bff`

## Project Overview

vibi BFF — Kotlin/Ktor backend. ElevenLabs (TTS / voices / lip-sync dubbing) 프록시 + Perso AI (오디오 분리) 프록시 + Vertex AI Gemini (자막 번역 + 채팅 function calling) 프록시 + 로컬 ffmpeg 렌더 파이프라인 (dub audio mix, sticker overlays, multi-segment concat, subtitle burn-in).

- **Stack**: Kotlin 2.0, Ktor 3.0.3, Netty, kotlinx.serialization, JDK 21
- **Runtime deps**: `ffmpeg`, `ffprobe` on `PATH`
- 자체 git repo, 자체 gradle 빌드 (워크스페이스 루트의 KMP 멀티프로젝트와는 별개)
- 외부 사용자/온보딩용 풀 문서 + API 레퍼런스: [`README.md`](./README.md)

> 폴더는 `vibi-bff/` 로 통일됐지만 코드 패키지 (`com.dubcast.bff.*`), 빌드 출력 경로 (`C:/tmp/dubcast-bff-build`), OpenAPI 파일명 (`dubcast-bff.yaml`) 은 legacy. 패키지 마이그레이션은 별도 작업.
>
> 워크스페이스 지도와 형제 디렉터리(`vibi-mobile/`) 관계는 루트 `CLAUDE.md` 참조.

## Build & Run

```bash
./gradlew compileKotlin                                    # build
./gradlew test                                             # all tests
./gradlew test --tests "com.dubcast.bff.TtsRoutesTest"     # single class
./gradlew run                                              # server on :8080
```

Swagger UI at `/swagger`. 환경 변수 표 + API 상세는 `README.md`.

## Structure

- `Application.kt` — entry point, DI wiring, `ELEVENLABS_API_KEY` + `PERSO_API_KEY` validation
- `config/AppConfig.kt` — HOCON + env var loading
- `plugins/` — CORS, serialization (`Json { ignoreUnknownKeys=true, encodeDefaults=true }`), error handling (StatusPages), routing
- `routes/` — `/api/v1` (legacy JSON) + `/api/v2` (multipart, spec-aligned — vibi-mobile (KMP/CMP) Android + iOS 클라이언트가 사용)
- `model/BffModels.kt` — BFF DTOs; `model/ElevenLabsModels.kt`, `model/PersoModels.kt` — upstream DTOs (`@SerialName` snake_case → camelCase)
- `service/` — `ElevenLabsClient`, `PersoClient`, `FileStorageService`, `AudioUtils`, `RenderService`, `SeparationService`, `StemMixService`, `SignedUrlService`

## Known BFF bug patterns

새 multipart endpoint 추가 시 / 외부 API 호출 추가 시 여기 먼저 확인. 새 버그 만나면 append.

### Perso STT / 음성분리는 전용 endpoint 사용 — `submitTranslate` 우회 트릭 폐기

**증상 (구)**: STT 받으려고 `submitTranslate` 에 source==target 보냈더니 `hasOriginalSubtitle=false`. 음성분리도 `submitTranslate` + `targetLanguageCodes` echo 트릭으로 우회.

**해결**: Perso 가 STT 와 음성분리에 **전용 endpoint** 제공 (`new_api.md` 참조).

- STT: `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/stt` body=`{mediaSeq, isVideoProject, title?}` → `GET .../stt/script` (paginated `nextCursorId`) → sentences[`offsetMs`,`durationMs`,`originalText`] 로 SRT 빌드.
- 음성분리: `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/audio-separation` → `GET .../audio-separation/script` (paginated) → sentences 각각 `audioUrl` 로 화자별 utterance 다운로드 → ffmpeg `adelay+amix` 로 timeline-aligned 화자 stem 합성.

**주의**: 새 audio-separation API 는 background stem 미제공. 화자 stem (`speaker_0..N`) + `voice_all` 만 만들어짐.

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

## Error handling

`plugins/ErrorHandling.kt`: `NotFoundException` → 404, `IllegalArgumentException` → 400, `ElevenLabsApiException` → upstream code, `PersoApiException` → 402/429/4xx/502 by upstream status. All return `ErrorResponse(error, detail?)`.

## File serving

Static mounts: `/files/tts/*`, `/files/lipsync/*`. Render outputs **not** static-mounted — `GET /api/v2/render/{jobId}/download`. Separation stems · stem-mix outputs require HMAC-signed tokens, served via endpoints (never static-mounted).

## Testing

Ktor `testApplication` + MockK. 테스트 파일은 라우트 파일과 1:1 (`TtsRoutesTest.kt` ↔ `TtsRoutes.kt`). `RenderService` 는 unit test 없음 — 실제 ffmpeg 스폰.

## Task-specific skills

해당 서브시스템 작업 시 로드 — 본 파일을 잡스럽게 만들지 않기 위해 분리:

- `render-pipeline` — ffmpeg 파이프라인, `RenderConfig`, segments, sticker overlays, Windows path escaping
- `elevenlabs-integration` — upstream endpoints, dubbing status flow, v1/v2 differences
- `separation-pipeline` — Perso upload 3-step, translate/poll/download, stem plan mapping, HMAC URL signing, mix-triggered dispose
- `review` — code review checklist (트리거: "리뷰")
