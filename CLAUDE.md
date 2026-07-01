# CLAUDE.md — `vibi-bff`

## Project Overview

vibi BFF — Kotlin/Ktor backend. **Perso AI** 프록시 (오디오 분리) + 로컬 ffmpeg 렌더 파이프라인 (multi-segment concat, BGM atrim+amix, audio override) + **Google / Apple Sign In** ID Token 검증 + **Postgres** user upsert + HS256 JWT 발급 + **크레딧/인앱결제** (StoreKit2 / Play Billing 영수증 검증) + **admin 대시보드** (읽기전용 분석) + 옵션 **Cloudflare R2** SigV4 presigned URL redirect.

- **Stack**: Kotlin 2.0, Ktor 3.0.3, Netty, kotlinx.serialization, JDK 21, Exposed + HikariCP, Postgres
- **Runtime deps**: `ffmpeg`, `ffprobe` on `PATH`; Postgres (Neon free tier 호환); Cloudflare R2 bucket (옵션)
- 자체 git repo, 자체 gradle 빌드 (워크스페이스 루트의 KMP 멀티프로젝트와는 별개)
- 온보딩/개요 문서: [`README.md`](./README.md). (API 레퍼런스 정본은 OpenAPI `src/main/resources/openapi/vibi-bff.yaml` = `/swagger`, env 전체 목록은 `.env.example`. README 는 표를 걷어내고 산문 개요로 유지 — 상세는 이 두 정본과 CLAUDE.md/스킬로 위임.)

> 폴더·코드 패키지 (`com.vibi.bff.*`) 모두 `vibi` 통일.
>
> 워크스페이스 지도와 형제 디렉터리(`vibi-mobile/`, `vibi-landing/`) 관계는 루트 `CLAUDE.md` 참조.

## Build & Run

```bash
./gradlew compileKotlin                                       # build
./gradlew test                                                # all tests
./gradlew test --tests "com.vibi.bff.SeparationRoutesTest"    # single class
./gradlew run                                                 # server on :8080
```

Swagger UI at `/swagger`. 환경 변수 표 + API 상세는 `README.md`.

## Structure

- `Application.kt` — entry point. `loadDotenv` (real env > sys prop > .env), `loadConfig`, `DbBootstrap.init`, HttpClient + 모든 서비스 wiring, CallLogging (signed-URL token / OAuth code 마스킹), 종료 hook (httpClient + render/separation shutdown + dataSource close).
- `Constants.kt` — `MAX_UPLOAD_FILE_SIZE` 등.
- `config/AppConfig.kt` — HOCON + env var loading (StorageConfig·PersoConfig·SeparationConfig·AuthConfig·DbConfig). 각 config 의 `init { require(...) }` 가 boot 시 fail-fast.
- `db/Database.kt` (DbBootstrap, HikariCP) + Exposed 테이블 (`UsersTable` `(provider, providerSub)` unique + 크레딧 ledger / job analytics / external-call 로그).
- `plugins/` — CORS, Serialization (`AppJson = Json { ignoreUnknownKeys=true, encodeDefaults=true }`), `ErrorHandling` (StatusPages), `Routing`.
- `routes/` — `/api/v2` (`AuthRoutes` · `CreditRoutes` · `AssetRoutes` · `RenderRoutes` (+ `/inputs`, `/v3`) · `SeparationRoutes` · `AdminRoutes` · `DownloadResponder`·`MultipartUtils` 헬퍼). dev mock 으로 `/testdata/separation/*`.
- `model/` — `AuthModels` · `CreditModels` · `AdminModels` · `BffModels` · `PersoModels`. BFF DTO 와 Perso upstream DTO 분리, `@SerialName` 으로 snake_case ↔ camelCase 매핑.
- `service/` — `PersoClient`, `PersoPolling`, `FileStorageService`, `MediaTrimmer` (audio duration probe 전용), `RenderService`, `RenderInputCacheService`, `SeparationService`, `SeparationDispatcher`, `SeparationQueueRepository`, `SignedUrlService`, `AuthService`, `UserRepository`, `CreditRepository`, `AppleReceiptVerifier`, `GoogleReceiptVerifier`, `AdminRepository`, `ExternalApiCallsRepository`, `JobAnalyticsRepository`, `ObjectStore`, `FfmpegRunner`.

