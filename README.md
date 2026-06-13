# vibi BFF

Kotlin/Ktor Backend-For-Frontend for the vibi mobile app (Android + iOS, KMP/CMP).
Current surface:

- Proxies **Perso AI** for audio source separation (background / all-voice /
  per-speaker stems). Stem volume preview is mixed locally on the mobile client;
  final composition merges stems into the render pipeline server-side.
- Runs a local **ffmpeg** render pipeline (multi-segment concat, BGM
  `atrim`+`amix` sub-range mixing, separation stem `amix normalize=0`).
- Gateways **Google Sign In** + **Apple Sign In** ID Token verification → upserts
  the user in **Postgres** → issues an HS256 access token whose `sub` is the
  internal UUID (reusable as IAP `appAccountToken`).
- Meters usage with a **credit** ledger (Postgres): separation reserves credits up
  front and refunds on failure. Top-ups validate **StoreKit2 / Play Billing** receipts
  before granting. A read-only **admin** dashboard serves `/api/v2/admin/*` analytics.
- Optionally redirects large render / stem downloads to **Cloudflare R2** SigV4 presigned URLs (egress free)
  so Cloud Run egress and instance occupancy decouple. Mobile can also upload render
  assets directly to R2 (`/api/v2/assets/upload-url`) and render them by reference (`/api/v2/render/v3`).

> Surface previously exposed but **removed** in commit `52f8d7c` (sticker
> overlays, subtitle burn-in, auto-subtitle/Perso STT, auto-dub, lipsync, Gemini
> SRT translation). The OpenAPI spec (`src/main/resources/openapi/vibi-bff.yaml`)
> reflects only the current surface.

> Detailed architecture notes live in [`CLAUDE.md`](./CLAUDE.md). The OpenAPI
> spec is served at `/swagger` when the app is running.

## Prerequisites

- **JDK 21**
- **ffmpeg** and **ffprobe** on `PATH` (required by the render pipeline)
- A **Perso AI API key** and workspace `spaceSeq` (required for separation)
- **Postgres** (Neon free tier is fine) — required for user upsert
- At least one **Google OAuth client id** (iOS / Android / Web) — required to boot
- *(Optional)* **Apple Sign In** client ids (iOS bundle id) — blank disables Apple
- *(Optional)* A **Cloudflare R2 bucket** — large render / stem downloads redirect to SigV4
  signed URLs instead of streaming through Cloud Run

On Windows, verify:

```bash
ffmpeg -version
ffprobe -version
```

## Setup

```bash
cp .env.example .env
# then edit .env and set PERSO_API_KEY + PERSO_SPACE_SEQ
```

Environment variables (also loadable from system env):

### Perso AI

| Key                            | Default                       | Description                                              |
|--------------------------------|-------------------------------|----------------------------------------------------------|
| `PERSO_API_KEY`                | —                             | **Required.** Perso AI API key (separation).             |
| `PERSO_SPACE_SEQ`              | —                             | **Required.** Your Perso workspace id (`GET /portal/api/v1/spaces`). |
| `PERSO_BASE_URL`               | `https://api.perso.ai`        | Perso API host.                                          |
| `PERSO_STORAGE_BASE_URL`       | `https://portal-media.perso.ai` | Perso storage CDN host. `/perso-storage/...` download paths go here without auth headers. |
| `PERSO_DOWNLOAD_ALLOWED_HOSTS` | `portal-media.perso.ai`       | Comma-separated SSRF allow-list. Host of any Perso-issued download URL must be in this set (api/storage hosts are added automatically). |
| `PERSO_POLL_INTERVAL_MS`       | `15000`                       | Perso job polling interval.                              |
| `PERSO_MAX_POLL_MINUTES`       | `30`                          | Separation job polling timeout (1..120).                 |

