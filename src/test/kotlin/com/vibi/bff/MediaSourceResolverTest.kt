package com.vibi.bff

import com.vibi.bff.service.MediaSourceResolver
import com.vibi.bff.service.RenderJob
import com.vibi.bff.service.RenderService
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 follow-up regression coverage for the [MediaSourceResolver] copy
 * strategy + [RenderService.acquireRenderOutputCopy] cleanup race.
 *
 * The render output file must survive being passed to N downstream
 * pipelines (auto-subtitle deletes the source after audio extract,
 * auto-dub renames it, separation deletes it after Perso upload). The
 * resolver therefore returns a *copy* under
 * [com.vibi.bff.service.FileStorageService.editedSourceDir]; the
 * downstream pipeline owns the copy and the render output stays intact
 * for the next consumer.
 */
class MediaSourceResolverTest {

    private val testRoot = File(System.getProperty("java.io.tmpdir"), "media-source-resolver-test")
    private val renderDir = File(testRoot, "render")
    private val editedSourceDir = File(testRoot, "edited-source")
    private lateinit var renderService: RenderService
    private lateinit var resolver: MediaSourceResolver

    @BeforeTest
    fun setup() {
        testRoot.deleteRecursively()
        renderDir.mkdirs()
        editedSourceDir.mkdirs()
        // Long TTL so the cleanup task never fires from the schedule mid-test —
        // we trigger it explicitly via cleanupExpiredJobsForTest().
        renderService = RenderService(renderDir, jobTtlMs = 60 * 60 * 1000L)
        resolver = MediaSourceResolver(renderService, editedSourceDir)
    }

    @AfterTest
    fun cleanup() {
        renderService.shutdown()
        testRoot.deleteRecursively()
    }

    private fun seedCompletedJob(jobId: String, contents: String): File {
        val outputFile = File(renderDir, "$jobId.mp4").apply { writeText(contents) }
        val job = RenderJob(
            jobId = jobId,
            outputFile = outputFile,
        ).also {
            it.status = "COMPLETED"
            it.progress = 100
        }
        renderService.registerJobForTest(job)
        return outputFile
    }

    @Test
    fun `acquireRenderOutputCopy returns a fresh copy under editedSourceDir`() {
        val rendered = seedCompletedJob("render-copy-1", "rendered-bytes")
        val copy = renderService.acquireRenderOutputCopy("render-copy-1", editedSourceDir)
        assertNotNull(copy)
        assertNotEquals(rendered.absolutePath, copy.absolutePath, "copy must NOT alias the render output")
        assertEquals(editedSourceDir.absolutePath, copy.parentFile.absolutePath)
        assertEquals("rendered-bytes", copy.readText())
        assertTrue(rendered.exists(), "original render output must still be present")
    }

    @Test
    fun `acquireRenderOutputCopy returns null for missing or not-completed jobs`() {
        // Unknown id.
        assertEquals(null, renderService.acquireRenderOutputCopy("nope", editedSourceDir))

        // Status != COMPLETED.
        val outputFile = File(renderDir, "render-pending.mp4").apply { writeText("x") }
        renderService.registerJobForTest(
            RenderJob(jobId = "render-pending", outputFile = outputFile),
        )
        assertEquals(null, renderService.acquireRenderOutputCopy("render-pending", editedSourceDir))

        // Output reaped (status COMPLETED but file gone).
        seedCompletedJob("render-reaped", "x").delete()
        assertEquals(null, renderService.acquireRenderOutputCopy("render-reaped", editedSourceDir))
    }

    /** Two consecutive downstream calls both succeed because each gets its own copy
     *  — and downstream services are free to delete/rename the copy without harming
     *  the original render output. */
    @Test
    fun `resolver hands two consecutive callers independent owned copies`() {
        val rendered = seedCompletedJob("render-2x", "stable-bytes")

        val firstCopy = resolver.resolve(filePart = null, editedRenderJobId = "render-2x")
        // Simulate AutoSubtitleService.runPipeline deleting the source after
        // audio extract.
        firstCopy.delete()

        val secondCopy = resolver.resolve(filePart = null, editedRenderJobId = "render-2x")
        // Simulate AutoDubService.runPipeline renaming the source aside.
        val moved = File(editedSourceDir, "moved.mp4")
        assertTrue(secondCopy.renameTo(moved), "rename should succeed on local fs")

        assertTrue(rendered.exists(), "render output must survive both downstream consumers")
        assertEquals("stable-bytes", rendered.readText())
        assertNotEquals(firstCopy.absolutePath, secondCopy.absolutePath, "each resolve must mint a unique copy")
    }

