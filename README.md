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
- Meters usage with a **credit** ledger (Postgres): separation is billed
  **length-proportionally (1 credit per started 5 minutes)**, reserved up front and
  refunded on failure. New users get a 3-credit signup bonus. Top-ups validate
  **StoreKit2 / Play Billing** receipts before granting.
- Serves a read-only **admin** dashboard (`/api/v2/admin/*`): KPI overview, usage /
  signup trends, Perso reliability, a revenue / IAP panel, and a job success/failure
  breakdown. A static admin SPA mounts at `/<ADMIN_SLUG>/`.
- Optionally redirects large render / stem downloads to **Cloudflare R2** SigV4
  presigned URLs (egress free) so Cloud Run egress and instance occupancy decouple.
- Hardened for launch: per-route **rate limiting**, sanitized errors, SSRF
  allow-listing, log masking, TLS-enforced DB URLs, and optional **Sentry**.

> Surface previously exposed but **removed** in commit `52f8d7c` (sticker overlays,
> subtitle burn-in, auto-subtitle/Perso STT, auto-dub, lipsync, Gemini SRT
> translation) — re-introducing any of them should be a fresh design, not a `git revert`.
> The OpenAPI spec (`src/main/resources/openapi/vibi-bff.yaml`, served at `/swagger`
> when `ENABLE_SWAGGER=true`) reflects only the current surface.

## Quickstart

```bash
# 1. Prereqs: JDK 21, plus ffmpeg + ffprobe on PATH (the render pipeline shells out to them).
ffmpeg -version && ffprobe -version

# 2. Fill the required env vars.
cp .env.example .env
#   PERSO_API_KEY, PERSO_SPACE_SEQ, GOOGLE_OAUTH_CLIENT_IDS, AUTH_JWT_SECRET,
#   SEPARATION_SIGNING_SECRET, DATABASE_URL, DB_USER, DB_PASSWORD
#   (generate the HMAC secrets with: openssl rand -hex 32)

# 3. Run (port 8080).
./gradlew run
```

**Conventions**

- Base URL: `http://localhost:8080` locally; production is the deployed Cloud Run URL.
- Every `/api/v2` call except `/auth/*` and signed downloads needs `Authorization: Bearer <accessToken>`.
- Errors are always `{ "error": "<code>", "detail": "<optional>" }` with the matching HTTP status.
- `GET /healthz` → `{"status":"ok"}` (no auth, not rate-limited) is the liveness probe.

**End-to-end flow** (`idToken` comes from the native sign-in SDK):

```bash
BASE=http://localhost:8080

# Exchange a Google ID token for a BFF JWT.
curl -sX POST $BASE/api/v2/auth/google \
  -H 'Content-Type: application/json' -d '{"idToken":"<google-id-token>"}'
# → { "accessToken":"<jwt>", "expiresAt":1750000000,
#     "user":{ "sub":"<uuid>", "email":"…", "name":"…", "role":"user" } }

TOKEN=<jwt>

# Check the balance (new users start at 3 credits).
curl -s $BASE/api/v2/credits -H "Authorization: Bearer $TOKEN"
# → { "balance": 3 }

# Submit an already-trimmed audio file (multipart: file + spec). Reserves credits.
curl -sX POST $BASE/api/v2/separate -H "Authorization: Bearer $TOKEN" \
  -F 'file=@clip.m4a' -F 'spec={"sourceLanguageCode":"auto"}'
# → 202 { "jobId": "sep-…" }

# Poll until READY; the response carries signed stem URLs.
curl -s $BASE/api/v2/separate/sep-… -H "Authorization: Bearer $TOKEN"
# → { "status":"READY", "stems":[ { "stemId":"voice_all",
#     "url":"/api/v2/separate/sep-…/stem/voice_all?token=…" }, … ] }

# Download a stem — the ?token signature is the gate; no Bearer needed here.
curl -s "$BASE/api/v2/separate/sep-…/stem/voice_all?token=…" -o voice_all.wav
```

