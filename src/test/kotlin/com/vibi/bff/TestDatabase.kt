package com.vibi.bff

import com.vibi.bff.config.DbConfig
import com.vibi.bff.db.DbBootstrap
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 테스트용 H2(PostgreSQL mode) + Flyway DB 핸들 — 생성/정리를 한 곳에 모은다.
 *
 * **격리 핵심**: Exposed 의 전역 `defaultDatabase` 는 `Database.connect` 시 한 번만(=null 일 때만) 잡혀
 * 이후 connect 로 갱신되지 않는다. 그래서 어떤 테스트가 `dataSource.close()` 한 뒤에도 전역 default 는
 * 그 **닫힌 DB** 를 가리켜, 같은 JVM 의 다른 테스트가 `newSuspendedTransaction(Dispatchers.IO)` 로
 * 트랜잭션을 열면 닫힌 풀로 resolve 되어 `HikariDataSource has been closed` 가 난다.
 * → start() 에서 이번 DB 를 default 로 **명시 지정**하고, stop() 에서 default 를 비워 다음 테스트의
 *   connect 가 자기 DB 로 깨끗이 잡히게 한다.
 */
class TestDatabase {
    lateinit var dataSource: HikariDataSource
        private set
    private var db: Database? = null

    fun start() {
        val unique = "test_" + System.nanoTime()
        dataSource = DbBootstrap.init(
            DbConfig(
                jdbcUrl = "jdbc:h2:mem:$unique;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
                maxPoolSize = 2,
            )
        )
        // 방금 connect 된 Database 를 캡처해 전역 default 로 고정 — suspend(IO) 트랜잭션이 이 DB 로 resolve.
        val captured = transaction { this.db }
        db = captured
        TransactionManager.defaultDatabase = captured
    }

    fun stop() {
        dataSource.close()
        // 전역 default 를 비워 다음 테스트의 connect/start 가 자기 DB 를 잡게 한다(닫힌 DB 잔존 방지).
        if (TransactionManager.defaultDatabase == db) {
            TransactionManager.defaultDatabase = null
        }
        db = null
    }
}