> 과거 `AutoSubtitleService` / `AutoDubService` / `LipSyncService` + Gemini 챗(`/chat`) + `/languages` + 관련 route·DTO 는 **commit `52f8d7c refactor(bff): sticker/자막/더빙 surface 절단`** 으로 일괄 제거. BFF·모바일 양쪽 모두 caller 까지 제거된 상태 (잔존 dead 메서드 없음) — 재도입 작업 시 본 commit 의 디프를 base 로 복원하지 말고 새로 설계할 것.

## 운영 surface 요약

```
POST   /api/v2/auth/{google,apple}        # ID Token → BFF JWT 교환
DELETE /api/v2/auth/account               # 계정 삭제 (인증 필요)
GET    /api/v2/credits                    # 잔액 (+ GET /credits/cost 견적)
POST   /api/v2/credits/purchase           # StoreKit2 / Play Billing 영수증 검증 → 적립
POST   /api/v2/credits/admin-grant        # admin 전용 수동 적립
POST   /api/v2/assets/upload-url          # R2 presigned PUT URL (v3 render 에셋 업로드)
POST   /api/v2/render/inputs              # video bytes 한 번만 업로드 → inputId
POST   /api/v2/render                     # multipart, multi-segment 합성 (inputId 재사용)
POST   /api/v2/render/v3                  # 에셋 by-reference (R2 key) 렌더
GET    /api/v2/render/{id}/{status,download}
POST   /api/v2/separate                   # audio-only multipart (m4a/mp3/wav, ≤100MB, 크레딧 reserve)
GET    /api/v2/separate/{id}              # stem + 서명 URL
GET    /api/v2/separate/{id}/stem/{stemId}        # token=*** 필수
GET    /api/v2/admin/*                     # 읽기전용 대시보드 (overview/stats/users/jobs, admin role)
GET    /api/v2/testdata/separation/*      # (dev mock)
```

## Known BFF bug patterns

새 multipart endpoint 추가 시 / 외부 API 호출 추가 시 여기 먼저 확인. 새 버그 만나면 append.

### Perso STT / 음원 분리는 전용 endpoint 사용 — `submitTranslate` 우회 트릭 폐기

**해결**: 음원 분리: `POST /video-translator/api/v1/projects/spaces/{spaceSeq}/audio-separation` → `GET .../download?target=originalVoiceSpeakers` 로 .tar 화자 collection, `GET .../download?target=originalSubBackground` 로 .wav background. (STT 는 BFF surface 절단 후 사용 안 함)

### Perso audio-separation `/download` 응답의 host 는 `portal-media.perso.ai`

**증상**: `/perso-storage/...` path 를 `https://api.perso.ai{path}` 로 GET 하면 404 + `Cache-Control: public, max-age=14400` (Cloudflare 가 응답 4시간 캐시) → fresh path 받아도 동일 path-host 조합은 4시간 락.

**원인**: `/download?target=...` 응답의 path 는 별도 storage host 의 것 (Azure Blob 기반 public CDN: `portal-media.perso.ai`). origin (api host) 은 그 path 모름. 인증 헤더 무관.

**해결 패턴**: `PersoConfig.storageBaseUrl = https://portal-media.perso.ai` 환경 변수 분리. `PersoClient.streamDownloadAuthorized` 가 path 가 `/perso-storage/...` 시작이면 storage host + 인증 헤더 X 로 분기. `getDownloadLinks` 의 path 매번 새로 받아도 cache key 는 동일 — 매 retry 마다 fresh path 받아 새 cache key 로 storage 직접 조회. SSRF 차단을 위해 host 화이트리스트(`PERSO_DOWNLOAD_ALLOWED_HOSTS`) 강제.

### Perso `originalSubBackground` 의미 화자 수에 따라 다름 — `originalBackgroundPath` 우선

**증상**: 화자 1명 영상 분리 시 `target=originalSubBackground` 결과가 화자 + BGM mix 그대로. 화자 2명+ 영상에선 BGM only 처럼 보임.

**원인**: Perso 의 `OriginalSubBackground` 의 'Sub' 가 화자 수에 따라 후처리 다름. 진짜 BGM only 는 file 명 `OriginalBaseBackground` (project info endpoint 의 `downloadPathInfo.originalBackgroundPath`).

**해결 패턴**: `SeparationService.downloadBackgroundStem` 이 `getProjectInfo(projectSeq).downloadPathInfo.originalBackgroundPath` 우선 시도, 누락 시 `originalSubBackground` fallback.

### Perso audio-separation — 업로드 codec 은 **PCM WAV / MP3**, FLAC 은 거부

