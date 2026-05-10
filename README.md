# vibi BFF

Kotlin/Ktor Backend-For-Frontend for the vibi mobile app (Android + iOS, KMP/CMP).
Proxies Perso AI for audio source separation (background / all-voice / per-speaker
stems, plus user-selected stem remix), STT, and automatic dubbing; proxies
Vertex AI Gemini for subtitle translation and the chat-assistant function-calling
router; and runs a local ffmpeg-based video render pipeline (dub audio mixing,
sticker overlays, multi-segment concat, subtitle burn-in).

> Detailed architecture notes live in [`CLAUDE.md`](./CLAUDE.md). The OpenAPI
> and is served at `/swagger` when the app is running.

## Prerequisites

- **JDK 21**
- **ffmpeg** and **ffprobe** on `PATH` (required by the render pipeline)
- A **Perso AI API key** and workspace `spaceSeq` (required for separation, STT, auto-dubbing)
- *(Optional)* GCP service-account credentials for Vertex AI Gemini if subtitle translation / chat is exercised

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

| Key                          | Default                  | Description                                          |
|------------------------------|--------------------------|------------------------------------------------------|
| `PERSO_API_KEY`              | —                        | **Required.** Perso AI API key (separation / STT / auto-dub) |
| `PERSO_SPACE_SEQ`            | —                        | **Required.** Your Perso workspace id (`GET /portal/api/v1/spaces`) |
| `PERSO_BASE_URL`             | `https://api.perso.ai`   | Perso base URL                                        |
| `PERSO_POLL_INTERVAL_MS`     | `5000`                   | Perso progress polling interval                       |
| `PERSO_MAX_POLL_MINUTES`     | `30`                     | Separation job polling timeout                        |
| `PORT`                       | `8080`                   | Server port                                          |
| `STORAGE_PATH`               | `./storage`              | Local file storage root (`uploads/`, `render/`, `separation/`) |
| `BFF_BASE_URL`               | `http://localhost:8080`  | Public base URL used in download links               |
| `CORS_ALLOWED_ORIGINS`       | *(blank = any)*          | Comma-separated allow-list. Android-only deployments can set a sentinel such as `android-only.invalid` to lock out browsers. |
| `SEPARATION_SIGNING_SECRET`  | —                        | **Required.** HMAC key (≥32 chars) for stem / mix URL signing. Generate with `openssl rand -hex 32`. |
| `SEPARATION_ABANDON_TTL_MS`  | `1800000`                | Reap READY-but-unclaimed separations after this many ms |
| `SEPARATION_MIX_TTL_MS`      | `600000`                 | Keep mix outputs for this many ms after completion    |
| `SEPARATION_URL_TTL_SEC`     | `1800`                   | Stem download URL token lifetime                      |
| `SEPARATION_MIX_URL_TTL_SEC` | `600`                    | Mix download URL token lifetime                       |

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
- Render outputs / separation stems / mix outputs / auto-subtitle SRT / auto-dub audio are **not** static-mounted — see API section below

## API Overview

The mobile client (Android + iOS, KMP/CMP) targets `/api/v2` exclusively.
v1 has been retired.

### v2