Full config reference is under [Configuration](#configuration); build/test commands under
[Build & test](#build--test).

## API Reference

The mobile client (Android + iOS, KMP/CMP) targets `/api/v2` exclusively. v1 is retired.
`GET /healthz`, `/swagger` (gated by `ENABLE_SWAGGER`), and the admin SPA (`/<ADMIN_SLUG>/`,
gated by `ADMIN_SLUG`) sit outside `/api/v2` and outside rate limiting. Render outputs and
separation stems are never static-mounted — each download either streams via `respondFile`
or `302`-redirects to an R2 presigned URL when `R2_BUCKET` is set.

### v2 endpoints

| Method | Path                                         | Notes                                                         |
|--------|----------------------------------------------|---------------------------------------------------------------|
| POST   | `/api/v2/auth/google`                        | Google ID Token → BFF JWT (see [Sign-In](#sign-in)). Rate-limited. |
| POST   | `/api/v2/auth/apple`                         | Apple ID Token → BFF JWT (503 when `APPLE_OAUTH_CLIENT_IDS` blank). Rate-limited. |
| DELETE | `/api/v2/auth/account`                       | Delete the authenticated user, erase their render/separation content, cascade their jobs. |
| GET    | `/api/v2/credits`                            | Current credit balance → `{ "balance": N }`.                  |
| GET    | `/api/v2/credits/cost`                       | Separation cost estimate (`?durationMs=…` → credits). Same formula as the actual charge. |
| POST   | `/api/v2/credits/purchase`                   | StoreKit2 / Play Billing receipt → credit grant. Idempotent on `(platform, transactionId)`. `400 invalid_platform`/`unknown_product`/`iap_unconfigured`/`receipt_invalid`, `502 receipt_verify_unavailable`. |
| POST   | `/api/v2/credits/admin-grant`                | Manual grant (no receipt). `admin` role. 24h cap `ADMIN_GRANT_DAILY_CAP` → `429`. |
| POST   | `/api/v2/assets/upload-url`                  | R2 presigned PUT URL for a render asset (`sha256`-deduped). Feeds `/render/v3`. |
| POST   | `/api/v2/render/inputs`                      | Multipart `video` upload → `{inputId, expiresAt, videoSizeBytes}`, reused by N renders (`inputId = sha256(video)[:16]`). Rate-limited. |
| POST   | `/api/v2/render`                             | Multipart render job (see [Render](#render-endpoint)). Optional `inputId` form field replaces the video part. Rate-limited. |
| POST   | `/api/v2/render/v3`                          | Asset-by-reference render — segments/BGM reference R2 keys, no multipart bytes. |
| GET    | `/api/v2/render/{jobId}/status`              | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` + `progress`. |
| GET    | `/api/v2/render/{jobId}/download`            | Binary mp4. With `R2_BUCKET`, `302` to an R2 presigned URL.   |
| POST   | `/api/v2/separate`                           | Multipart separation job (see [Separation](#separation-endpoints)). Audio-only (`m4a`/`mp3`/`wav`, ≤ `MAX_SEPARATION_AUDIO_MB`). Reserves credits. Rate-limited. |
| GET    | `/api/v2/separate/{jobId}`                   | Status + signed stem URLs when `READY`.                       |
| GET    | `/api/v2/separate/{jobId}/stem/{stemId}`     | Stem audio stream (gated by `?token=…`).                      |

### Admin (read-only, `admin` role)

All `/api/v2/admin/*` endpoints are `GET`, require the `admin` role, and are read-only.
The admin role is DB-driven (`users.role`).

| Path                                     | Notes                                                         |
|------------------------------------------|---------------------------------------------------------------|
| `/admin/overview`                        | KPI cards (total users / renders / separations, total source duration, 7-day active users). |
| `/admin/stats/daily`                     | Daily render / separation / duration trend (`?from`/`?to` ISO, default 30d, max 90d). |
| `/admin/stats/signups`                   | Daily signups with provider (google / apple) split.          |
| `/admin/stats/external-calls`            | Daily Perso call counts + failure rate + p95 latency.        |
| `/admin/stats/duration-histogram`        | Video-length histogram (5 buckets).                          |
| `/admin/users`                           | User list (`?limit` 1..200 default 50, `?offset`, optional `?q=` search). |
| `/admin/users/{userId}/jobs`             | Per-user render jobs + per-video separation counts (`400 invalid_user_id` on bad UUID). |
| `/admin/jobs/active`                     | In-progress render + separation jobs (stuck detection).      |
| `/admin/jobs/status-breakdown`           | Per-type success / failure / in-progress breakdown.          |
| `/admin/revenue`                         | Revenue / IAP summary (paying users, credits sold, apple/google split — credits, not currency; admin grants excluded). |
| `/admin/revenue/daily`                   | Daily IAP trend (apple/google credits, purchase count).      |

> Dev mock routes `GET /api/v2/testdata/separation/{list,…}` are mounted only when
> `ENABLE_TESTDATA_MOCK=true`.

### Rate limiting

An in-process token-bucket limiter (Ktor `RateLimit` plugin) wraps three route groups;
exceeding a limit returns `429`. Status-polling GETs and stem/render downloads are
intentionally **not** limited.

| Group                                    | Limit    | Key                              |
|------------------------------------------|----------|----------------------------------|
| `/api/v2/auth/*`                         | 10 / min | client IP (blocks signup-bonus farming). |
| `POST /api/v2/render` + `/render/inputs` | 10 / min | JWT subject (falls back to IP).  |
| `POST /api/v2/separate`                  | 20 / min | JWT subject (falls back to IP).  |

The client IP comes from `X-Forwarded-For` with `RATE_LIMIT_TRUSTED_PROXY_HOPS` hops
skipped from the right — set `1` behind Cloud Run so the key is the real, unforgeable
client IP (leftmost is caller-spoofable).

### Render endpoint

`POST /api/v2/render` is a multipart upload with a JSON `config` form-part plus one or
more files. The service runs ffmpeg in a background coroutine and assigns a `jobId` for polling.

| Field                   | Description                                              |
|-------------------------|----------------------------------------------------------|
| `video`                 | *(legacy)* Single source video                           |
| `video_0`, `video_1`, … | Source videos referenced by `segments[].sourceFileKey`   |
| `segment_image_0`, …    | Still images used as IMAGE-type segments                 |
| `bgm_0`, `bgm_1`, …     | BGM tracks (referenced by `bgmClips[]`)                  |
| `config`                | JSON-encoded `RenderConfig` (form item, not a file)      |

```jsonc
// RenderConfig — multi-segment mode
{
  "segments": [
    { "sourceFileKey": "video_0", "type": "VIDEO", "order": 0,
      "durationMs": 30000, "trimStartMs": 0, "trimEndMs": 30000,
      "width": 1920, "height": 1080 },
    { "sourceFileKey": "segment_image_0", "type": "IMAGE", "order": 1,
      "durationMs": 5000, "imageWidthPct": 80, "imageHeightPct": 80 },
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

Output resolution comes from `segments[0].width`/`height` (multi-segment) or ffprobe on
the source (legacy). Pipeline: per-segment normalization (scale/pad to output resolution +
silent AAC track) → concat demuxer (`-c copy`, no re-encode) → final pass (`-c:v copy`) where
BGM clips are `atrim`+`asetpts` sub-ranged then `amix`-ed (`normalize=0`) with the original audio.

### Separation endpoints

`POST /api/v2/separate` accepts an **already-trimmed audio file** (m4a / mp3 / wav), submits
it to Perso's audio-separation pipeline, and exposes the byproduct stems for the mobile client
to pick and locally mix for preview. The client handles trim + audio extract locally (iOS
`AVAssetExportPresetAppleM4A`), so the BFF does no ffmpeg work before the upstream upload.
Final stem amix happens once at `/render` time via `SeparationDirective.selections[]` — there
is no server-side mix endpoint.

| Field  | Description                                                        |
|--------|--------------------------------------------------------------------|
| `file` | Audio bytes — extension `m4a` / `mp3` / `wav`, ≤ `MAX_SEPARATION_AUDIO_MB` (default 100MB). Video / flac / ogg → `400 unsupported_audio_format`. |
| `spec` | JSON `SeparationSpec`, e.g. `{ "sourceLanguageCode": "auto" }` (`"auto"` or an ISO code like `"ko"`). |

| Condition                                   | Response                       |
|---------------------------------------------|--------------------------------|
| `file` / `spec` missing                     | `400` (`file is required` / `spec is required`) |
| Extension outside whitelist                 | `400 unsupported_audio_format` |
| Multipart exceeds `MAX_SEPARATION_AUDIO_MB` | `413`                          |
| Balance insufficient                        | `402 insufficient_credits`     |
| Rate limit exceeded (20 / min)              | `429`                          |

**Credits & pricing.** Separation is billed length-proportionally:

```
cost = max(1, ceil( (durationMs − 1000) / 300000 ))   // 1 credit per started 5-minute block
```

i.e. 1 credit per started 5 minutes, with a 1-second boundary grace (so 5min+1s = 1 credit,
absorbing AAC trim padding), minimum 1. The same `CreditCost.forSeparation` powers both
`GET /credits/cost` (estimate) and the actual reservation, so estimate == charge. Charge
duration is measured by ffprobe on the upload; on probe failure it falls back to a
conservative size-based estimate to block undercharge. The reservation is row-locked and
refunded if the job ends `FAILED`. Top-up catalog (kept in sync with `vibi-mobile/shared`):
`vibi.credits.10` → 10, `vibi.credits.50` → 50, `vibi.credits.150` → 150; an unknown
`productId` is `400 unknown_product` even if the receipt verifies. New users get 3
signup-bonus credits (idempotent).

**Stems.** Once `READY`, `GET /api/v2/separate/{jobId}` returns a signed `url` per stem:

| `stemId`                    | Label        | Source                            |
|-----------------------------|--------------|-----------------------------------|
| `background`                | 배경음       | Perso `backgroundAudio` (BGM + effects + reactions, not split apart) |
| `voice_all`                 | 모든 화자    | Perso `originalVoiceAudio`        |
| `speaker_0`, `speaker_1`, … | 화자 1, 2, … | Perso `originalVoiceSpeakers` (ZIP, unzipped locally) |

Per-speaker stems appear only when `numberOfSpeakers >= 2`. On save, mobile builds a
`RenderConfig` whose `separationDirectives[].selections[]` carries each stem's signed URL +
final volume, and `/render` amixes them into the final mp4 in one ffmpeg pass.

**Security & lifecycle.** Stems are never static-mounted; downloads require a short-lived
HMAC token bound to `{jobId, resourceId, expiry}` (TTL `SEPARATION_URL_TTL_SEC`) — re-call
`GET /separate/{jobId}` to mint fresh URLs after expiry. Access logs mask `?token=***`,
`id_token`, `access_token`, and OAuth `code`; client HTTP logging is `NONE` so Perso keys /
SAS URLs never leak. Abandoned `READY` separations are reaped after `SEPARATION_ABANDON_TTL_MS`
(7 days); there is no explicit dispose endpoint. Rotating `SEPARATION_SIGNING_SECRET`
invalidates every outstanding token at once.

### Sign-In

Both providers follow the same pattern: the native SDK (GoogleSignIn iOS SPM / Android
Credential Manager / Apple `AuthenticationServices`) returns an ID Token which the mobile
client forwards to BFF. BFF verifies, upserts a row in `users` (`(provider, provider_sub)`
unique), and returns its own HS256 JWT whose `sub` is the internal UUID (reusable as IAP
`appAccountToken`).

- **Google** — `POST /api/v2/auth/google` with `{ idToken }`. Verified via
  `https://oauth2.googleapis.com/tokeninfo`; `aud` must match one of `GOOGLE_OAUTH_CLIENT_IDS`
  (iOS / Android / Web — typically all three, since one BFF serves both platforms and the landing).
- **Apple** — `POST /api/v2/auth/apple` with `{ idToken, fullName? }`. Verified via JWKS RS256
  (`https://appleid.apple.com/auth/keys`, 24h-cached); `aud` must match `APPLE_OAUTH_CLIENT_IDS`.
  `fullName` is Apple's first-login-only payload — used for `users.display_name` on insert only.

Enabling Apple in production needs a paid Apple Developer Program membership (Personal Team
builds hit runtime error 1000), `APPLE_OAUTH_CLIENT_IDS` set to the iOS bundle id, and the
`com.apple.developer.applesignin` entitlement (`iosApp/iosApp.entitlements` via `project.yml`;
run `xcodegen generate` after pulling).

## Configuration

Env vars load as **real env > system property > `.env`**. Required values fail fast at boot;
optional values fall back to the listed default.

### Perso AI

| Key                            | Default                       | Description                                              |
|--------------------------------|-------------------------------|----------------------------------------------------------|
| `PERSO_API_KEY`                | —                             | **Required.** Perso AI API key (separation).             |
| `PERSO_SPACE_SEQ`              | —                             | **Required.** Perso workspace id (`GET /portal/api/v1/spaces`, > 0). |
| `PERSO_BASE_URL`               | `https://api.perso.ai`        | Perso API host.                                          |
| `PERSO_STORAGE_BASE_URL`       | `https://portal-media.perso.ai` | Perso storage CDN host. `/perso-storage/...` paths go here without auth headers. |
| `PERSO_DOWNLOAD_ALLOWED_HOSTS` | `portal-media.perso.ai`       | Comma-separated SSRF allow-list for Perso-issued download URL hosts (api/storage auto-added). |
| `PERSO_POLL_INTERVAL_MS`       | `15000`                       | Perso job polling interval (≥ 1000).                     |
| `PERSO_MAX_POLL_MINUTES`       | `30`                          | Separation job polling timeout (1..120).                 |

### Server / storage

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `PORT`                       | `8080`                   | Server port.                                         |
| `STORAGE_PATH`               | `./storage`              | Local file storage root (`uploads/`, `render/`, `separation/`, `render-input-cache/`). |
| `BFF_BASE_URL`               | `http://localhost:8080`  | Public base URL used in signed download links (blank in prod auto-resolves to the Cloud Run self-reference). |
| `CORS_ALLOWED_ORIGINS`       | *(blank = same-origin only)* | Comma-separated allow-list. **Blank** = CORS plugin not installed (same-origin only — the safe default for a native app). Use a sentinel like `android-only.invalid` to install CORS while locking out real browser origins. |
| `R2_BUCKET`                  | *(blank = disabled)*     | When set, large render / separation downloads upload to this R2 bucket and respond with a `302` SigV4 presigned URL (R2 egress free). Blank → `respondFile` streaming. |
| `R2_ACCOUNT_ID`              | —                        | Cloudflare account ID (32-char hex). Required when `R2_BUCKET` set. |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | — | R2 API token (Object Read & Write). Required when `R2_BUCKET` set. |
| `SIGNED_URL_TTL_SEC`         | `900`                    | R2 presigned URL TTL seconds (60..86400).            |
| `RENDER_MAX_CONCURRENT`      | *(auto: CPU/2)*          | ffmpeg fan-out cap. Override when hosts misreport `availableProcessors`. |
| `RENDER_INPUT_CACHE_TTL_HOURS` | `24`                   | TTL of the shared `/render/inputs` slot (> 0).       |
| `RENDER_PROCESS_TIMEOUT_MIN` | `30`                     | Hard timeout (min) for the final ffmpeg mix pass; guards against a leaked concurrency permit. |
| `UPLOADS_TTL_HOURS`          | `6`                      | Retention TTL (h) for leftover uploaded source media (PII cleanup; normal path deletes immediately). |
| `MAX_UPLOAD_FILE_SIZE_MB`    | `500`                    | Multipart upload cap (MB) for render endpoints.      |
| `HTTP_CONNECT_TIMEOUT_MS` / `HTTP_REQUEST_TIMEOUT_MS` / `HTTP_SOCKET_TIMEOUT_MS` | `120000` / `600000` / `600000` | Upstream HttpClient timeouts. |

### Separation lifecycle

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `SEPARATION_SIGNING_SECRET`  | —                        | **Required.** HMAC key (≥32 chars) for stem URL signing. `openssl rand -hex 32`. |
| `MAX_SEPARATION_AUDIO_MB`    | `100`                    | Audio-only upload cap (MB) for `/separate`; exceeding → `413`. |
| `SEPARATION_ABANDON_TTL_MS`  | `604800000` (7 days)     | Reap READY-but-unclaimed separations after this many ms (≥ 60000). |
| `SEPARATION_URL_TTL_SEC`     | `604800` (7 days)        | Stem URL token lifetime (60..604800). Must be ≤ abandon TTL. |
| `SEPARATION_MAX_PERSO_IN_FLIGHT` | `2`                  | BFF-side cap on concurrent Perso jobs (1..5), enforced cross-instance via the Postgres queue. |

### Auth & Postgres

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `GOOGLE_OAUTH_CLIENT_IDS`    | —                        | **Required.** Comma-separated Google OAuth client IDs (iOS / Android / Web). `aud` must match. |
| `APPLE_OAUTH_CLIENT_IDS`     | *(blank = disabled)*     | Comma-separated Apple client IDs (typically `com.vibi.ios`). Blank → `/auth/apple` returns 503. |
| `AUTH_JWT_SECRET`            | —                        | **Required.** HMAC-SHA256 key (≥32 chars) for access token signing. |
| `AUTH_JWT_EXPIRY_SECONDS`    | `604800` (7 days)        | Issued token lifetime (60..2592000, i.e. max 30 days — no refresh tokens). |
| `DATABASE_URL`               | —                        | **Required.** JDBC URL. Postgres **must** set `sslmode=require` (or `verify-ca`/`verify-full`) — missing or `disable` is rejected at boot. H2 accepted for local tests. |
| `DB_USER` / `DB_PASSWORD`    | —                        | **Required.** DB role + password.                    |
| `DB_MAX_POOL`                | `5`                      | HikariCP pool size (1..50). Neon free tier 100 conn cap. |

### Credits / in-app purchase

Optional, **per-platform all-or-nothing**: if a platform's credentials are not fully set,
`/credits/purchase` for it returns `400 iap_unconfigured` (no stub passthrough, even in dev).

| Key                              | Default        | Description                                          |
|----------------------------------|----------------|------------------------------------------------------|
| `IAP_APPLE_ISSUER_ID`            | —              | App Store Connect Issuer ID (UUID) for the App Store Server API ES256 JWT. |
| `IAP_APPLE_KEY_ID`               | —              | In-App Purchase key Key ID (10-char).                |
| `IAP_APPLE_PRIVATE_KEY`          | —              | `.p8` PEM body (must contain `BEGIN PRIVATE KEY`; literal `\n` is restored to newlines). |
| `IAP_APPLE_BUNDLE_ID`            | —              | iOS bundle id; the verified transaction `bundleId` must match. |
| `IAP_APPLE_ENV`                  | `production`   | StoreKit environment: `production` or `sandbox`.     |
| `IAP_GOOGLE_PACKAGE_NAME`        | —              | Android package name for the Play Android Publisher API. |
| `IAP_GOOGLE_SERVICE_ACCOUNT_JSON`| —              | Play service-account JSON (must contain `client_email`). In prod, inject via Secret Manager. |

> All four Apple vars enable Apple; both Google vars enable Google. Android billing is still
> mocked, so Google is optional for now.

### Admin

| Key                     | Default                          | Description                                          |
|-------------------------|----------------------------------|------------------------------------------------------|
| `ADMIN_SLUG`            | *(blank = admin UI not mounted)* | Unguessable prefix for the admin SPA mount (`/<ADMIN_SLUG>/`, alphanumeric/dash/underscore, length 6..64). The admin **API** is served regardless. |
| `ADMIN_GRANT_DAILY_CAP` | `1000`                           | Per-admin rolling 24h cap on `/credits/admin-grant`; exceeding → `429`. |

> The admin SPA is built separately (`admin-ui/`, Vite); its build-time
> `VITE_GOOGLE_OAUTH_CLIENT_ID_ADMIN` is inlined into the bundle and is **not** read by the
> Kotlin server at runtime.

### Rate limiting & observability

| Key                              | Default                | Description                                          |
|----------------------------------|------------------------|------------------------------------------------------|
| `RATE_LIMIT_TRUSTED_PROXY_HOPS`  | `0`                    | Trusted proxy hops to skip from the right of `X-Forwarded-For` (see [Rate limiting](#rate-limiting)). Set `1` behind Cloud Run. |
| `SENTRY_DSN_BFF`                 | *(blank = Sentry off)* | Sentry DSN; blank makes init a no-op.                |
| `SENTRY_ENV`                     | `dev`                  | Sentry environment tag (e.g. `prod`).                |
| `SENTRY_TRACES_SAMPLE_RATE`      | `0.1`                  | Sentry traces sample rate.                           |
| `SENTRY_RELEASE`                 | —                      | Release id (CI injects the git SHA).                 |

### Dev toggles (leave unset in production)

| Key                     | Default     | Description                                          |
|-------------------------|-------------|------------------------------------------------------|
| `ENABLE_SWAGGER`        | *(unset)*   | `=true` mounts the **unauthenticated** `/swagger` UI. |
| `ENABLE_TESTDATA_MOCK`  | *(unset)*   | `=true` mounts the `/api/v2/testdata/separation/*` dev mock routes. |

## Build & test

```bash
./gradlew compileKotlin                                    # compile
./gradlew test                                             # all tests
./gradlew test --tests "com.vibi.bff.SeparationRoutesTest" # single class
./gradlew run                                              # server on :8080
```

Windows users can use `.\gradlew ...`; build output goes to `C:/tmp/vibi-bff-build`
(configured in `build.gradle.kts`). Tests run with Ktor `testApplication` + MockK and mirror
their route/service files. `RenderService` has no unit tests — it spawns real ffmpeg processes
and is exercised end-to-end against the mobile client.

## Architecture

Kotlin 2.0 · Ktor 3 · Netty · Exposed + HikariCP · kotlinx.serialization, on JDK 21.

```
src/main/kotlin/com/vibi/bff/
├── Application.kt   # entry point: config load, DI wiring, HttpClient, Sentry, shutdown hooks
├── config/          # HOCON + env loading with boot-time fail-fast (AppConfig)
├── db/              # HikariCP + Exposed tables + Flyway migrations (users, credits, jobs, calls)
├── plugins/         # CORS, serialization, JWT auth, rate limiting, routing, error handling
├── model/           # request/response DTOs (Auth, Credit, Admin, Bff, Perso)
├── routes/          # /api/v2 handlers (auth, credits, assets, render, separate, admin)
└── service/         # Perso client, render/separation orchestration, credit ledger,
                     #   IAP verifiers (iap/), signed URLs, optional R2 object store
```

See [`CLAUDE.md`](./CLAUDE.md) for the per-file map, subsystem deep-dives, and known bug patterns.

## Troubleshooting

- **`PERSO_API_KEY must be set` / `SEPARATION_SIGNING_SECRET must be at least 32 chars`** — required env missing; the `.env.example` secrets are placeholders. Generate with `openssl rand -hex 32`.
- **Boot fails on `DATABASE_URL`** — Postgres URLs must set `sslmode=require` (or `verify-ca`/`verify-full`).
- **Boot fails on `AUTH_JWT_EXPIRY_SECONDS`** — the max is `2592000` seconds (30 days).
- **`/credits/purchase` returns `iap_unconfigured`** — the platform's IAP credentials are not fully set (all four `IAP_APPLE_*`, or both `IAP_GOOGLE_*`).
- **`429` from `/auth/*`, `/render`, or `/separate`** — a rate limit was hit. Behind Cloud Run set `RATE_LIMIT_TRUSTED_PROXY_HOPS=1` so the key is the real client IP.
- **Render jobs stuck in `FAILED` with "ffmpeg exited with code …"** — check the logs (last 500 bytes of ffmpeg stderr are emitted). Usually missing `ffmpeg`/`ffprobe` on `PATH` or a segment referencing an unknown `sourceFileKey`.
- **Separation status returns 403 after a while** — stem tokens expired (`SEPARATION_URL_TTL_SEC`). Re-call `GET /api/v2/separate/{jobId}` for fresh URLs.
- **Perso returns 402** — workspace quota exhausted; maps to `402` on the BFF response.
