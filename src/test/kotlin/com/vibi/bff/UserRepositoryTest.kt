package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class UserRepositoryTest {

    private val testDb = TestDatabase()
    private lateinit var repo: UserRepository

    @BeforeTest
    fun setup() {
        // H2 PostgreSQL mode + Flyway. TestDatabase 가 매 테스트 fresh DB + teardown 에서
        // Exposed 레지스트리 정리(공유 IO 스레드 cross-test 오염 방지).
        testDb.start()
        repo = UserRepository()
    }

    @AfterTest
    fun teardown() {
        testDb.stop()
    }

    @Test
    fun `upsert returns same UUID for same provider+sub`() {
        val first = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        val second = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        assertEquals(first.id, second.id)
        // isNewUser 는 첫 호출에서만 true (그 외 필드는 UPDATE 분기로 동일하게 떨어짐).
        assertEquals(true, first.isNewUser)
        assertEquals(false, second.isNewUser)
    }

    @Test
    fun `upsert returns different UUID for different provider sub`() {
        val google = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        val apple = repo.upsert(AuthProvider.APPLE, "a-1", "a@example.com", "Alice", null)
        assertNotEquals(google.id, apple.id)
    }

    @Test
    fun `upsert updates email and name on existing row`() {
        repo.upsert(AuthProvider.APPLE, "a-1", "old@example.com", "Old Name", null)
        repo.upsert(AuthProvider.APPLE, "a-1", "new@example.com", "New Name", "pic.png")

        val row = transaction {
            UsersTable.selectAll().where { UsersTable.providerSub eq "a-1" }.single()
        }
        assertEquals("new@example.com", row[UsersTable.email])
        assertEquals("New Name", row[UsersTable.name])
        assertEquals("pic.png", row[UsersTable.picture])
    }

    @Test
    fun `upsert returns default role 'user' for new row`() {
        val upserted = repo.upsert(AuthProvider.GOOGLE, "g-new", "x@example.com", "X", null)
        assertEquals("user", upserted.role)
    }

    @Test
    fun `upsert preserves admin role across re-login`() {
        val first = repo.upsert(AuthProvider.GOOGLE, "g-admin", "ops@example.com", "Ops", null)
        // 운영자가 SQL 로 admin 승격한 상황 시뮬레이션.
        transaction {
            UsersTable.update({ UsersTable.id eq first.id }) { it[role] = "admin" }
        }
        // 같은 사용자가 재로그인 → upsert 가 'admin' 을 'user' 로 강등시키면 안 됨.
        val second = repo.upsert(AuthProvider.GOOGLE, "g-admin", "ops@example.com", "Ops Renamed", null)
        assertEquals(first.id, second.id)
        assertEquals("admin", second.role)
    }

    @Test
    fun `exists is true for upserted user, false for unknown and after delete`() {
        val u = repo.upsert(AuthProvider.GOOGLE, "g-exists", "e@example.com", "E", null)
        assertTrue(repo.exists(u.id))
        assertFalse(repo.exists(UUID.randomUUID())) // 본 적 없는 UUID
        repo.delete(u.id)
        assertFalse(repo.exists(u.id))               // 삭제 후 false → A-1 차단의 기반
    }
}