| Method | Path                                         | Notes                                                         |
|--------|----------------------------------------------|---------------------------------------------------------------|
| POST   | `/api/v2/render`                             | Multipart render job (see [Render](#render-endpoint))         |
| GET    | `/api/v2/render/{jobId}/status`              | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` + `progress`|
| GET    | `/api/v2/render/{jobId}/download`            | Binary mp4 (no static mount)                                  |
| POST   | `/api/v2/separate`                           | Multipart separation job (see [Separation](#separation-endpoints)) |
| GET    | `/api/v2/separate/{jobId}`                   | Status + signed stem URLs when `READY`                        |
| GET    | `/api/v2/separate/{jobId}/stem/{stemId}`     | Stem audio stream (requires `?token=…`)                       |
| POST   | `/api/v2/separate/{jobId}/mix`               | Mix selected stems; disposes source stems on success          |
| GET    | `/api/v2/separate/mix/{mixJobId}`            | Mix status + signed download URL when `COMPLETED`             |
| GET    | `/api/v2/separate/mix/{mixJobId}/download`   | Mix audio stream (requires `?token=…`)                        |
| POST   | `/api/v2/subtitles`                          | Multipart auto-subtitle job (Perso STT + Gemini translation)  |
| GET    | `/api/v2/subtitles/{jobId}`                  | Subtitle job status; signed `originalSrtUrl` / `translatedSrtUrlsByLang` when ready |
| POST   | `/api/v2/autodub`                            | Multipart auto-dub job (Perso translate + voice synthesis)    |
| GET    | `/api/v2/autodub/{jobId}`                    | Auto-dub job status; signed `dubbedAudioUrl` / `dubbedVideoUrl` when ready |
| GET    | `/api/v2/languages`                          | Perso-supported target language list                          |
| POST   | `/api/v2/chat`                               | Gemini function-calling assistant — returns `{kind:"text"\|"proposal"}` (see [Chat](#chat-endpoint)) |

### Chat endpoint

`POST /api/v2/chat` accepts JSON `{messages, projectContext, locale}`. The
service forwards the conversation + a registered set of `EDIT_TOOLS`
function declarations to Vertex AI Gemini (`generateContent`). Gemini either
returns plain text (read-only / clarification) or one or more `functionCall`
parts that the BFF relays as `proposal.steps` (max 5). The mobile dispatcher
then maps each step name to the matching `TimelineViewModel.onXxx`.

Tool registry (`service/ChatToolDefs.kt`): `delete_segment_range`,
`duplicate_segment_range`, `update_segment_volume`, `update_segment_speed`,
`separate_audio_range`, `update_stem_volume`, `update_subtitle_text`,
`generate_subtitles`, `generate_dub`, `move_bgm_clip`, `update_bgm_volume`,
`generate_subtitles_for_bgm`, `generate_dub_for_bgm`.

System instruction enforces: respond in user's language, never invent IDs,
single tool kind per response (`text` or `proposal`), confirmation gate on
the mobile side. ProjectContext (segments / subtitles / dubs / BGM clips /
stems / playhead / selectionId) is inlined as a system prefix on the first
user turn.

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

### Separation endpoints

`POST /api/v2/separate` uploads a video or audio file, submits it to Perso's
dubbing pipeline, and exposes the byproduct stems for the mobile client to
pick and remix.

#### Multipart fields

| Field  | Description                                                        |
|--------|--------------------------------------------------------------------|
| `file` | Source mp4 or mp3 (up to 500 MB)                                   |
| `spec` | JSON `SeparationSpec` (form item, not a file)                      |

```jsonc
// SeparationSpec
{
  "mediaType": "VIDEO",          // "VIDEO" or "AUDIO"
  "numberOfSpeakers": 2,         // 1..10 — Perso has no auto-detect
  "sourceLanguageCode": "auto",  // "auto" or ISO code like "ko"
  "trimStartMs": 2000,           // optional — see "Optional trim" below
  "trimEndMs": 8500              // optional — must be present iff trimStartMs is
}
```

##### Optional trim (`trimStartMs` / `trimEndMs`)

When both fields are supplied, the BFF stream-copy cuts the uploaded file
with ffmpeg before handing it to Perso, so only the selected window is
processed (and billed). Omitting both is backwards-compatible: Perso
receives the whole file. Validation:

| Condition                              | Response                                                                 |
|----------------------------------------|--------------------------------------------------------------------------|
| only one of the two is present         | `400 partial_trim_range`                                                 |
| `trimStartMs < 0`                      | `400 trim_start_negative`                                                |
| `trimEndMs <= trimStartMs`             | `400 trim_range_invalid`                                                 |
| window `< 500 ms`                      | `400 trim_range_too_short`                                               |
| `trimEndMs` exceeds probed duration    | `400 trim_end_exceeds_duration` with `detail="trimEndMs=… duration=…"` |
| ffprobe / ffmpeg fails                 | `500 ffmpeg_error`                                                       |

> Any non-2xx from the trim stage disposes the uploaded file. Clients must
> re-upload the source with a corrected `spec` rather than retrying against
> the same upload. (`500` instead of `501` because 501 is semantically
> *Not Implemented* — the ffmpeg call is available, it just failed.)

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
3. Preview individual stems via `GET /api/v2/separate/{jobId}/stem/{stemId}?token=…`.
4. `POST /api/v2/separate/{jobId}/mix` with `{ "stems": [{ "stemId": …, "volume": … }, …] }` → `{ "mixJobId": "mix-…" }`.
5. Poll `GET /api/v2/separate/mix/{mixJobId}` until `status=COMPLETED`; response
   carries a signed `downloadUrl`.
6. `GET /api/v2/separate/mix/{mixJobId}/download?token=…` streams the mp3.

**Lifecycle note:** a successful mix disposes the source separation atomically
— the parent `jobId`'s stems are deleted and further `/mix` calls against it
return `409 Conflict`. Download any stems you want to keep **before** calling
`/mix`. Abandoned `READY` separations are reaped after `SEPARATION_ABANDON_TTL_MS`.

#### Security

- Stems and mixes are **never** static-mounted; all downloads require a
  short-lived HMAC token bound to `{jobId, resourceId, expiry}`.
- Tokens appear in response `url` fields. Access logs mask `?token=***` so
  signatures don't leak to log aggregators.
- Rotate `SEPARATION_SIGNING_SECRET` carefully — changing it invalidates every
  outstanding token at once.

## Project Layout

```
src/main/kotlin/com/vibi/bff/
├── Application.kt              # Ktor entry point + DI wiring
├── config/AppConfig.kt         # HOCON + env var loading (Perso, Gemini, Separation, Storage)
├── plugins/
│   ├── Cors.kt, Serialization.kt, Routing.kt
│   └── ErrorHandling.kt        # StatusPages → ErrorResponse
├── model/
│   ├── BffModels.kt            # BFF request/response DTOs
│   ├── ChatModels.kt           # Chat / Gemini DTOs
│   └── PersoModels.kt          # Perso upstream DTOs
├── routes/                     # /api/v2 route definitions (Render, Separation, Subtitles, AutoDub, Chat, Languages)
└── service/
    ├── PersoClient.kt          # Perso upstream wrapper (SAS upload / translate / poll / download)
    ├── GeminiClient.kt         # Vertex AI Gemini wrapper (translation + function calling)
    ├── FileStorageService.kt   # Local blob storage
    ├── AudioUtils.kt           # MP3 frame parsing
    ├── RenderService.kt        # ffmpeg render orchestration
    ├── SeparationService.kt    # Perso-backed stem separation jobs
    ├── StemMixService.kt       # ffmpeg amix-based stem remix
    ├── AutoSubtitleService.kt  # Perso STT + Gemini translation jobs
    ├── AutoDubService.kt       # Perso translate-with-TTS jobs
    └── SignedUrlService.kt     # HMAC-SHA256 download URL signer
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
- **Subtitle burn-in silently fails on Windows** — `escapeFilterPath` preserves the drive-letter colon; if you see `No such file or directory` for a subtitle path in ffmpeg logs, verify the uploaded file exists under `STORAGE_PATH/uploads/`.
- **Separation status returns 403 after a while** — stem tokens expire after `SEPARATION_URL_TTL_SEC`. Re-call `GET /api/v2/separate/{jobId}` to mint fresh signed URLs.
- **`POST /api/v2/separate/{jobId}/mix` returns 409** — the separation is no longer `READY` (either already consumed by a prior mix, still processing, or reaped after `SEPARATION_ABANDON_TTL_MS`).
- **Perso returns 402** — workspace quota is exhausted. Status maps to `402 Payment Required` on the BFF response.
