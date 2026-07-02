# vibi BFF

The Kotlin/Ktor backend behind vibi. One surface and one Postgres serve two clients — the
vibi **mobile app** (Android + iOS, KMP/CMP) and the *Vibi: AI Sound Eraser* **Premiere
plugin** (UXP panel). It splits a clip's audio into speaker + background stems through
**Perso AI**, handles Google / Apple sign-in, meters usage as credits, and re-renders the
chosen mix with a local **ffmpeg** pipeline.

## Core flow

The whole product is five calls; everything else supports them.

1. `POST /api/v2/auth/google` — trade a native ID token for a BFF JWT.
2. `POST /api/v2/separate` — upload trimmed audio, reserve credits, get a `jobId`.
3. `GET  /api/v2/separate/{id}` — poll until `READY`; the response carries signed stem URLs.
4. `GET  /api/v2/separate/{id}/stem/{stemId}` — download a stem (mobile uses a `?token`, the
   Adobe panel a Bearer JWT + WAV transcode).
5. `POST /api/v2/render` — mix the chosen stems back into the final mp4.

## Quickstart

```bash
# Prereqs: JDK 21 + ffmpeg/ffprobe on PATH (the render pipeline shells out to them).
ffmpeg -version && ffprobe -version

cp .env.example .env          # fill the 8 required vars (see Configuration)
./gradlew run                 # server on :8080
```

Base URL is `http://localhost:8080` locally, the Cloud Run URL in prod. Every `/api/v2` call
except `/auth/*` and signed downloads needs `Authorization: Bearer <jwt>`. Errors are always
`{ "error": "<code>", "detail": "<optional>" }` with the matching status; `GET /healthz` is the
liveness probe. Running the Adobe panel against a local BFF? Point it here with
`VIBI_BFF_BASE_URL=http://localhost:8080` (its dev build defaults to `:8787`).

## What it does

**Separation.** `POST /separate` takes an already-trimmed audio file (m4a / mp3 / wav) and runs
it through Perso's pipeline, exposing background / all-voice / per-speaker stems (per-speaker
only when there are two or more speakers). The client trims and extracts audio locally, so the
BFF forwards the bytes as-is. Separation is billed **1 credit per started minute** — measured
server-side by ffprobe, reserved up front, refunded if the job fails; new users get a 3-credit
signup bonus. Saved separations and the diarized script stay listable per project, so both
clients can rebuild history across devices.

**Render.** `POST /render` is a multipart ffmpeg job: multi-segment concat, BGM `atrim`+`amix`
sub-range mixing, and stem `amix normalize=0` to preserve original loudness. `POST /peaks`
returns input-waveform peaks for clients that can't decode AAC locally.

**Sign-in & credits.** Google and Apple ID tokens are verified server-side, upserted into
Postgres (`(provider, provider_sub)` unique), and exchanged for an HS256 JWT whose `sub` is the
internal UUID. The Adobe panel, which has no native sign-in SDK, uses an RFC 8628 device-code
flow instead. Credit top-ups verify StoreKit2 / Play Billing receipts — purchasing is
mobile-only; the panel spends the shared balance.

**Operations.** Large render / stem downloads optionally redirect to a Cloudflare R2 presigned
URL (R2 egress is free); leave `R2_BUCKET` blank for local `respondFile` streaming. An
`admin`-role dashboard API (`/api/v2/admin/*`) serves read-only analytics. Launch hardening:
per-route rate limiting, sanitized errors, SSRF allow-listing, log masking, TLS-enforced DB
URLs, and optional Sentry.

> Sticker overlays, subtitle burn-in, auto-subtitle/STT, auto-dub, lipsync, and Gemini SRT
> translation were **removed** in commit `52f8d7c` — reintroduce as a fresh design, not a revert.

## API reference

The full, always-current endpoint contract is the **OpenAPI spec**
(`src/main/resources/openapi/vibi-bff.yaml`), served as Swagger UI at `/swagger` when
`ENABLE_SWAGGER=true`. Both clients target `/api/v2` exclusively (v1 is retired). Render outputs
and separation stems are never static-mounted — each download streams via `respondFile` or
`302`-redirects to an R2 presigned URL. `POST` on `/auth/*`, `/render`, and `/separate` is
rate-limited (keyed by client IP or JWT subject); polling GETs and downloads are not.

Cheat-sheet — all paths under `/api/v2`; params + errors live in `/swagger`:

| Group    | Endpoints |
|----------|-----------|
| Auth     | `POST auth/google` · `auth/apple` · `auth/device/{start,poll}` · `GET auth/google/{start,callback}` · `DELETE auth/account` |
| Separate | `POST separate` · `GET separate/{id}` · `separate/{id}/stem/{stemId}` · `separate/{id}/script` · `separations` · `DELETE separate/{id}` |
| Render   | `POST render` · `render/inputs` · `render/v3` · `GET render/{id}/{status,download}` |
| Credits  | `GET credits` · `credits/cost` · `POST credits/{purchase,admin-grant}` |
| Assets   | `POST assets/upload-url` · `peaks` |
| Admin    | `GET admin/*` (read-only dashboard, `admin` role) |

## Configuration

Env vars load as **real env > system property > `.env`**. `.env.example` is the canonical list
of all vars with defaults and inline notes; these eight fail fast at boot if unset:

```
PERSO_API_KEY  PERSO_SPACE_SEQ  GOOGLE_OAUTH_CLIENT_IDS  AUTH_JWT_SECRET
SEPARATION_SIGNING_SECRET  DATABASE_URL  DB_USER  DB_PASSWORD
```

Generate the HMAC secrets with `openssl rand -hex 32`. Postgres URLs must set `sslmode=require`.
IAP, R2, admin SPA, and Sentry are all optional and off by default — see `.env.example`.

## Build & test

```bash
./gradlew compileKotlin                                    # compile
./gradlew test                                             # all tests
./gradlew test --tests "com.vibi.bff.SeparationRoutesTest" # single class
./gradlew run                                              # server on :8080
```

Tests run with Ktor `testApplication` + MockK and mirror their route/service files.
`RenderService` has no unit tests — it spawns real ffmpeg and is exercised end-to-end against
the mobile client. Windows build output goes to `C:/tmp/vibi-bff-build`.

## Architecture

Kotlin 2.0 · Ktor 3 · Netty · Exposed + HikariCP · kotlinx.serialization, on JDK 21.

```
src/main/kotlin/com/vibi/bff/
├── Application.kt   # entry point: config load, DI wiring, HttpClient, Sentry, shutdown hooks
├── config/          # HOCON + env loading with boot-time fail-fast
├── db/              # HikariCP + Exposed tables + Flyway migrations
├── plugins/         # CORS, serialization, JWT auth, rate limiting, routing, error handling
├── model/           # request/response DTOs (Auth, Credit, Admin, Bff, Perso)
├── routes/          # /api/v2 handlers (auth, credits, assets, render, separate, admin)
└── service/         # Perso client, render/separation orchestration, credits, IAP, R2
```

See [`CLAUDE.md`](./CLAUDE.md) for the per-file map, subsystem deep-dives (the `render-pipeline`
and `separation-pipeline` skills), and known bug patterns.
