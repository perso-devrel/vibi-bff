package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * render_jobs 스키마. 컬럼은 V3__usage_jobs.sql 의 DDL 과 1:1. 변경 시 새 Flyway 마이그레이션
 * 파일 + 본 테이블 동시 갱신.
 *
 * id 는 RenderJob.jobId ("render-<uuid>") 와 동일 텍스트 — surrogate UUID PK 안 둠.
 */
object RenderJobsTable : Table("render_jobs") {
    val id = text("id")
    // user_id 는 회원탈퇴 시 SET NULL — V5 마이그레이션 (`ON DELETE SET NULL`) 과 동기.
    // 분석용 row 는 익명 (NULL) 으로 보존되어 KPI 연속성 유지.
    val userId = uuid("user_id").nullable()
    val sourceDurationMs = long("source_duration_ms")
    val status = text("status")
    val createdAt = timestamp("created_at")
    val finishedAt = timestamp("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
