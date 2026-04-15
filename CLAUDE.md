# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DubCast BFF — Kotlin/Ktor backend that proxies ElevenLabs API for TTS, voice listing, and lip-sync dubbing. Serves as a BFF (Backend For Frontend) for the DubCast Android app.

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
- `service/FileStorageService.kt` — Local file storage in `uploads/`, `tts/`, `lipsync/` subdirectories
- `service/AudioUtils.kt` — MP3 frame header parsing for duration estimation

### API versions

- `/api/v1` — original endpoints (upload, voices, tts, lipsync). Lipsync uses JSON with blob paths from prior `/upload`.
- `/api/v2` — spec-aligned endpoints:
  - `GET /voices` — includes `language` field (extracted from ElevenLabs labels)
  - `POST /tts` — returns `durationMs` (MP3 frame parsing via AudioUtils)
  - `POST /lipsync` — multipart (video, audio, targetLang, startMs, durationMs)
  - `GET /lipsync/{jobId}/status` — status polling with dynamic target language
  - `GET /lipsync/{jobId}/download` — binary mp4 streaming

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

## Testing

Tests use Ktor `testApplication` with MockK for mocking `ElevenLabsClient` and `FileStorageService`. Test files mirror route files (e.g., `TtsRoutesTest.kt` tests `TtsRoutes.kt`).