**증상**: trim 출력을 `.flac` 으로 업로드하면 `getSasToken` → `uploadToBlob` → `registerMedia` → `submitAudioSeparation` 까지 전부 OK 인데 잡 폴링이 `progress=20% Uploading → 100% Failed (hasFailed=false)` 로 종료. BFF `PersoPolling` 이 `progressReason == "Failed"` 를 `PersoApiException(500)` 으로 변환 → 502 응답.

**원인**: Perso 분리 파이프라인이 FLAC 파일은 받아 두기만 하고 실제 처리 단계에서 거절. `hasFailed` 플래그가 false 라 단순 status 만 봐서는 안 잡힘. WAV PCM / MP3 는 정상 처리 (`Transcribing → Completed`).

**해결 패턴**: BFF surface 화이트리스트 = m4a / mp3 / wav (Perso 호환 audio). flac / video / ogg 등은 400 `unsupported_audio_format` 으로 즉시 reject. 모바일이 `AudioExtractor` (iOS `AVAssetExportPresetAppleM4A`) 로 추출하므로 신규 경로 default = m4a AAC. `PersoLiveSeparationIT` 가 회귀 가드 (env opt-in, fixture 부재 시 skip).

### Perso API path prefix — `/video-translator` 통일 (단, 일부 endpoint 만 `/file`)

**해결 패턴**: 새 PersoClient method 추가 시 endpoint group 확인 — 영상 lifecycle 은 `/video-translator/api/v1/projects/.../spaces/<spaceSeq>/...`, blob 업로드는 `/file/api/upload/...`.

### Perso 일시 5xx (F5001 INTERNAL_SERVER_ERROR) 는 backoff retry

**해결**: PersoClient method 안에서 5xx 만 짧은 backoff (3s × 3회) 재시도. 4xx 는 즉시 throw.

### Ktor HttpClient default timeout 으로 큰 파일 업로드 못 함

**증상**: 50MB+ 영상 Perso 업로드 시 `ConnectTimeoutException [connect_timeout=60000 ms]`.

**해결**: `Application.kt` 의 HttpClient 에 `connectTimeoutMillis=120s, requestTimeoutMillis=600s, socketTimeoutMillis=600s` + CIO `engine.endpoint.connectAttempts=3`. 환경 변수 (`HTTP_CONNECT_TIMEOUT_MS` 등) 로도 override 가능.

### Ktor 3.x `receiveMultipart()` default formFieldLimit 50MB

**증상**: 큰 영상 (50MB 이상) 업로드 시 `java.io.IOException: Multipart content length exceeds limit ... > 52428800` → 500.

**해결**: `call.receiveMultipart(formFieldLimit = MAX_UPLOAD_FILE_SIZE)` 명시. 적용 사이트: `SeparationRoutes`, `RenderRoutes` (+ `/inputs`).

### Multipart `spec` 파싱은 `AppJson` 사용 — 구 모바일 클라이언트 호환

**증상**: 모바일이 보낸 옛 spec JSON 에 BFF 가 모르는 필드가 섞여있으면 `MissingFieldException` 으로 폭사.

**해결**: `AppJson = Json { ignoreUnknownKeys=true, encodeDefaults=true }` 를 single source 로 두고, route 들이 `AppJson.decodeFromString` 사용. RenderConfig 도 동일 (`d865f19 fix(bff): /render 의 RenderConfig 파싱을 AppJson 으로`).

### Separation idempotency — 현재 미지원

이전엔 `editedRenderJobId + trim` 키 기반 dedup 이 있었으나 audio-only contract 로 surface 단순화되며 dead path 가 됨. 버튼 연타 방어는 일단 클라 측 책임 (모바일이 `submitting` flag 로 차단). 필요해지면 RenderInputCacheService 의 `sha256(bytes)[:16]` 패턴을 audio 에도 적용 가능.

### Mix 프리뷰는 모바일 로컬 — BFF mix endpoint 없음

stem volume 슬라이더 프리뷰는 모바일이 stem URL 들을 받아 AVAudioPlayer / ExoPlayer 다중 인스턴스로 로컬에서 합성. 최종 render 는 `/render` 의 `SeparationDirective.selections[]` 로 stem URL + volume 을 받아 ffmpeg `amix=normalize=0` 으로 한 번에 합성 — 서버 측 mix 잡 없음. (이전 `/separate/{id}/mix` endpoint + StemMixService 는 dead path 라 제거.)

