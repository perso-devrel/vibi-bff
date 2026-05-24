package com.vibi.bff

import com.vibi.bff.service.LocalStem
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.SeparationJob
import com.vibi.bff.service.SeparationService
import io.mockk.*
import java.io.File
import kotlin.test.*

/**
 * Unit tests for the reservation state machine in SeparationService. Uses a
 * tiny reflection helper to seed a READY job without running the real Perso
 * pipeline — the pipeline itself is integration-level and excluded here.
 */
class SeparationServiceReservationTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "sep-reservation-test")
    private lateinit var service: SeparationService

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        testDir.mkdirs()
        val perso = mockk<PersoClient>(relaxed = true)
        val config = testAppConfig(storagePath = testDir.path).separation
        service = SeparationService(
            persoClient = perso,
            separationDir = testDir,
            config = config,
            pollIntervalMs = 100,
            maxPollMinutes = 1,
        )
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
        unmockkAll()
    }

    private fun seedReadyJob(jobId: String): SeparationJob {
        val outputDir = File(testDir, jobId).apply { mkdirs() }
        val stem = File(outputDir, "background.mp3").apply { writeText("x") }
        val sourceFile = File(testDir, "$jobId-source.wav").apply { writeText("dummy") }
        val job = SeparationJob(
            jobId = jobId,
            outputDir = outputDir,
            sourceFile = sourceFile,
            spec = com.vibi.bff.model.SeparationSpec(mediaType = "AUDIO"),
            audioPreExtracted = true,
        ).apply {
            status = "READY"
            stems = listOf(LocalStem("background", "배경음", stem))
        }
        // Access the private jobs map through reflection for test seeding.
        val field = SeparationService::class.java.getDeclaredField("jobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(service) as java.util.concurrent.ConcurrentHashMap<String, SeparationJob>
        jobs[jobId] = job
        return job
    }

    @Test
    fun `first reserve succeeds and second returns null`() {
        seedReadyJob("sep-1")
        val first = service.reserveForMix("sep-1", "mix-a")
        val second = service.reserveForMix("sep-1", "mix-b")
        assertNotNull(first)
        assertNull(second, "second concurrent reservation must be rejected")
    }

    @Test
    fun `reserve on unknown job returns null`() {
        assertNull(service.reserveForMix("missing", "mix-a"))
    }

    @Test
    fun `reserve on non-READY job returns null`() {
        val job = seedReadyJob("sep-2")
        job.status = "PROCESSING"
        assertNull(service.reserveForMix("sep-2", "mix-a"))
    }

    @Test
    fun `releaseReservation lets the next reserve succeed`() {
        seedReadyJob("sep-3")
        assertNotNull(service.reserveForMix("sep-3", "mix-a"))
        service.releaseReservation("sep-3")
        assertNotNull(service.reserveForMix("sep-3", "mix-b"))
    }

    @Test
    fun `dispose removes the job and deletes its outputDir`() {
        val job = seedReadyJob("sep-4")
        assertTrue(job.outputDir.exists())
        service.dispose("sep-4")
        assertFalse(job.outputDir.exists(), "outputDir should be deleted on dispose")
        assertNull(service.getJob("sep-4"))
    }
}
