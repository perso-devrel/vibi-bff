package com.vibi.bff

/**
 * Cross-cutting limits and TTLs. Single source of truth for values that
 * appeared duplicated across routes/services. Keep this file flat — config
 * values that depend on env vars belong in `config/AppConfig.kt`.
 */

/**
 * Multipart upload cap. Applied to `receiveMultipart(formFieldLimit=...)`.
 *
 * Default 500MB. `MAX_UPLOAD_FILE_SIZE_MB` env var 로 오버라이드 — Cloud Run
 * `--memory` 가 1Gi 면 200MB, 4Gi 면 1000MB 등 메모리 tier 에 맞춰 조정.
 */
val MAX_UPLOAD_FILE_SIZE: Long =
    (System.getenv("MAX_UPLOAD_FILE_SIZE_MB")?.toLongOrNull() ?: 500L) * 1024 * 1024

/**
 * Separation 전용 audio upload cap. 모바일이 trim + audio extract 후 m4a/mp3/wav 만 보내므로
 * render upload cap 보다 훨씬 작게. 60min AAC 192k ≈ 86MB → 100MB 안전 마진.
 * `MAX_SEPARATION_AUDIO_MB` env var 로 오버라이드.
 */
val MAX_SEPARATION_AUDIO_SIZE: Long =
    (System.getenv("MAX_SEPARATION_AUDIO_MB")?.toLongOrNull() ?: 100L) * 1024 * 1024

/** How long to keep a FAILED job in memory so the client can poll its error. */
const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L // 5 min
