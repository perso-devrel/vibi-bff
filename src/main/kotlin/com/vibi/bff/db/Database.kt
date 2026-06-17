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
                // Neon 은 idle connection 을 server-side 에서 ~5 min 후 끊는다. Hikari 기본
                // maxLifetime(30 min) 은 그보다 길어 stale 커넥션이 풀에 잔존, validate 단계에서
                // "connection has been closed" 경고 다발 발생. maxLifetime 을 Neon idle limit 보다
                // 짧게 잡아 Hikari 가 먼저 회전시키도록 한다.
                maxLifetime = 240_000
                // minimumIdle=0: idle 시 풀을 0까지 비운다. Hikari 기본은 minimumIdle=maximumPoolSize
                // 라 항상 풀만큼 커넥션을 유지하고 keepaliveTime 마다 ping 을 던져 Neon compute 가
                // 24/7 깨어있게 만든다(=과금). 0 으로 두면 마지막 사용 후 idleTimeout 안에 커넥션이
                // 전부 닫혀 ping 도 멈추고 Neon 이 suspend 된다. 반드시 SeparationDispatcher 의 idle
                // tick 완화와 함께 적용 — 한쪽이라도 주기적으로 DB 를 두드리면 suspend 가 안 된다.
                minimumIdle = 0
                // idleTimeout 을 짧게(30s) 잡아 마지막 쿼리 후 빠르게 풀을 비운다. keepaliveTime 은
                // idleTimeout 보다 길게 두어 idle 커넥션이 ping 전에 닫히게 — 사실상 keepalive 정지.
                // (커넥션이 30s 이상 살아남는 건 사용 중일 때뿐이고, 그땐 Neon 이 어차피 깨어있다.)
                idleTimeout = 30_000
                keepaliveTime = 120_000
                // 첫 요청이 풀을 0→1 로 키울 때 Neon cold start(수 초)를 견디도록 validation 여유.
                validationTimeout = 10_000
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
