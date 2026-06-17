package com.vibi.bff.service

import com.vibi.bff.db.UsersTable
import com.vibi.bff.model.AuthProvider
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/**
 * users 테이블의 `(provider, providerSub)` 기준 single-query upsert.
 *
 * Postgres `INSERT ... ON CONFLICT (...) DO UPDATE` 한 번으로 race condition 까지
 * 해소 — 동시 가입 시 두 트랜잭션이 같은 row 를 안전하게 공유. 결과로 internal UUID 와
 * role 을 반환해 [com.vibi.bff.service.AuthService] 가 JWT sub + role 클레임으로 발급.
 *
 * Exposed `upsert` 가 dialect 추상화 — Postgres 는 `ON CONFLICT`, H2 PostgreSQL mode 는
 * `MERGE` 로 매핑. 단위 테스트는 H2 in-memory 로 동일 경로 검증.
 *
 * role 은 upsert 시 보존된다 — onUpdate 에 명시 안 함. 운영자가 SQL `UPDATE` 로
 * 'admin' 으로 승격한 row 가 재로그인으로 'user' 로 강등되지 않도록.
 */
/**
 * isNewUser: true 면 이번 upsert 가 신규 row INSERT (재로그인은 false). 호출자가 신규 가입
 * 보너스(크레딧 그랜트) 같은 1회성 사이드이펙트를 분기하기 위해 노출. createdAt == updatedAt
 * 으로 판별 — onUpdate 에서 updatedAt 만 갱신하므로 INSERT 직후 한 번만 둘이 일치한다.
 */
data class UpsertedUser(val id: UUID, val role: String, val isNewUser: Boolean)

class UserRepository {

    fun upsert(
        provider: AuthProvider,
        providerSub: String,
        email: String,
        name: String,
        picture: String?,
    ): UpsertedUser = transaction {
        val now = Instant.now()
        UsersTable.upsert(
            UsersTable.provider, UsersTable.providerSub,
            onUpdate = {
                it[UsersTable.email] = email
                it[UsersTable.name] = name
                it[UsersTable.picture] = picture
                it[UsersTable.updatedAt] = now
                // role 은 의도적으로 미터치 — 운영자가 admin 으로 올린 row 가 재로그인으로 강등되지 않도록.
                // createdAt 도 미터치 — 신규 가입 판별 기준이라 INSERT 시점에 고정돼야 함.
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
            // role 은 column default ('user') 가 채움.
        }
        // RETURNING 은 dialect 차이가 커 별도 SELECT 로 안정성 우선 (UNIQUE 인덱스라 단일 row).
        val row = UsersTable
            .select(UsersTable.id, UsersTable.role, UsersTable.createdAt, UsersTable.updatedAt)
            .where { (UsersTable.provider eq provider.dbValue) and (UsersTable.providerSub eq providerSub) }
            .single()
        // INSERT 면 onUpdate 가 안 돌아 createdAt == updatedAt (둘 다 위의 now). UPDATE 면
        // onUpdate 가 updatedAt 만 갱신하므로 createdAt != updatedAt. 동시 가입 race 의
        // ON CONFLICT loser 도 UPDATE 분기라 false 로 안전하게 떨어진다.
        UpsertedUser(
            id = row[UsersTable.id].value,
            role = row[UsersTable.role],
            isNewUser = row[UsersTable.createdAt] == row[UsersTable.updatedAt],
        )
    }

    /**
     * 회원탈퇴 — users row 삭제. V5 마이그레이션의 FK cascade 가 자식 row 정리를 책임진다:
     *   • user_credits — `ON DELETE CASCADE` → 함께 삭제
     *   • render_jobs / separation_jobs / credit_transactions — `ON DELETE SET NULL` → 익명 row 로 보존
     *
     * 같은 (provider, providerSub) 로 재가입 시점에는 [upsert] 가 새 UUID 의 row 를 생성하므로
     * 이전 잡 분석 row 는 새 사용자와 다른 UUID — 익명 row 로 영구 격리된다.
     *
     * 반환: 실제 삭제된 row 수 (0 = 존재하지 않던 user — 호출자가 200/404 로 분기 가능).
     */
    fun delete(userId: UUID): Int = transaction {
        UsersTable.deleteWhere { UsersTable.id eq userId }
    }

    /**
     * users row 존재 여부 — 삭제된 계정의 아직-유효한 JWT 가 비용/엔타이틀먼트 mutating
     * endpoint 를 타는 것을 차단하기 위한 fresh check. PK 단일 lookup + limit(1) 이라 저렴.
     * 핫패스(상태 폴링 GET)에는 적용하지 않고 제출(POST) 계열에서만 호출한다.
     */
    fun exists(userId: UUID): Boolean = transaction {
        UsersTable.selectAll().where { UsersTable.id eq userId }.limit(1).any()
    }
}
