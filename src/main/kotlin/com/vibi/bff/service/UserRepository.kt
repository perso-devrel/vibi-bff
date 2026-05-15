package com.vibi.bff.service

import com.vibi.bff.db.UsersTable
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * (provider, providerSub) 기준 user upsert + 조회. 신규 가입이면 UUID 생성 + insert,
 * 기존 row 이면 email/name/picture/updatedAt 갱신. 어느 경우든 internal UUID 반환 —
 * 호출자(AuthService)가 그 UUID 를 JWT sub 로 발급한다.
 *
 * UUID 는 향후 IAP `appAccountToken` 으로도 그대로 재사용 — StoreKit 2 의
 * appAccountToken 이 UUID 만 받기 때문이다.
 *
 * v1 은 row 자체 lookup 만 필요하므로 `User` 도메인 모델 없이 컬럼 직접 매핑.
 */
class UserRepository {

    /** 결과: 해당 user 의 internal UUID. 신규/기존 구분이 필요하면 별도 method 추가. */
    fun upsert(
        provider: String,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UUID = transaction {
        val now = Instant.now()
        val existing = UsersTable
            .selectAll()
            .where { (UsersTable.provider eq provider) and (UsersTable.providerSub eq providerSub) }
            .singleOrNull()
        if (existing != null) {
            val id = existing[UsersTable.id].value
            UsersTable.update({ UsersTable.id eq id }) {
                it[UsersTable.email] = email
                it[UsersTable.name] = name
                it[UsersTable.picture] = picture
                it[UsersTable.updatedAt] = now
            }
            id
        } else {
            val id = UUID.randomUUID()
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.provider] = provider
                it[UsersTable.providerSub] = providerSub
                it[UsersTable.email] = email
                it[UsersTable.name] = name
                it[UsersTable.picture] = picture
                it[UsersTable.createdAt] = now
                it[UsersTable.updatedAt] = now
            }
            id
        }
    }
}
