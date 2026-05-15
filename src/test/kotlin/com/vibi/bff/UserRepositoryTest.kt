package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import com.vibi.bff.service.UserRepository
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class UserRepositoryTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: UserRepository

    @BeforeTest
    fun setup() {
        // H2 PostgreSQL compatibility mode 로 Flyway + Exposed 동일 코드 경로 검증.
        // 매 테스트마다 fresh DB — DB_CLOSE_DELAY=-1 없으면 connection 닫힐 때 schema 날아가지만,
        // 우리는 매 테스트 새로 init 하므로 default 가 안전.
        val unique = "test_" + System.nanoTime()
        dataSource = DbBootstrap.init(
            DbConfig(
                jdbcUrl = "jdbc:h2:mem:$unique;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
                maxPoolSize = 2,
            )
        )
        repo = UserRepository()
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    @Test
    fun `upsert returns same UUID for same provider+sub`() {
        val id1 = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        val id2 = repo.upsert(AuthProvider.GOOGLE, "g-123", "a@example.com", "Alice", null)
        assertEquals(id1, id2)
    }

    @Test
    fun `upsert returns different UUID for different provider sub`() {
        val google = repo.upsert(AuthProvider.GOOGLE, "g-1", "a@example.com", "Alice", null)
        val apple = repo.upsert(AuthProvider.APPLE, "a-1", "a@example.com", "Alice", null)
        assertNotEquals(google, apple)
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
}
