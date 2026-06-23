package com.vibi.bff.service

import com.vibi.bff.db.DeviceCodesTable
import com.vibi.bff.model.AuthUser
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** device-code 의 polling 상태. EXPIRED 는 저장값이 아니라 created_at + TTL 로 파생된다. */
enum class DeviceStatus { PENDING, AUTHORIZED, EXPIRED }

data class DeviceRecord(
    val deviceCode: String,
    val userCode: String,
    val status: DeviceStatus,
    /** AUTHORIZED 일 때만 non-null — 승인 콜백이 채운 사용자 식별자. */
    val user: AuthUser?,
    val createdAtMs: Long,
)

/**
 * device_codes 테이블 CRUD. plugin server/ 의 `auth/deviceStore.ts` 를 Kotlin/Exposed 로 포팅.
 *
 * - [create] — device/user 코드 발급(pending). user_code 는 CSPRNG (brute-force 표면이라 예측 불가해야 함).
 * - [poll] — deviceCode(UUID) 로 조회. TTL 경과 시 status 를 EXPIRED 로 파생.
 * - [authorize] — userCode 로 찾아 승인. 만료면 EXPIRED 반환(미승인).
 * - [delete] — 토큰 발급 후 single-use 소비 (유출 deviceCode 재폴링으로 추가 토큰 못 찍게).
 * - [deleteExpired] — TTL 지난 코드 sweep. 호출자(Application cleanup loop)가 주기 실행.
 *
 * JDBC blocking 이라 호출자는 Dispatchers.IO 에서 부른다(라우트 핸들러 참고).
 */
class DeviceCodeRepository(
    private val ttlMs: Long = TimeUnit.MINUTES.toMillis(10),
) {
    /** device/start 응답의 expiresIn(초)으로 노출. */
    val ttlSeconds: Long get() = ttlMs / 1000

    fun create(): DeviceRecord = transaction {
        val deviceCode = UUID.randomUUID().toString()
        val userCode = generateUserCode()
        val now = Instant.now()
        DeviceCodesTable.insert {
            it[DeviceCodesTable.deviceCode] = deviceCode
            it[DeviceCodesTable.userCode] = userCode
            it[DeviceCodesTable.status] = "pending"
            it[DeviceCodesTable.createdAt] = now
        }
        DeviceRecord(deviceCode, userCode, DeviceStatus.PENDING, null, now.toEpochMilli())
    }

    fun poll(deviceCode: String): DeviceRecord? {
        if (deviceCode.isBlank()) return null
        return transaction {
            DeviceCodesTable.selectAll()
                .where { DeviceCodesTable.deviceCode eq deviceCode }
                .singleOrNull()
                ?.let { rowToRecord(it) }
        }
    }

    fun authorize(userCode: String, user: AuthUser): DeviceRecord? = transaction {
        val normalized = normalizeUserCode(userCode)
        val existing = DeviceCodesTable.selectAll()
            .where { DeviceCodesTable.userCode eq normalized }
            .singleOrNull()
            ?: return@transaction null
        val current = rowToRecord(existing)
        // 만료된 코드는 승인하지 않는다 — status 가 EXPIRED 로 파생되어 그대로 반환.
        if (current.status == DeviceStatus.EXPIRED) return@transaction current
        DeviceCodesTable.update({ DeviceCodesTable.userCode eq normalized }) {
            it[DeviceCodesTable.status] = "authorized"
            it[DeviceCodesTable.userSub] = user.sub
            it[DeviceCodesTable.userEmail] = user.email
            it[DeviceCodesTable.userName] = user.name
            it[DeviceCodesTable.userRole] = user.role
            it[DeviceCodesTable.userPicture] = user.picture
        }
        DeviceCodesTable.selectAll()
            .where { DeviceCodesTable.userCode eq normalized }
            .single()
            .let { rowToRecord(it) }
    }

    fun delete(deviceCode: String) {
        if (deviceCode.isBlank()) return
        transaction {
            DeviceCodesTable.deleteWhere { DeviceCodesTable.deviceCode eq deviceCode }
        }
    }

    fun deleteExpired(): Int = transaction {
        val cutoff = Instant.now().minusMillis(ttlMs)
        DeviceCodesTable.deleteWhere { DeviceCodesTable.createdAt less cutoff }
    }

    private fun rowToRecord(row: ResultRow): DeviceRecord {
        val createdAtMs = row[DeviceCodesTable.createdAt].toEpochMilli()
        val expired = Instant.now().toEpochMilli() - createdAtMs > ttlMs
        val status = when {
            expired -> DeviceStatus.EXPIRED
            row[DeviceCodesTable.status] == "authorized" -> DeviceStatus.AUTHORIZED
            else -> DeviceStatus.PENDING
        }
        val sub = row[DeviceCodesTable.userSub]
        val user = if (sub != null) {
            AuthUser(
                sub = sub,
                email = row[DeviceCodesTable.userEmail] ?: "",
                name = row[DeviceCodesTable.userName] ?: "",
                picture = row[DeviceCodesTable.userPicture],
                role = row[DeviceCodesTable.userRole] ?: "user",
            )
        } else {
            null
        }
        return DeviceRecord(
            deviceCode = row[DeviceCodesTable.deviceCode],
            userCode = row[DeviceCodesTable.userCode],
            status = status,
            user = user,
            createdAtMs = createdAtMs,
        )
    }

    private fun generateUserCode(): String = buildString {
        repeat(USER_CODE_LENGTH) { append(USER_CODE_ALPHABET[secureRandom.nextInt(USER_CODE_ALPHABET.length)]) }
    }

    companion object {
        private val secureRandom = SecureRandom()
        // 시각적으로 헷갈리는 문자(0/O, 1/I/L, 5/S, 8/B) 제외.
        private const val USER_CODE_ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ2346789"
        private const val USER_CODE_LENGTH = 8

        /** 사용자가 페이지에 입력/전달한 코드 정규화 — 공백·소문자·구분자 흡수. */
        fun normalizeUserCode(userCode: String): String =
            userCode.trim().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    }
}