### Server / storage

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `PORT`                       | `8080`                   | Server port.                                         |
| `STORAGE_PATH`               | `./storage`              | Local file storage root (`uploads/`, `render/`, `separation/`, `render-input-cache/`, `edited-source/`). |
| `BFF_BASE_URL`               | `http://localhost:8080`  | Public base URL used in signed download links.       |
| `CORS_ALLOWED_ORIGINS`       | *(blank = any)*          | Comma-separated allow-list. Set a sentinel such as `android-only.invalid` to lock out browsers. |
| `R2_BUCKET`                  | *(blank = disabled)*     | When set, large render / separation downloads upload to this Cloudflare R2 bucket and respond with a `302` to a SigV4 presigned URL. **R2 egress free** → zero Cloud Run egress cost. Blank falls back to `respondFile` streaming. |
| `R2_ACCOUNT_ID`              | —                        | Cloudflare account ID (32-char hex from dashboard URL). Required when `R2_BUCKET` set. |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | — | R2 API token (Object Read & Write). Cloudflare dashboard → R2 → Manage API Tokens. |
| `SIGNED_URL_TTL_SEC`         | `900`                    | Presigned URL TTL in seconds (60..86400).            |
| `RENDER_MAX_CONCURRENT`      | *(auto: CPU/2)*          | ffmpeg fan-out cap. Override when containerized hosts misreport `availableProcessors`. |
| `RENDER_INPUT_CACHE_TTL_HOURS` | `24`                   | TTL of the shared `/render/inputs` slot.             |
| `HTTP_CONNECT_TIMEOUT_MS` / `HTTP_REQUEST_TIMEOUT_MS` / `HTTP_SOCKET_TIMEOUT_MS` | `120000` / `600000` / `600000` | Upstream HttpClient timeouts. Bump when debugging slow Perso uploads. |

### Separation lifecycle

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `SEPARATION_SIGNING_SECRET`  | —                        | **Required.** HMAC key (≥32 chars) for stem URL signing. `openssl rand -hex 32`. |
| `SEPARATION_ABANDON_TTL_MS`  | `604800000` (7 days)     | Reap READY-but-unclaimed separations after this many ms. |
| `SEPARATION_URL_TTL_SEC`     | `604800` (7 days)        | Stem download URL token lifetime. Must be ≤ abandonTtlMs so a token that outlives the reaped job never appears. |

### Auth & Postgres

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `GOOGLE_OAUTH_CLIENT_IDS`    | —                        | **Required.** Comma-separated Google OAuth client IDs (iOS / Android / Web). `aud` of ID token must match. |
| `APPLE_OAUTH_CLIENT_IDS`     | *(blank = disabled)*     | Comma-separated Apple Sign In client IDs (typically iOS bundle id `com.vibi.ios`). Blank → `/auth/apple` returns 503. |
| `AUTH_JWT_SECRET`            | —                        | **Required.** HMAC-SHA256 key (≥32 chars) for own access token signing. `openssl rand -hex 32`. |
| `AUTH_JWT_EXPIRY_SECONDS`    | `604800` (7 days)        | Issued access token lifetime (60..7776000).          |
| `DATABASE_URL`               | —                        | **Required.** JDBC URL for Postgres (Neon / Cloud SQL). Format: `jdbc:postgresql://<host>/<db>?sslmode=require`. H2 also accepted for local tests. |
| `DB_USER`                    | —                        | **Required.** DB role.                               |
| `DB_PASSWORD`                | —                        | **Required.** DB password.                           |
| `DB_MAX_POOL`                | `5`                      | HikariCP pool size (1..50). Neon free tier 100 conn cap. |

## Build & Run

```bash
# Compile
./gradlew compileKotlin

# Run tests (all)
./gradlew test

# Run a single test class
./gradlew test --tests "com.vibi.bff.SeparationRoutesTest"

# Run the server (port 8080 by default)
./gradlew run
```

Windows users can use `.\gradlew ...` equivalently. Build output is directed
to `C:/tmp/vibi-bff-build` (configured in `build.gradle.kts`).

Once running:
- Swagger UI: <http://localhost:8080/swagger>
- Render outputs and separation stems are **not** static-mounted —
  see API section below. Each download either streams via `respondFile` (default)
  or 302-redirects to a Cloudflare R2 SigV4 presigned URL (when `R2_BUCKET` is set).

