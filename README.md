# DubCast BFF

Kotlin/Ktor Backend-For-Frontend for the DubCast Android app. Proxies the
ElevenLabs API (voices, TTS, dubbing/lip-sync) and runs a local ffmpeg-based
video render pipeline (dub audio mixing, sticker overlays, multi-segment
concat, subtitle burn-in).

> Detailed architecture notes live in [`CLAUDE.md`](./CLAUDE.md). The OpenAPI
> spec is at `src/main/resources/openapi/dubcast-bff.yaml` and is served at
> `/swagger` when the app is running.

## Prerequisites

- **JDK 21**
- **ffmpeg** and **ffprobe** on `PATH` (required by the render pipeline)
- An **ElevenLabs API key**

On Windows, verify:

```bash
ffmpeg -version
ffprobe -version
```

## Setup

```bash
cp .env.example .env
# then edit .env and set ELEVENLABS_API_KEY
```

Environment variables (also loadable from system env):

| Key                    | Default                  | Description                                          |
|------------------------|--------------------------|------------------------------------------------------|
| `ELEVENLABS_API_KEY`   | —                        | **Required.** ElevenLabs API key                     |
| `PORT`                 | `8080`                   | Server port                                          |
| `STORAGE_PATH`         | `./storage`              | Local file storage root (`uploads/`, `tts/`, `lipsync/`, `render/`) |
| `BFF_BASE_URL`         | `http://localhost:8080`  | Public base URL used in download links               |
| `CORS_ALLOWED_ORIGINS` | *(blank = any)*          | Comma-separated allow-list                           |

## Build & Run

```bash
# Compile
./gradlew compileKotlin

# Run tests (all)
./gradlew test

# Run a single test class
./gradlew test --tests "com.dubcast.bff.TtsRoutesTest"

# Run the server (port 8080 by default)
./gradlew run
```

Windows users can use `.\gradlew ...` equivalently. Build output is directed
to `C:/tmp/dubcast-bff-build` (configured in `build.gradle.kts`).

Once running:
- Swagger UI: <http://localhost:8080/swagger>
- Static files: `/files/tts/*`, `/files/lipsync/*`

## API Overview

Two versioned surfaces are mounted under `/api/v1` and `/api/v2`.
v2 is the spec-aligned surface the Android client targets; v1 is retained
for backwards compatibility.

### v1 (legacy)

| Method | Path                     | Notes                                       |
|--------|--------------------------|---------------------------------------------|
| POST   | `/api/v1/upload`         | Generic blob upload; returns `blobPath`     |
| GET    | `/api/v1/voices`         | ElevenLabs voice list                       |
| POST   | `/api/v1/tts`            | Text-to-speech; JSON body                   |
| POST   | `/api/v1/lipsync`        | Lip-sync; JSON with `blobPath`s from upload |

### v2

| Method | Path                                         | Notes                                                         |
|--------|----------------------------------------------|---------------------------------------------------------------|
| GET    | `/api/v2/voices`                             | Includes `language` (extracted from ElevenLabs labels)        |
| POST   | `/api/v2/tts`                                | Response includes `durationMs` (parsed from MP3 frames)       |
| POST   | `/api/v2/lipsync`                            | Multipart: `video`, `audio`, `targetLang`, `startMs`, `durationMs` |
| GET    | `/api/v2/lipsync/{jobId}/status`             | Polling                                                       |
| GET    | `/api/v2/lipsync/{jobId}/download`           | Binary mp4                                                    |
| POST   | `/api/v2/render`                             | Multipart render job (see [Render](#render-endpoint))         |
| GET    | `/api/v2/render/{jobId}/status`              | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` + `progress`|
| GET    | `/api/v2/render/{jobId}/download`            | Binary mp4 (no static mount)                                  |

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
| `image_0`, `image_1`, … | Sticker overlay images (referenced by `imageClips[]`)  |
| `audio_0`, `audio_1`, … | Dub audio tracks (referenced by `dubClips[]`)          |
| `subtitles`           | *(optional)* ASS subtitle file, burned in at the end     |
| `config`              | JSON-encoded `RenderConfig` (form item, not a file)      |

#### RenderConfig

Two modes share the same DTO; pick one:

```jsonc
// Legacy single-video mode
{
  "videoDurationMs": 60000,
  "dubClips": [
    { "audioFileKey": "audio_0", "startMs": 1000, "durationMs": 4000, "volume": 1.0 }
  ],
  "imageClips": [
    { "imageFileKey": "image_0", "startMs": 2000, "endMs": 5000,
      "xPct": 50, "yPct": 30, "widthPct": 25, "heightPct": 25 }
  ]
}
```

```jsonc
// Multi-segment mode (replaces videoDurationMs)
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
  "imageClips": [ /* stickers — global timeline, same as above */ ],
  "dubClips":   [ /* dub tracks — global timeline */ ]
}
```

Output resolution comes from `segments[0].width`/`height` (multi-segment) or
ffprobe on the source (legacy). Sticker positions/sizes are percentages of
that output resolution. `imageClips` are gated via ffmpeg `enable='between(t,…)'`.

#### Pipeline (multi-segment)

1. **Per-segment normalization** — VIDEO: input-side seek + scale/pad to output resolution + silent AAC track. IMAGE: `-loop 1` + letterbox + silent AAC track.
2. **Concat demuxer** (`-c copy`) — all normalized clips share codec, fps, resolution, and audio layout, so no re-encode here.
3. **Final pass** — subtitle burn-in → chained sticker `overlay` filters → `adelay`+`volume` dub clips mixed with original audio via `amix`.

## Project Layout

```
src/main/kotlin/com/dubcast/bff/
├── Application.kt              # Ktor entry point + DI wiring
├── config/AppConfig.kt         # HOCON + env var loading
├── plugins/
│   ├── Cors.kt, Serialization.kt, Routing.kt
│   └── ErrorHandling.kt        # StatusPages → ErrorResponse
├── model/
│   ├── BffModels.kt            # BFF request/response DTOs
│   └── ElevenLabsModels.kt     # Upstream API DTOs
├── routes/                     # v1 + v2 route definitions
└── service/
    ├── ElevenLabsClient.kt     # Upstream API wrapper
    ├── FileStorageService.kt   # Local blob storage
    ├── AudioUtils.kt           # MP3 frame parsing
    └── RenderService.kt        # ffmpeg orchestration
```

## Testing

Tests run with Ktor `testApplication` + MockK. Test files mirror route files
(e.g. `TtsRoutesTest.kt` covers `TtsRoutes.kt`). The render service is not
currently covered by tests — it spawns real ffmpeg processes and is exercised
end-to-end against the Android client.

## Troubleshooting

- **`ELEVENLABS_API_KEY must be set`** — populate `.env` or the env var.
- **Render jobs stuck in `FAILED` with "ffmpeg exited with code …"** — check the server logs; the last 500 bytes of ffmpeg stderr are emitted on failure. The most common causes are missing `ffmpeg`/`ffprobe` on `PATH` or a segment referencing an unknown `sourceFileKey`.
- **Subtitle burn-in silently fails on Windows** — `escapeFilterPath` preserves the drive-letter colon; if you see `No such file or directory` for a subtitle path in ffmpeg logs, verify the uploaded file exists under `STORAGE_PATH/uploads/`.
