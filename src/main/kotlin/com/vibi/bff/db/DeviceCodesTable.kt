package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * device_codes 스키마. 컬럼 길이/이름은 V11__device_codes.sql 의 DDL 과 1:1.
 *
 * 이 테이블은 RFC 8628 device authorization flow 의 단기 상태 저장소다(코드 TTL 10분,
 * 발급 후 single-use 삭제). Flyway 가 DDL 을 소유하고 Exposed 는 쿼리만 한다 —
 * SchemaUtils.create 로 만들지 않으므로 컬럼 선언 type 은 read/bind(String) 용도로만 쓰인다.
 *
 * 원래 plugin server/ 가 TEXT 컬럼으로 만들던 것을 BFF 가 흡수했다([V11__device_codes.sql]).
 */
object DeviceCodesTable : Table("device_codes") {
    val deviceCode = varchar("device_code", 64)
    val userCode = varchar("user_code", 16)
    val status = varchar("status", 16)
    val userSub = varchar("user_sub", 64).nullable()
    val userEmail = varchar("user_email", 320).nullable()
    val userName = varchar("user_name", 255).nullable()
    val userRole = varchar("user_role", 16).nullable()
    val userPicture = varchar("user_picture", 2048).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(deviceCode)
}