### Render 의 amix `normalize=0` — stem + BGM + base 합칠 때 원본 loudness 보존

`amix=normalize=1` (ffmpeg default) 은 input 개수에 따라 자동 감쇠 → 사용자가 모든 stem unmute 했을 때 원본보다 조용. `normalize=0` 으로 원본 loudness 유지. (commit `92e1758 fix(separation): amix normalize=0`)

### Render 최종 mix 패스 `-c:v copy`

normalize 단계에서 이미 output 해상도·codec 으로 맞췄으므로 최종 mix 패스에서는 video 재인코드 없이 `-c:v copy`. 자막 burn-in/sticker overlay/image segment surface 가 절단된 이후의 단순화. (commit `6bcb392 perf(render)`)

### BGM atrim — 모바일 trim 시트 결과 그대로 ffmpeg sub-range mix

모바일 `BgmTrimSheet` 의 sub-range (start/end ms) 선택을 BgmClip 의 `sourceTrimStartMs/EndMs` 로 보내고, BFF 가 `atrim`+`asetpts` 로 sub-range 만 잘라 amix. (commit `0148a44 feat(render): BGM atrim 적용`)

### `/separate` 의 source — 모바일이 trim + audio extract → m4a 송신

모바일이 `AudioExtractor` (iOS `AVAssetExportPresetAppleM4A`) 로 trim + audio extract 까지 끝낸 m4a 를 multipart 로 보냄. BFF 는 받은 file 을 그대로 Perso 에 forward — 라우트에 ffmpeg / `MediaTrimmer.trim` / `extractAudioForUpload` 단계 없음. video / flac / ogg 등 화이트리스트 외는 400 `unsupported_audio_format`. 옛 `editedRenderJobId` / `MediaSourceResolver` 경로는 모바일 미사용이라 제거됨.

### Cloud Run egress 분리 — `R2_BUCKET` 설정 시 SigV4 presigned URL redirect

`/render/{id}/download` 와 `/separate/{id}/stem/{stemId}` 가 R2 에 upload → SigV4 presigned URL 302 redirect. R2 egress 무료 → Cloud Run egress 비용 0. 인스턴스 점유 분리. blank 면 `respondFile` streaming (로컬 dev). TTL 60..86400.

## Error handling

`plugins/ErrorHandling.kt`: `NotFoundException` → 404, `IllegalArgumentException` → 400, `PersoApiException` → 402/429/4xx/502 by upstream status. All return `ErrorResponse(error, detail?)`.

`34b7002 fix(security): raw exception 메시지가 UI 로 노출되던 부분 sanitize` — 외부 메시지를 그대로 detail 에 노출하지 말 것. PersoApiException 등 upstream 메시지는 status code 로만 매핑.

## File serving

Render outputs · separation stems **never static-mounted**. Two backends:

1. **로컬 streaming** (default, `R2_BUCKET` 비어있을 때) — `respondFile` 로 Ktor 가 직접 서브. 작은 산출물 / 로컬 dev 용.
2. **R2 SigV4 presigned URL redirect** (`R2_BUCKET` 설정 시) — 산출물을 Cloudflare R2 bucket 에 업로드 후 presigned URL (TTL `SIGNED_URL_TTL_SEC`) 로 302. R2 egress 무료 → Cloud Run egress 비용 0. 인스턴스도 큰 응답 동안 점유되지 않음.

stem 다운로드는 HMAC-signed `?token=...` 요구. Render download 는 `jobId` 자체가 random — 단 짧은 TTL 후 GC.

## Testing

Ktor `testApplication` + MockK. 테스트 파일은 라우트·서비스와 1:1 (`SeparationRoutesTest.kt` ↔ `SeparationRoutes.kt`). `RenderService` 는 unit test 없음 — 실제 ffmpeg 스폰. `MediaTrimmerTest` / `RenderServiceUtilsTest` / `UserRepositoryTest` / `SignedUrlServiceTest` / `RenderInputCacheServiceTest` 등 헬퍼 단위는 unit test 활발.

## Task-specific skills

해당 서브시스템 작업 시 로드 — 본 파일을 잡스럽게 만들지 않기 위해 분리:

- `render-pipeline` — ffmpeg 파이프라인, `RenderConfig`, segments, BGM atrim/amix, Windows path escaping, RenderInputCache.
- `separation-pipeline` — Perso upload 3-step, audio-separation 잡 / polling / download, stem plan mapping, HMAC URL signing.
- `review` — code review checklist (트리거: "리뷰")
