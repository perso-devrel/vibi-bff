package com.vibi.bff.model

import kotlinx.serialization.Serializable

/**
 * POST /api/v2/peaks 응답. plugin 클라(`src/jobs/peaksClient.ts`)가 `{peaks:number[], durationSec}`
 * 로 읽어 Float32Array 로 변환한다. peaks 는 0..1 정규화 진폭.
 */
@Serializable
data class PeaksResponse(
    val peaks: List<Float>,
    val durationSec: Double,
)

@Serializable
data class PeaksError(
    val error: String,
)
