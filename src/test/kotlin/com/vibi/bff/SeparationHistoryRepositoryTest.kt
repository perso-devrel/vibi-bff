package com.vibi.bff

import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.UserRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #4(제출 시 projectId/메타 저장) 쓰기측 + #3(history) 읽기측 SQL 라운드트립을 H2 + Flyway(V12) 로 검증.
 * enqueue(metadata) → markProcessing(projectSeq) → markReady → listReadyHistory/delete/loadForScript.
 * (DB 정리는 [TestDatabase] 가 담당 — Exposed 레지스트리 cross-test 오염 방지.)
 */
class SeparationHistoryRepositoryTest {

    private val testDb = TestDatabase()
    private lateinit var queue: SeparationQueueRepository
    private lateinit var users: UserRepository

    private val stemsJson =
        """[{"stemId":"background","label":"BG","ext":"wav"},{"stemId":"speaker-0","label":"화자 1","ext":"flac"}]"""

    @BeforeTest
    fun setup() {
        testDb.start()
        queue = SeparationQueueRepository()
        users = UserRepository()
    }

    @AfterTest
    fun teardown() {
        testDb.stop()
    }

    @Test
    fun `enqueue stores history metadata and listReadyHistory returns it`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-hist", "h@example.com", "H", null)
        queue.enqueue(
            jobId = "sep-h1",
            userId = u.id,
            renderJobId = null,
            sourceDurationMs = 60_000L,
            bffInstanceId = "inst-1",
            projectId = "proj-1",
            fileName = "song.mp3",
            byteLength = 12_345L,
        )
        queue.markProcessing("sep-h1", 42L) // persoProjectSeq → hasScript true
        queue.markReady("sep-h1", stemsJson, actualDurationMs = 60_000L)

        val list = queue.listReadyHistory(u.id, "proj-1")
        assertEquals(1, list.size)
        val row = list.first()
        assertEquals("sep-h1", row.jobId)
        assertEquals("song.mp3", row.fileName)
        assertEquals(12_345L, row.byteLength)
        assertEquals(60_000L, row.durationMs)
        assertNotNull(row.stemsJson)
        // 마지막 statement 는 Unit 을 반환해야 한다 — assertNotNull 은 값(String)을 돌려줘서
        // 마지막에 두면 test fun 의 반환형이 Unit 이 아니게 되어 JUnit 이 메서드를 무시한다.
        assertTrue(row.hasScript, "persoProjectSeq set → hasScript")
    }

    @Test
    fun `listReadyHistory is project-scoped (null bucket excludes project rows)`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-scope", "s@example.com", "S", null)
        queue.enqueue("sep-p", u.id, null, 60_000L, "inst-1", projectId = "proj-1", fileName = "a.mp3", byteLength = 1L)
        queue.markReady("sep-p", stemsJson, 60_000L)
        // null(no-project) 버킷엔 proj-1 row 가 안 보여야 한다(null-safe eq).
        assertEquals(0, queue.listReadyHistory(u.id, null).size)
        assertEquals(1, queue.listReadyHistory(u.id, "proj-1").size)
    }

    @Test
    fun `listReadyHistory excludes non-ready and other owners`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-a", "a@example.com", "A", null)
        val other = users.upsert(AuthProvider.GOOGLE, "g-b", "b@example.com", "B", null)
        queue.enqueue("sep-q", u.id, null, 1000L, "inst-1", projectId = null) // QUEUED → 제외
        queue.enqueue("sep-other", other.id, null, 1000L, "inst-1", projectId = null)
        queue.markReady("sep-other", stemsJson, 1000L) // 다른 owner 의 READY → 제외
        assertEquals(0, queue.listReadyHistory(u.id, null).size)
        assertEquals(1, queue.listReadyHistory(other.id, null).size)
    }

    @Test
    fun `deleteOwnedReturningStemsJson removes the row and returns stems`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-del", "d@example.com", "D", null)
        val other = users.upsert(AuthProvider.GOOGLE, "g-del2", "d2@example.com", "D2", null)
        queue.enqueue("sep-d", u.id, null, 1000L, "inst-1", projectId = "proj-1")
        queue.markReady("sep-d", stemsJson, 1000L)
        // 남의 잡 삭제 시도 → no-op(null), row 보존.
        assertNull(queue.deleteOwnedReturningStemsJson("sep-d", other.id))
        assertEquals(1, queue.listReadyHistory(u.id, "proj-1").size)
        // owner 삭제 → stems_json 반환 + row 제거.
        val returned = queue.deleteOwnedReturningStemsJson("sep-d", u.id)
        assertNotNull(returned)
        assertTrue(returned.contains("background"))
        assertEquals(0, queue.listReadyHistory(u.id, "proj-1").size)
    }

    @Test
    fun `loadForScript returns owner status and projectSeq`() = runBlocking {
        val u = users.upsert(AuthProvider.GOOGLE, "g-scr", "c@example.com", "C", null)
        queue.enqueue("sep-s", u.id, null, 1000L, "inst-1", projectId = "proj-1")
        queue.markProcessing("sep-s", 77L)
        queue.markReady("sep-s", stemsJson, 1000L)
        val row = queue.loadForScript("sep-s")
        assertNotNull(row)
        assertEquals(u.id, row.ownerUserId)
        assertEquals("READY", row.status)
        assertEquals(77L, row.persoProjectSeq)
        assertNull(queue.loadForScript("sep-missing"))
    }
}
