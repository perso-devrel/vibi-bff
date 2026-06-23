package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.vibi.bff.model.AuthUser
import com.vibi.bff.service.DeviceCodeRepository
import com.vibi.bff.service.DeviceStatus
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H2 PostgreSQL mode + Flyway(V11) 로 device-code 플로우 검증.
 * plugin server/ 의 deviceStore.ts 포팅 동등성: pending→authorize→single-use→expiry.
 */
class DeviceCodeRepositoryTest {

    private lateinit var dataSource: HikariDataSource

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
    }

    @AfterTest
    fun teardown() {
        dataSource.close()
    }

    private fun user(): AuthUser = AuthUser(
        sub = UUID.randomUUID().toString(),
        email = "editor@example.com",
        name = "Editor",
        picture = "https://img/x.png",
        role = "user",
    )

    @Test
    fun `create issues distinct device and user codes, poll is pending`() {
        val repo = DeviceCodeRepository()
        val rec = repo.create()
        assertTrue(rec.deviceCode.isNotBlank())
        assertEquals(8, rec.userCode.length)
        assertEquals(DeviceStatus.PENDING, rec.status)
        assertNull(rec.user)

        val polled = repo.poll(rec.deviceCode)
        assertNotNull(polled)
        assertEquals(DeviceStatus.PENDING, polled.status)
        assertNull(polled.user)
    }

    @Test
    fun `authorize then poll returns authorized with the user`() {
        val repo = DeviceCodeRepository()
        val rec = repo.create()
        val u = user()

        val authorized = repo.authorize(rec.userCode, u)
        assertNotNull(authorized)
        assertEquals(DeviceStatus.AUTHORIZED, authorized.status)

        val polled = repo.poll(rec.deviceCode)
        assertNotNull(polled)
        assertEquals(DeviceStatus.AUTHORIZED, polled.status)
        val pu = polled.user
        assertNotNull(pu)
        assertEquals(u.sub, pu.sub)
        assertEquals(u.email, pu.email)
        assertEquals(u.name, pu.name)
        assertEquals(u.role, pu.role)
        assertEquals(u.picture, pu.picture)
    }

    @Test
    fun `authorize normalizes user code (case and separators)`() {
        val repo = DeviceCodeRepository()
        val rec = repo.create()
        // 사용자가 소문자/공백/대시로 입력해도 매칭돼야 한다.
        val messy = rec.userCode.lowercase().chunked(4).joinToString("-") { " $it " }
        val authorized = repo.authorize(messy, user())
        assertNotNull(authorized)
        assertEquals(DeviceStatus.AUTHORIZED, authorized.status)
    }

    @Test
    fun `delete makes the code single-use`() {
        val repo = DeviceCodeRepository()
        val rec = repo.create()
        repo.authorize(rec.userCode, user())
        repo.delete(rec.deviceCode)
        // 발급 후 소비 — 재폴링 시 더 이상 존재하지 않음(invalid_device_code).
        assertNull(repo.poll(rec.deviceCode))
    }

    @Test
    fun `expired code polls as EXPIRED and cannot be authorized`() {
        // ttlMs 음수 → 생성 즉시 만료로 파생.
        val repo = DeviceCodeRepository(ttlMs = -1L)
        val rec = repo.create()

        val polled = repo.poll(rec.deviceCode)
        assertNotNull(polled)
        assertEquals(DeviceStatus.EXPIRED, polled.status)

        val authorized = repo.authorize(rec.userCode, user())
        assertNotNull(authorized)
        assertEquals(DeviceStatus.EXPIRED, authorized.status)
        assertNull(authorized.user) // 승인 안 됨
    }

    @Test
    fun `deleteExpired removes timed-out codes`() {
        val repo = DeviceCodeRepository(ttlMs = -1L)
        repo.create()
        repo.create()
        val removed = repo.deleteExpired()
        assertTrue(removed >= 2, "expected to sweep expired codes, removed=$removed")
    }
}
