package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * separation_jobs 스키마. V3__usage_jobs.sql 과 1:1.
 *
 * id 는 SeparationJob.jobId ("sep-<uuid>") 와 동일 텍스트.
 * renderJobId 는 spec.editedRenderJobId 경유 시 [RenderJobsTable.id] 참조,
 * legacy multipart 업로드 경로면 null.
 */
object SeparationJobsTable : Table("separation_jobs") {
    val id = text("id")
    // user_id 는 회원탈퇴 시 SET NULL — V5 마이그레이션 (`ON DELETE SET NULL`) 과 동기.
    val userId = uuid("user_id").nullable()
    val renderJobId = text("render_job_id").nullable()
    val sourceDurationMs = long("source_duration_ms")
    val status = text("status")
    val createdAt = timestamp("created_at")
    val finishedAt = timestamp("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
