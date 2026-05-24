package com.vibi.bff.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * separation_jobs 스키마. V3__usage_jobs.sql + V6__separation_queue.sql 와 1:1.
 *
 * id 는 SeparationJob.jobId ("sep-<uuid>") 와 동일 텍스트.
 * renderJobId 는 spec.editedRenderJobId 경유 시 [RenderJobsTable.id] 참조,
 * legacy multipart 업로드 경로면 null.
 *
 * Queue 컬럼 (V6) — Perso 동시성 제어용 source-of-truth. SeparationQueueRepository 가 read/write.
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

    // V6: 큐 컬럼. bffInstanceId 는 enqueue 한 BFF 프로세스 UUID — dispatcher 는 자기 인스턴스
    // row 만 claim (소스 파일이 해당 인스턴스 로컬 디스크). persoProjectSeq 는 SUBMITTING →
    // PROCESSING 전이 시 기록 — 인스턴스 재시작 후 polling resumption 의 entry point.
    val bffInstanceId = text("bff_instance_id").nullable()
    val persoProjectSeq = long("perso_project_seq").nullable()
    val queuedAt = timestamp("queued_at").nullable()
    val dispatchedAt = timestamp("dispatched_at").nullable()
    val attemptCount = integer("attempt_count")

    // V7: stem 메타 영속화 — 인스턴스가 READY 직후 죽어도 새 인스턴스가 DB + R2 만으로 GET 응답
    // 재구축. stemsJson 은 JSON 배열 [{stemId, label, ext}, ...]; R2 object key 는 ObjectKey
    // .separationStem 으로 계산. actualDurationMs 는 SeparationService 가 측정한 speaker stem 길이.
    val stemsJson = text("stems_json").nullable()
    val actualDurationMs = long("actual_duration_ms").nullable()

    override val primaryKey = PrimaryKey(id)
}
