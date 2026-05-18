package com.vibi.bff.service

import com.vibi.bff.plugins.PersoApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Poll Perso for project completion. Used by SeparationService.
 *
 * Each tick the caller's [onProgress] is invoked so the service can
 * update its own job's progress/reason fields. Returns when Perso
 * reports `Completed`; throws on failure or [maxPollMinutes] timeout.
 */
internal suspend fun pollPersoUntilComplete(
    persoClient: PersoClient,
    scope: CoroutineScope,
    projectSeq: Long,
    pollIntervalMs: Long,
    maxPollMinutes: Int,
    onProgress: (progress: Int, reason: String?) -> Unit,
) {
    val deadline = System.currentTimeMillis() + maxPollMinutes * 60_000L
    while (scope.isActive) {
        if (System.currentTimeMillis() > deadline) {
            throw RuntimeException("Perso polling timed out after $maxPollMinutes minutes")
        }
        val p = persoClient.getProgress(projectSeq)
        onProgress(p.progress, p.progressReason)
        when {
            p.hasFailed || p.progressReason == "Failed" ->
                throw PersoApiException(500, "Perso reported failure: ${p.progressReason}")
            p.progressReason == "Completed" -> return
        }
        delay(pollIntervalMs)
    }
}