    /** [resolver.resolve] requires either filePart or editedRenderJobId — caller
     *  must not be able to coast through both being null. */
    @Test
    fun `resolver rejects neither filePart nor editedRenderJobId`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(filePart = null, editedRenderJobId = null)
        }
    }

    /** When both are supplied, render output wins and the file part is deleted
     *  (no dead bytes left on disk). */
    @Test
    fun `resolver prefers editedRenderJobId and deletes redundant filePart`() {
        seedCompletedJob("render-priority", "rendered-bytes")
        val upload = File(editedSourceDir, "stale-upload.bin").apply { writeText("upload") }

        val resolved = resolver.resolve(filePart = upload, editedRenderJobId = "render-priority")

        assertTrue(resolved.readText() == "rendered-bytes", "must yield the render output, not the upload")
        assertTrue(!upload.exists(), "stale upload must be removed by resolve()")
    }

    /** Cleanup vs acquire race: acquireRenderOutputCopy bumps lastAccessedAt
     *  inside the same per-job lock that cleanupExpiredJobs takes before
     *  deleting outputFile. Best-effort coverage — the deterministic shape we
     *  can assert here is that AFTER acquire returns a non-null copy, the
     *  cleanup pass cannot reap the just-touched job (bumped timestamp). */
    @Test
    fun `cleanup defers to a freshly-touched job after acquireRenderOutputCopy`() {
        // Short TTL service so we can simulate "expired" without wall-clock waits.
        val shortRenderDir = File(testRoot, "render-short").apply { mkdirs() }
        val shortRenderService = RenderService(shortRenderDir, jobTtlMs = 50L)
        try {
            val outputFile = File(shortRenderDir, "render-race.mp4").apply { writeText("race-bytes") }
            // Backdate lastAccessedAt so the job would normally be eligible for cleanup.
            shortRenderService.registerJobForTest(
                RenderJob(
                    jobId = "render-race",
                    outputFile = outputFile,
                    lastAccessedAt = System.currentTimeMillis() - 60_000L,
                ).also {
                    it.status = "COMPLETED"
                    it.progress = 100
                },
            )

            // Acquire bumps lastAccessedAt inside the job lock.
            val copy = shortRenderService.acquireRenderOutputCopy("render-race", editedSourceDir)
            assertNotNull(copy)

            // Cleanup pass should now see lastAccessedAt within the TTL window
            // and skip deletion. Even though the original timestamp said "stale",
            // the acquire updated it.
            shortRenderService.cleanupExpiredJobsForTest()
            assertTrue(outputFile.exists(), "output must survive cleanup right after a successful acquire")
            assertEquals("race-bytes", outputFile.readText())
        } finally {
            shortRenderService.shutdown()
        }
    }

    /** Without a fresh acquire, cleanup *does* delete an expired job — guards
     *  against a regression where the cleanup-time lock check accidentally
     *  no-ops in the common path. */
    @Test
    fun `cleanup still reaps expired jobs that have not been touched`() {
        val expiredDir = File(testRoot, "render-expired").apply { mkdirs() }
        val shortRenderService = RenderService(expiredDir, jobTtlMs = 50L)
        try {
            val outputFile = File(expiredDir, "old.mp4").apply { writeText("old") }
            shortRenderService.registerJobForTest(
                RenderJob(
                    jobId = "old-job",
                    outputFile = outputFile,
                    lastAccessedAt = System.currentTimeMillis() - 60_000L,
                ).also {
                    it.status = "COMPLETED"
                },
            )
            shortRenderService.cleanupExpiredJobsForTest()
            assertTrue(!outputFile.exists(), "stale job's outputFile must be reaped")
            assertEquals(null, shortRenderService.getJob("old-job"))
        } finally {
            shortRenderService.shutdown()
        }
    }
}
