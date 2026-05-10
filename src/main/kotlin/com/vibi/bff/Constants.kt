package com.vibi.bff

/**
 * Cross-cutting limits and TTLs. Single source of truth for values that
 * appeared duplicated across routes/services. Keep this file flat — config
 * values that depend on env vars belong in `config/AppConfig.kt`.
 */

/** Multipart upload cap. Applied to `receiveMultipart(formFieldLimit=...)`. */
const val MAX_UPLOAD_FILE_SIZE = 500L * 1024 * 1024 // 500MB

/** How long to keep a FAILED job in memory so the client can poll its error. */
const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L // 5 min

/** How long a READY job's output (subtitle / autodub) stays before GC reclaims its files.
 *  Render output 은 별도 TTL ([com.vibi.bff.service.RenderService.jobTtlMs] = 2h, lastAccessedAt 기반). */
const val READY_JOB_TTL_MS = 60 * 60 * 1000L // 1 hour
