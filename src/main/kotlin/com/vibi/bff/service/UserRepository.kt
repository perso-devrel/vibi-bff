package com.vibi.bff.service

import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/**
 * users 테이블의 `(provider, providerSub)` 기준 single-query upsert.
 *
 * Postgres `INSERT ... ON CONFLICT (...) DO UPDATE` 한 번으로 race condition 까지
 * 해소 — 동시 가입 시 두 트랜잭션이 같은 row 를 안전하게 공유. 결과로 internal UUID 를
 * 반환해 [com.vibi.bff.service.AuthService] 가 JWT sub 로 발급 + 향후 IAP
 * `appAccountToken` 으로 재사용.
 *
 * Exposed `upsert` 가 dialect 추상화 — Postgres 는 `ON CONFLICT`, H2 PostgreSQL mode 는
 * `MERGE` 로 매핑. 단위 테스트는 H2 in-memory 로 동일 경로 검증.
 */
class UserRepository {

    fun upsert(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UUID = transaction {
        val now = Instant.now()
        UsersTable.upsert(
            UsersTable.provider, UsersTable.providerSub,
            onUpdate = {
                it[UsersTable.email] = email
                it[UsersTable.name] = name
                it[UsersTable.picture] = picture
                it[UsersTable.updatedAt] = now
            },
        ) {
            it[UsersTable.id] = UUID.randomUUID()
            it[UsersTable.provider] = provider.dbValue
            it[UsersTable.providerSub] = providerSub
            it[UsersTable.email] = email
            it[UsersTable.name] = name
            it[UsersTable.picture] = picture
            it[UsersTable.createdAt] = now
            it[UsersTable.updatedAt] = now
        }
        // RETURNING id 는 dialect 차이가 커 별도 SELECT 로 안정성 우선 (UNIQUE 인덱스라 단일 row).
        UsersTable
            .select(UsersTable.id)
            .where { (UsersTable.provider eq provider.dbValue) and (UsersTable.providerSub eq providerSub) }
            .single()[UsersTable.id].value
    }
}
