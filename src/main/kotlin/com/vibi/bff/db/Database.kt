package com.vibi.bff.db

import com.vibi.bff.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/**
 * HikariCP 풀 + Flyway 마이그레이션 + Exposed Database 연결을 1회 초기화한다.
 *
 * Neon (managed Postgres) JDBC URL 을 받아 그대로 사용 — vendor 종속성 없어 Cloud Run /
 * Cloudflare Containers 어디서든 동일하게 동작. Flyway 가 `classpath:db/migration/V*.sql`
 * 을 부팅 시 1회 적용.
 *
 * 단위 테스트는 H2 in-memory (Postgres mode) 로 동일 코드 경로 검증 가능 —
 * [DbConfig] 의 `jdbc:h2:...` URL prefix 도 허용.
 */
object DbBootstrap {
    private val log = LoggerFactory.getLogger(javaClass)

    fun init(config: DbConfig): HikariDataSource {
        val isH2 = config.jdbcUrl.startsWith("jdbc:h2:")
        val hikari = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            driverClassName = if (isH2) "org.h2.Driver" else "org.postgresql.Driver"
            // 트랜잭션 격리 — Postgres default (READ_COMMITTED) 와 일치. 명시해 H2 와도 동등.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            // Neon free tier 는 idle 시 compute 가 suspended 상태로 들어가서 첫 connect 가
            // cold start 동안 boot. Hikari 의 connectionTimeout 이 Postgres 드라이버의 socket
            // login timeout 으로 propagate 되므로 default 30s 면 cold start 마진이 빠듯하다.
            // 60s + initializationFailTimeout 120s 로 첫 부팅 시 한 번의 cold start 를 견디게 함.
            if (!isH2) {
                connectionTimeout = 60_000
                initializationFailTimeout = 120_000
            }
        }
        val ds = HikariDataSource(hikari)

        val flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            // H2 에서는 baseline 자동, Postgres 는 빈 schema 시 default 동작 그대로
            .baselineOnMigrate(true)
            .load()
        val result = flyway.migrate()
        log.info("Flyway migrated: success={} migrationsExecuted={}", result.success, result.migrationsExecuted)

        Database.connect(ds)
        return ds
    }
}
