package com.vibi.bff.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * users 스키마. 컬럼 길이는 V1__users.sql 의 DDL 과 1:1 — 둘 중 하나만 바꾸면
 * runtime constraint violation. 변경 시 새 Flyway 마이그레이션 파일 (V2__...sql)
 * + 본 테이블 동시 갱신.
 */
object UsersTable : UUIDTable("users", "id") {
    val provider = varchar("provider", 16)
    val providerSub = varchar("provider_sub", 255)
    val email = varchar("email", 320)
    val name = varchar("name", 255)
    val picture = varchar("picture", 2048).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
