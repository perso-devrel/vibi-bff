package com.vibi.bff

import com.vibi.bff.service.LocalStem
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.SeparationJob
import com.vibi.bff.service.SeparationService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/**
 * Unit tests for the dispose path on SeparationService — cleanupAbandoned 가
 * abandon TTL 지난 READY 잡을 reap 할 때 outputDir 까지 같이 지워지는지 회귀 가드.
 * mix 잡 / reservation 상태머신은 모바일 로컬 mix 로 이관되며 제거됨.
 */
class SeparationServiceDisposeTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "sep-dispose-test")
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
            spec = com.vibi.bff.model.SeparationSpec(),
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
    fun `dispose removes the job and deletes its outputDir`() {
        val job = seedReadyJob("sep-4")
        assertTrue(job.outputDir.exists())
        service.dispose("sep-4")
        assertFalse(job.outputDir.exists(), "outputDir should be deleted on dispose")
        assertNull(runBlocking { service.getJob("sep-4") })
    }
}
