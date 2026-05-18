package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * user_credits — V5 마이그레이션과 1:1. 사용자별 단일 row.
 *
 * `userId` 는 users(id) FK 이자 PK. `ON DELETE CASCADE` 로 회원탈퇴 시 자동 정리.
 * `balance` 는 `CHECK (balance >= 0)` 로 음수 방어 — 동시 consume 시 잠금 (`forUpdate`) 사용.
 */
object UserCreditsTable : Table("user_credits") {
    val userId = uuid("user_id")
    val balance = integer("balance").default(0)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}
