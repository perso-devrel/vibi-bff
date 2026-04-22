# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DubCast BFF — Kotlin/Ktor backend for the DubCast Android app. Proxies ElevenLabs (TTS/voices/lip-sync dubbing) and runs a local ffmpeg render pipeline (dub audio mix, sticker overlays, multi-segment concat, subtitle burn-in).

**Runtime deps:** `ffmpeg` and `ffprobe` on `PATH`.
**Stack:** Kotlin 2.0, Ktor 3.0.3, Netty, kotlinx.serialization, JDK 21.

## Build & Run

```bash
.\gradlew compileKotlin                                    # build
.\gradlew test                                             # all tests
.\gradlew test --tests "com.dubcast.bff.TtsRoutesTest"     # single class
.\gradlew run                                              # server on :8080
```

Build output: `C:/tmp/dubcast-bff-build`. Full env var table + API reference in `README.md`. Swagger UI at `/swagger`.

## Structure

- `Application.kt` — entry point, DI wiring, `ELEVENLABS_API_KEY` + `PERSO_API_KEY` validation
- `config/AppConfig.kt` — HOCON + env var loading
- `plugins/` — CORS, serialization (`Json { ignoreUnknownKeys=true, encodeDefaults=true }`), error handling (StatusPages), routing
- `routes/` — `/api/v1` (legacy JSON) and `/api/v2` (multipart, spec-aligned Android target)
- `model/BffModels.kt` — BFF DTOs; `model/ElevenLabsModels.kt`, `model/PersoModels.kt` — upstream DTOs (`@SerialName` snake_case → camelCase)
- `service/` — `ElevenLabsClient`, `PersoClient`, `FileStorageService`, `AudioUtils`, `RenderService`, `SeparationService`, `StemMixService`, `SignedUrlService`

## Error handling

`plugins/ErrorHandling.kt`: `NotFoundException` → 404, `IllegalArgumentException` → 400, `ElevenLabsApiException` → upstream code. All return `ErrorResponse(error, detail?)`.

## File serving

Static mounts: `/files/tts/*`, `/files/lipsync/*`. Render outputs are **not** static-mounted — fetch via `GET /api/v2/render/{jobId}/download`. Separation stems and stem-mix outputs require HMAC-signed tokens and are served via endpoints (never static-mounted).

## Testing

Ktor `testApplication` + MockK. Test files mirror route files (e.g. `TtsRoutesTest.kt` ↔ `TtsRoutes.kt`). `RenderService` is not unit-tested (spawns real ffmpeg).

## Task-specific skills

Load the relevant skill when touching that subsystem — these contain the detail that would otherwise clutter this file:

- `render-pipeline` — ffmpeg pipeline, `RenderConfig`, segments, sticker overlays, Windows path escaping
- `elevenlabs-integration` — upstream endpoints, dubbing status flow, v1/v2 differences
- `separation-pipeline` — Perso upload 3-step, translate/poll/download, stem plan mapping, HMAC URL signing, mix-triggered dispose
- `review` — code review checklist (triggered by "리뷰")
