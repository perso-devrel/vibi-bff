package com.vibi.bff

import com.vibi.bff.config.DbConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * DbConfig 의 fail-fast 검증 (#8) — Neon 은 공용 인터넷 경유라 Postgres 연결은 TLS 필수.
 * H2 in-memory 는 면제. boot 시 잘못된 DATABASE_URL 이 조용히 평문 연결되는 회귀 차단.
 */
class DbConfigTest {

    private fun dbConfig(url: String) = DbConfig(jdbcUrl = url, user = "u", password = "p", maxPoolSize = 2)

    @Test
    fun `postgres url without sslmode is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            dbConfig("jdbc:postgresql://ep-x.neon.tech/neondb")
        }
    }

    @Test
    fun `postgres url with sslmode=disable is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            dbConfig("jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=disable")
        }
    }

    @Test
    fun `postgres url with sslmode=require is accepted`() {
        dbConfig("jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=require")
    }

    @Test
    fun `postgres url with stronger sslmode=verify-full is accepted`() {
        dbConfig("jdbc:postgresql://ep-x.neon.tech/neondb?sslmode=verify-full")
    }

    @Test
    fun `h2 in-memory url is exempt from sslmode`() {
        dbConfig("jdbc:h2:mem:test;MODE=PostgreSQL")
    }
}
