# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DubCast BFF — Kotlin/Ktor backend that proxies ElevenLabs API for TTS, voice listing, and lip-sync dubbing, and runs local ffmpeg-based video rendering (dub audio mixing, sticker overlays, multi-segment concat, subtitle burn-in). Serves as a BFF (Backend For Frontend) for the DubCast Android app.

**Runtime dependencies:** `ffmpeg` and `ffprobe` must be available on `PATH` for the render pipeline to work.

## Build & Run

```bash
# Build
.\gradlew compileKotlin

# Run tests
.\gradlew test

# Run single test class
.\gradlew test --tests "com.dubcast.bff.TtsRoutesTest"

# Run application (port 8080)
.\gradlew run
```

Build output goes to `C:/tmp/dubcast-bff-build` (configured in build.gradle.kts).

## Environment Variables

Configured via `.env` file (loaded by dotenv-kotlin) or system env. See `.env.example`:
- `ELEVENLABS_API_KEY` (required) — ElevenLabs API key
- `PORT` — server port (default: 8080)
- `STORAGE_PATH` — local file storage path (default: `./storage`)
- `BFF_BASE_URL` — public base URL for download links (default: `http://localhost:8080`)
- `CORS_ALLOWED_ORIGINS` — comma-separated allowed origins (blank = any host)

## Architecture

**Stack**: Kotlin 2.0, Ktor 3.0.3, Netty, kotlinx.serialization, JDK 21

**Request flow**: Android app → Ktor routes → ElevenLabsClient (HTTP proxy) → ElevenLabs API. Audio/video files are stored locally via FileStorageService and served as static files.

### Key layers

- `config/AppConfig.kt` — HOCON config loading from `application.conf` with env var overrides
- `plugins/` — Ktor plugin setup: CORS, serialization (`Json { ignoreUnknownKeys=true, encodeDefaults=true }`), error handling (StatusPages), routing
- `routes/` — Route definitions. v1 routes use JSON-based requests; v2 routes add multipart lipsync, `durationMs` in TTS, `language` in voices, dedicated download endpoint
- `model/BffModels.kt` — BFF request/response DTOs (kotlinx.serialization)
- `model/ElevenLabsModels.kt` — ElevenLabs API response DTOs (snake_case → camelCase via `@SerialName`)
- `service/ElevenLabsClient.kt` — HTTP client wrapping all ElevenLabs API calls (voices, TTS, dubbing/lip-sync)
- `service/FileStorageService.kt` — Local file storage in `uploads/`, `tts/`, `lipsync/`, `render/` subdirectories
- `service/AudioUtils.kt` — MP3 frame header parsing for duration estimation
- `service/RenderService.kt` — Background ffmpeg render jobs (multipart inputs → mp4 output). Handles multi-segment concat, sticker image overlays, dub audio mixing, and ASS subtitle burn-in. Jobs tracked in-memory with TTL cleanup; outputs streamed via download endpoint (no static mount).

### API versions

- `/api/v1` — original endpoints (upload, voices, tts, lipsync). Lipsync uses JSON with blob paths from prior `/upload`.
- `/api/v2` — spec-aligned endpoints:
  - `GET /voices` — includes `language` field (extracted from ElevenLabs labels)
  - `POST /tts` — returns `durationMs` (MP3 frame parsing via AudioUtils)
  - `POST /lipsync` — multipart (video, audio, targetLang, startMs, durationMs)
  - `GET /lipsync/{jobId}/status` — status polling with dynamic target language
  - `GET /lipsync/{jobId}/download` — binary mp4 streaming
  - `POST /render` — multipart render job. Fields: `video` (legacy) or `video_0`/`video_1`/… , `audio_N` (dub tracks), `image_N` (sticker overlays), `segment_image_N` (IMAGE-type segment sources), `subtitles` (ASS), `config` (JSON `RenderConfig`). Returns `jobId`.
  - `GET /render/{jobId}/status` — status polling (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED` + `progress`)
  - `GET /render/{jobId}/download` — binary mp4 streaming (no static mount)
- Swagger UI exposed at `/swagger` (spec: `resources/openapi/dubcast-bff.yaml`)

### Render pipeline (v2)

`RenderConfig` supports two paths chosen by presence of `segments`:

- **Legacy** (`segments == null`): single video input + `videoDurationMs`. Runs one ffmpeg command (audio mix + subtitles + stickers).
- **Multi-segment** (`segments != null`): each segment is `VIDEO` (trim+normalize to output resolution) or `IMAGE` (loop → letterboxed still with silent AAC). Normalized clips are concatenated via concat demuxer, then the final ffmpeg pass applies stickers + dub audio mix + subtitle burn-in.

Shared features across both paths:
- `imageClips[]` — sticker overlays with percentage-based position/size (`xPct/yPct/widthPct/heightPct`) and `between(t,start,end)` gating
- `dubClips[]` — per-clip `adelay`+`volume` then `amix` with original video audio
- Optional ASS subtitles via the `ass` filter

Output resolution is taken from `segments[0].width/height` (multi-segment) or ffprobe on the source video (legacy). Windows drive-letter colons are preserved when paths are embedded in filter expressions.

### ElevenLabs API usage

- Voices: `GET /v2/voices` (ElevenLabs v2, paginated, `page_size=100`)
- TTS: `POST /v1/text-to-speech/{voice_id}`
- Dubbing: `POST /v1/dubbing` (`target_lang` required, `mode=automatic`, `start_time`/`end_time` in seconds)
- Dubbing status: `GET /v1/dubbing/{dubbing_id}` (status: `dubbing`→`dubbed`→`failed`)
- Dubbed audio: `GET /v1/dubbing/{dubbing_id}/audio/{language_code}`

### Error handling

Custom exceptions in `ErrorHandling.kt`: `NotFoundException` (404), `ElevenLabsApiException` (maps upstream status codes), `IllegalArgumentException` (400). All return `ErrorResponse(error, detail?)`.

### Static file serving

- `/files/tts/*` — TTS audio files (mp3)
- `/files/lipsync/*` — Lip-sync result videos (mp4)
- Render outputs are **not** statically mounted; fetch via `GET /api/v2/render/{jobId}/download`.

## Testing

Tests use Ktor `testApplication` with MockK for mocking `ElevenLabsClient` and `FileStorageService`. Test files mirror route files (e.g., `TtsRoutesTest.kt` tests `TtsRoutes.kt`).
