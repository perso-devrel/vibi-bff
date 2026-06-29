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

/**
 * 클라이언트가 보내는 audio 컨테이너 확장자 화이트리스트 — m4a (AAC) / mp3 / wav (PCM).
 * `/separate` 분리 입력(video 확장자와 합쳐 사용)과 `/peaks` 디코드 양쪽의 단일 소스.
 * flac 은 Perso 가 silent fail 하므로 제외 (CLAUDE.md FLAC 회귀 참조).
 */
val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "wav")

/** How long to keep a FAILED job in memory so the client can poll its error. */
const val FAILED_JOB_TTL_MS = 5 * 60 * 1000L // 5 min