## API Overview

The mobile client (Android + iOS, KMP/CMP) targets `/api/v2` exclusively.
v1 has been retired.

### v2

| Method | Path                                         | Notes                                                         |
|--------|----------------------------------------------|---------------------------------------------------------------|
| POST   | `/api/v2/auth/google`                        | Google ID Token → BFF JWT (see [Sign-In](#sign-in)).          |
| POST   | `/api/v2/auth/apple`                         | Apple ID Token → BFF JWT (503 when `APPLE_OAUTH_CLIENT_IDS` blank). |
| DELETE | `/api/v2/auth/account`                       | Delete the authenticated user and cascade their jobs.         |
| GET    | `/api/v2/credits`                            | Current credit balance (auth required).                       |
| GET    | `/api/v2/credits/cost`                       | Separation cost estimate (`?durationMs=…` → credits).         |
| POST   | `/api/v2/credits/purchase`                   | StoreKit2 / Play Billing receipt validation → credit grant (idempotent on `transactionId`). |
| POST   | `/api/v2/credits/admin-grant`                | Manual credit grant. Requires `admin` role.                   |
| POST   | `/api/v2/assets/upload-url`                  | R2 presigned PUT URL for a render asset (`sha256`-deduped). Feeds `/render/v3`. |
| POST   | `/api/v2/render/inputs`                      | Multipart `video` upload → `{inputId, expiresAt, videoSizeBytes}`. Reused by N variant renders. `inputId = sha256(video)[:16]`. |
| POST   | `/api/v2/render`                             | Multipart render job (see [Render](#render-endpoint)). Optional `inputId` form field replaces the video file part. |
| POST   | `/api/v2/render/v3`                          | Asset-by-reference render — segments/BGM reference R2 keys uploaded via `/assets/upload-url`, no multipart bytes. |
| GET    | `/api/v2/render/{jobId}/status`              | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` + `progress`. |
| GET    | `/api/v2/render/{jobId}/download`            | Binary mp4. With `R2_BUCKET`, `302` to an R2 presigned URL.   |
| POST   | `/api/v2/separate`                           | Multipart separation job (see [Separation](#separation-endpoints)). Audio-only upload (`m4a` / `mp3` / `wav`, ≤100MB). Reserves credits. |
| GET    | `/api/v2/separate/{jobId}`                   | Status + signed stem URLs when `READY`.                       |
| GET    | `/api/v2/separate/{jobId}/stem/{stemId}`     | Stem audio stream (requires `?token=…`).                      |
| GET    | `/api/v2/admin/*`                            | Read-only analytics dashboard (`overview`, `stats/*`, `users`, `jobs/active`). Requires `admin` role. |
| GET    | `/api/v2/testdata/separation/list`           | (dev mock) `testdata/<startSec>-<endSec>/` folder listing.    |
| GET    | `/api/v2/testdata/separation/{folder}/{stem}` | (dev mock) Stem audio stream (no auth).                      |

> **Retired surface** (commit `52f8d7c`): `/api/v2/subtitles*`, `/api/v2/autodub*`,
> `/api/v2/lipsync*`, the Gemini-backed chat assistant (`/api/v2/chat`), `/api/v2/languages`,
> sticker overlays on `/render`, and subtitle burn-in on `/render`. These were removed from
> **both the BFF and the mobile client** (no dead caller methods remain) — re-introducing any
> of them should be a fresh design, not a `git revert`.

### Render endpoint

`POST /api/v2/render` is a multipart upload with a JSON `config` form-part plus
one or more files. The service runs ffmpeg in a background coroutine and
assigns a `jobId` for polling.

#### Multipart fields

| Field                 | Description                                              |
|-----------------------|----------------------------------------------------------|
| `video`               | *(legacy)* Single source video                           |
| `video_0`, `video_1`, … | Source videos referenced by `segments[].sourceFileKey` |
| `segment_image_0`, …  | Still images used as IMAGE-type segments                 |
| `bgm_0`, `bgm_1`, …   | BGM tracks (referenced by `bgmClips[]`)                  |
| `config`              | JSON-encoded `RenderConfig` (form item, not a file)      |

#### RenderConfig

```jsonc
// Multi-segment mode
{
  "segments": [
    { "sourceFileKey": "video_0", "type": "VIDEO", "order": 0,
      "durationMs": 30000, "trimStartMs": 0, "trimEndMs": 30000,
      "width": 1920, "height": 1080 },
    { "sourceFileKey": "segment_image_0", "type": "IMAGE", "order": 1,
      "durationMs": 5000,
      "imageWidthPct": 80, "imageHeightPct": 80 },
    { "sourceFileKey": "video_1", "type": "VIDEO", "order": 2,
      "durationMs": 20000, "trimStartMs": 0, "trimEndMs": 20000,
      "width": 1920, "height": 1080 }
  ],
  "bgmClips": [
    { "audioFileKey": "bgm_0", "startMs": 0, "volume": 0.4,
      "sourceTrimStartMs": 0, "sourceTrimEndMs": 30000 }
  ]
}
```

Output resolution comes from `segments[0].width`/`height` (multi-segment) or
ffprobe on the source (legacy).

#### Pipeline (multi-segment)

1. **Per-segment normalization** — VIDEO: input-side seek + scale/pad to output resolution + silent AAC track. IMAGE: `-loop 1` + letterbox + silent AAC track.
2. **Concat demuxer** (`-c copy`) — all normalized clips share codec, fps, resolution, and audio layout, so no re-encode here.
3. **Final pass** (`-c:v copy`) — BGM clips `atrim`+`asetpts` sub-ranged then `amix` with original audio.

### Separation endpoints

`POST /api/v2/separate` accepts an **already-trimmed audio file** (m4a / mp3 / wav),
submits it to Perso's audio-separation pipeline, and exposes the byproduct stems for the
mobile client to pick and locally mix for preview. The mobile client handles trim +
audio extract locally (iOS `AVAssetExportPresetAppleM4A`), so the BFF performs no
ffmpeg work before the upstream upload. Final stem amix happens once at `/render`
time via `SeparationDirective.selections[]` — there is no server-side mix endpoint.

#### Multipart fields

| Field  | Description                                                        |
|--------|--------------------------------------------------------------------|
| `file` | Audio bytes — extension must be `m4a` / `mp3` / `wav`, ≤100MB. Video / flac / ogg are rejected with `400 unsupported_audio_format`. |
| `spec` | JSON `SeparationSpec` (form item, not a file)                      |

```jsonc
// SeparationSpec
{
  "sourceLanguageCode": "auto"   // "auto" or ISO code like "ko"
}
```

Errors:

| Condition                                | Response                                  |
|------------------------------------------|-------------------------------------------|
| `file` missing                           | `400` (`file is required`)                |
| `spec` missing                           | `400` (`spec is required`)                |
| File extension outside whitelist         | `400 unsupported_audio_format`            |
| Multipart > `MAX_SEPARATION_AUDIO_SIZE`  | `413` (Ktor multipart limit)              |
| Balance insufficient                     | `402 insufficient_credits`                |

#### Stems

Once the job reaches `READY`, `GET /api/v2/separate/{jobId}` returns signed
URLs for each available stem:

| `stemId`                       | Label         | Source                            |
|--------------------------------|---------------|-----------------------------------|
| `background`                   | 배경음        | Perso `backgroundAudio` (BGM + effects + reactions — Perso does not split these apart) |
| `voice_all`                    | 모든 화자     | Perso `originalVoiceAudio`        |
| `speaker_0`, `speaker_1`, …    | 화자 1, 2, …  | Perso `originalVoiceSpeakers` (ZIP, unzipped locally) |

Per-speaker stems appear only when `numberOfSpeakers >= 2` and Perso produces
a speaker collection.

#### Typical flow

1. `POST /api/v2/separate` with the source file + spec → `{ "jobId": "sep-…" }`
2. Poll `GET /api/v2/separate/{jobId}` every 5s until `status=READY`; response
   carries `stems[]` with signed `url` fields valid for `SEPARATION_URL_TTL_SEC`.
3. Mobile downloads each stem via `GET /api/v2/separate/{jobId}/stem/{stemId}?token=…`
   and mixes them locally (AVAudioPlayer / ExoPlayer multi-instance) so volume
   slider previews are realtime and free of server round-trips.
4. On save, mobile builds a `RenderConfig` whose `separationDirectives[].selections[]`
   carries each stem's signed URL + final volume — `/render` amixes them into the
   final mp4 in a single ffmpeg pass.

**Lifecycle note:** abandoned `READY` separations are reaped after
`SEPARATION_ABANDON_TTL_MS` (7 days by default). There is no explicit dispose
endpoint — the mobile client just stops referencing the job when done.

#### Security

- Stems are **never** static-mounted; all downloads require a
  short-lived HMAC token bound to `{jobId, resourceId, expiry}`.
- Tokens appear in response `url` fields. Access logs mask `?token=***` so
  signatures don't leak to log aggregators.
- Rotate `SEPARATION_SIGNING_SECRET` carefully — changing it invalidates every
  outstanding token at once.

### Sign-In

Both providers follow the same pattern: the native SDK (GoogleSignIn iOS SPM /
Android Credential Manager / Apple `AuthenticationServices`) returns an ID Token
which the mobile client forwards to BFF. BFF verifies, upserts a row in `users`
(`(provider, provider_sub)` unique), and returns its own HS256 JWT whose `sub`
is the internal UUID. That same UUID is later reusable as IAP `appAccountToken`.

#### Google

`POST /api/v2/auth/google` accepts `{ idToken }`. Verification calls
`https://oauth2.googleapis.com/tokeninfo`; `aud` must match one of the values in
`GOOGLE_OAUTH_CLIENT_IDS` (iOS / Android / Web client ids — typically all three
are listed since one BFF serves both platforms and the web landing).

#### Apple

`POST /api/v2/auth/apple` accepts `{ idToken, fullName? }`. Verification path is
JWKS RS256 (`https://appleid.apple.com/auth/keys`) with 24h-cached public keys;
`aud` must match `APPLE_OAUTH_CLIENT_IDS`. `fullName` is Apple's first-login-only
payload — server uses it for `users.display_name` only on insert.

To enable in production:
1. Apple Developer Portal → Identifiers → your App ID → **Capabilities** → check
   "Sign in with Apple". Requires a **paid Apple Developer Program** membership;
   Personal Team builds will hit runtime error 1000.
2. Set `APPLE_OAUTH_CLIENT_IDS` (Secret Manager or env) to your iOS bundle id
   (e.g. `com.vibi.ios`).
3. iOS app must carry the `com.apple.developer.applesignin` entitlement —
   `iosApp/iosApp.entitlements` is wired via `project.yml`. Run
   `xcodegen generate` after pulling.

## Project Layout

```
src/main/kotlin/com/vibi/bff/
├── Application.kt              # Ktor entry, DI wiring, HttpClient timeouts, shutdown hooks
├── Constants.kt                # MAX_UPLOAD_FILE_SIZE, etc.
├── config/AppConfig.kt         # HOCON + env var loading (Storage, Perso, Separation, Auth, Db)
├── db/
│   ├── Database.kt             # HikariCP + Exposed bootstrap
│   └── UsersTable.kt           # (provider, providerSub) unique
├── plugins/
│   ├── Cors.kt, Serialization.kt, Routing.kt
│   └── ErrorHandling.kt        # StatusPages → ErrorResponse
├── model/
│   ├── AuthModels.kt           # GoogleAuthRequest / AppleAuthRequest / AuthResponse
│   ├── CreditModels.kt         # Balance / cost / purchase / admin-grant DTOs
│   ├── AdminModels.kt          # Dashboard KPI / stats response DTOs
│   ├── BffModels.kt            # Render (v2 + v3) / Separation / Asset DTOs
│   └── PersoModels.kt          # Perso upstream DTOs
├── routes/
│   ├── AuthRoutes.kt           # /auth/google · /auth/apple · /auth/account (DELETE)
│   ├── CreditRoutes.kt         # /credits · /credits/cost · /credits/purchase · /credits/admin-grant
│   ├── AssetRoutes.kt          # /assets/upload-url (R2 presigned PUT)
│   ├── RenderRoutes.kt         # /render · /render/inputs · /render/v3 · /render/{id}/{status,download}
│   ├── SeparationRoutes.kt     # /separate · /separate/{id} · /stem
│   ├── AdminRoutes.kt          # /admin/* read-only dashboard (admin role)
│   ├── DownloadResponder.kt    # respondFile / R2-redirect dispatcher
│   └── MultipartUtils.kt       # parsing helpers
└── service/
    ├── PersoClient.kt          # Perso upstream wrapper (SAS upload / separate / poll / download, SSRF allow-list, 5xx backoff)
    ├── PersoPolling.kt         # Job-status polling primitive
    ├── AuthService.kt          # Google tokeninfo + Apple JWKS RS256 + JWT issuance
    ├── UserRepository.kt       # Exposed-backed user upsert
    ├── CreditRepository.kt     # Credit ledger (reserve / refund / grant)
    ├── AppleReceiptVerifier.kt # StoreKit2 transaction verification
    ├── GoogleReceiptVerifier.kt # Play Billing purchase token verification
    ├── AdminRepository.kt      # Dashboard analytics queries
    ├── ExternalApiCallsRepository.kt # Perso call logging (latency / failure rate)
    ├── JobAnalyticsRepository.kt # Render / separation job analytics
    ├── FileStorageService.kt   # Local blob storage (uploads/, render/, separation/)
    ├── MediaTrimmer.kt         # ffprobe-based audio duration probe (KPI / credit metering)
    ├── RenderService.kt        # ffmpeg orchestration (normalize → concat → final mix -c:v copy)
    ├── RenderInputCacheService.kt # /render/inputs slot (sha256 inputId, TTL sweep)
    ├── SeparationService.kt    # Perso-backed stem separation jobs (idempotency, TTL reap)
    ├── SeparationDispatcher.kt # Dequeue + submit QUEUED separations
    ├── SeparationQueueRepository.kt # In-memory queue (position / avg time)
    ├── SignedUrlService.kt     # HMAC-SHA256 download URL signer
    ├── ObjectStore.kt          # Optional Cloudflare R2 upload + SigV4 presigned URL redirect
    └── FfmpegRunner.kt         # ffmpeg / ffprobe process spawning + stderr capture
```

## Testing

Tests run with Ktor `testApplication` + MockK. Test files mirror route files
(e.g. `SeparationRoutesTest.kt` covers `SeparationRoutes.kt`). The render
service is not currently covered by tests — it spawns real ffmpeg processes
and is exercised end-to-end against the mobile client.

## Troubleshooting

- **`PERSO_API_KEY must be set`** — populate `.env` or the env var; the boot fail-fast guards against missing credentials.
- **`SEPARATION_SIGNING_SECRET must be at least 32 chars`** — the default template has a placeholder; generate one with `openssl rand -hex 32` and paste into `.env`.
- **Render jobs stuck in `FAILED` with "ffmpeg exited with code …"** — check the server logs; the last 500 bytes of ffmpeg stderr are emitted on failure. The most common causes are missing `ffmpeg`/`ffprobe` on `PATH` or a segment referencing an unknown `sourceFileKey`.
- **Separation status returns 403 after a while** — stem tokens expire after `SEPARATION_URL_TTL_SEC`. Re-call `GET /api/v2/separate/{jobId}` to mint fresh signed URLs.
- **Perso returns 402** — workspace quota is exhausted. Status maps to `402 Payment Required` on the BFF response.
