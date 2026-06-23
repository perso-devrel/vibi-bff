package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.AccountContentEraser
import com.vibi.bff.service.JobAnalyticsRepository
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 회원탈퇴 콘텐츠 erasure (GDPR/CCPA) 회귀 가드 — 사용자의 분리 스템 outputDir + 렌더 산출물
 * 로컬 파일이 실제로 삭제되고, 다른 사용자 콘텐츠는 보존되는지 검증. objectStore=null 로컬 경로.
 */
class AccountContentEraserTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var users: UserRepository
    private lateinit var queue: SeparationQueueRepository
    private lateinit var analytics: JobAnalyticsRepository
    private lateinit var separationDir: File
    private lateinit var renderDir: File
    private lateinit var eraser: AccountContentEraser

    @BeforeTest
    fun setup() {
        val unique = "test_" + System.nanoTime()
        dataSource = DbBootstrap.init(
            DbConfig(
                jdbcUrl = "jdbc:h2:mem:$unique;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
                maxPoolSize = 2,
            )
        )
        users = UserRepository()
        queue = SeparationQueueRepository()
        analytics = JobAnalyticsRepository()
        separationDir = Files.createTempDirectory("sep").toFile()
        renderDir = Files.createTempDirectory("render").toFile()
        eraser = AccountContentEraser(separationDir, renderDir, objectStore = null)
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
        separationDir.deleteRecursively()
        renderDir.deleteRecursively()
    }

    @Test
    fun `erase deletes the user's separation and render local content`() = runBlocking {
        val victim = users.upsert(AuthProvider.GOOGLE, "g-victim", "v@example.com", "V", null).id
        val other = users.upsert(AuthProvider.GOOGLE, "g-other", "o@example.com", "O", null).id

        // victim: 분리 잡 + 렌더 잡 (DB row + 로컬 파일).
        val sepJob = "sep-${UUID.randomUUID()}"
        queue.enqueue(sepJob, victim, null, 1_000, "inst-1")
        val sepDir = File(separationDir, sepJob).apply { mkdirs() }
        File(sepDir, "speaker_0.flac").writeText("stem-bytes")

        val renderJob = "render-${UUID.randomUUID()}"
        analytics.insertRenderJob(renderJob, victim, 1_000, "COMPLETED")
        val renderFile = File(renderDir, "$renderJob.mp4").apply { writeText("video-bytes") }

        // other: 보존되어야 하는 콘텐츠.
        val otherSep = "sep-${UUID.randomUUID()}"
        queue.enqueue(otherSep, other, null, 1_000, "inst-1")
        val otherDir = File(separationDir, otherSep).apply { mkdirs() }
        File(otherDir, "speaker_0.flac").writeText("other-stem")

        val stats = eraser.erase(victim)

        assertEquals(1, stats.separationJobs)
        assertEquals(1, stats.renderJobs)
        assertFalse(sepDir.exists(), "victim 분리 outputDir 삭제되어야 함")
        assertFalse(renderFile.exists(), "victim 렌더 산출물 삭제되어야 함")
        assertTrue(otherDir.exists(), "다른 사용자 콘텐츠는 보존되어야 함")
    }

    @Test
    fun `erase purges both the source stem and the derived WAV plugin cache from R2`() = runBlocking {
        val victim = users.upsert(AuthProvider.GOOGLE, "g-wav", "w@example.com", "W", null).id
        val sepJob = "sep-${UUID.randomUUID()}"
        queue.enqueue(sepJob, victim, null, 1_000, "inst-1")
        queue.markReady(sepJob, stemsJson = """[{"stemId":"speaker_0","label":"Speaker 0","ext":"flac"}]""")

        val store = mockk<ObjectStore>(relaxed = true)
        every { store.deleteObject(any()) } returns true
        val eraserWithR2 = AccountContentEraser(separationDir, renderDir, objectStore = store)

        eraserWithR2.erase(victim)

        // 소스 FLAC 키 + 플러그인 경로가 lazy 로 만든 파생 WAV 캐시 키 둘 다 purge 되어야 함 (orphan/erasure 완전성).
        verify { store.deleteObject("separation/$sepJob/speaker_0.flac") }
        verify { store.deleteObject("separation/$sepJob/speaker_0.wav") }
    }

    @Test
    fun `erase is a no-op for a user with no content`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-empty", "e@example.com", "E", null).id
        val stats = eraser.erase(u)
        assertEquals(0, stats.separationJobs)
        assertEquals(0, stats.renderJobs)
        assertEquals(0, stats.localDeleted)
    }
}
