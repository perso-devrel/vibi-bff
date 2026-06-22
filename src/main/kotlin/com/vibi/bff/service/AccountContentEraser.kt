package com.vibi.bff.service

import com.vibi.bff.db.RenderJobsTable
import com.vibi.bff.db.SeparationJobsTable
import com.vibi.bff.plugins.AppJson
import com.vibi.bff.routes.ObjectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * 회원탈퇴 시 사용자 콘텐츠(음원분리 스템 · 렌더 산출물)를 로컬 디스크 + R2 에서 제거한다 —
 * GDPR 17조 / CCPA right-to-erasure. **반드시 users row 삭제 BEFORE** 에 호출해야 한다:
 * V5 FK 정책이 탈퇴 시 separation_jobs/render_jobs.user_id 를 SET NULL 로 익명화하므로,
 * 삭제가 먼저 일어나면 잡↔user 링크가 끊겨 enumerate 가 불가능해진다.
 *
 * **best-effort**: 개별 파일/오브젝트 삭제 실패는 WARN 후 계속 — 일부 잔존이 전체 탈퇴
 * 트랜잭션을 막지 않도록. R2 쪽 잔존분은 lifecycle 규칙(`deploy/r2-lifecycle.json`)이 짧은
 * TTL 후 만료시키는 것이 2차 안전망.
 *
 * **한계 (별도 메커니즘이 담당)**:
 *  - `uploads/` 원본은 잡 단위로 인덱싱되지 않는다(파이프라인이 업로드 직후 source 를 삭제).
 *    무기한 잔존 방지는 시간 기반 GC([FileStorageService.sweepUploadsOlderThan])가 책임.
 *  - Perso AI 측 복사본은 Perso 가 삭제 API 를 제공하면 추가 연동. 현재 미연동 — Perso 의
 *    자체 보존정책에 의존하며, 개인정보처리방침에 하위 처리자로 고지한다.
 */
class AccountContentEraser(
    private val separationDir: File,
    private val renderDir: File,
    private val objectStore: ObjectStore?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class EraseStats(
        val separationJobs: Int,
        val renderJobs: Int,
        val localDeleted: Int,
        val r2Deleted: Int,
    )

    suspend fun erase(userId: UUID): EraseStats = withContext(Dispatchers.IO) {
        val (sepJobs, renderJobs) = transaction {
            val sep = SeparationJobsTable
                .select(SeparationJobsTable.id, SeparationJobsTable.stemsJson)
                .where { SeparationJobsTable.userId eq userId }
                .map { it[SeparationJobsTable.id] to it[SeparationJobsTable.stemsJson] }
            val ren = RenderJobsTable
                .select(RenderJobsTable.id)
                .where { RenderJobsTable.userId eq userId }
                .map { it[RenderJobsTable.id] }
            sep to ren
        }

        var localDeleted = 0
        var r2Deleted = 0

        // 음원분리: 로컬 outputDir 전체 + 각 stem 의 R2 오브젝트.
        for ((jobId, stemsJson) in sepJobs) {
            val dir = File(separationDir, jobId)
            if (dir.exists() && runCatching { dir.deleteRecursively() }.getOrDefault(false)) localDeleted++
            if (objectStore != null && stemsJson != null) {
                val metas = runCatching {
                    AppJson.decodeFromString(ListSerializer(StemMeta.serializer()), stemsJson)
                }.getOrDefault(emptyList())
                for (m in metas) {
                    val key = ObjectKey.separationStem(jobId, m.stemId, m.ext.ifBlank { "flac" })
                    if (objectStore.deleteObject(key)) r2Deleted++
                }
            }
        }

        // 렌더: 로컬 <jobId>.* + R2 산출물(확장자 mp4/m4a 둘 다 시도 — 어느 쪽인지 미보존).
        for (jobId in renderJobs) {
            (renderDir.listFiles { f -> f.isFile && f.name.startsWith("$jobId.") } ?: emptyArray())
                .forEach { if (runCatching { it.delete() }.getOrDefault(false)) localDeleted++ }
            if (objectStore != null) {
                for (ext in RENDER_OUTPUT_EXTS) {
                    val key = ObjectKey.renderOutput(jobId, "$jobId.$ext")
                    if (objectStore.deleteObject(key)) r2Deleted++
                }
            }
        }

        val stats = EraseStats(sepJobs.size, renderJobs.size, localDeleted, r2Deleted)
        log.info(
            "Account content erased: user={} separationJobs={} renderJobs={} localDeleted={} r2Deleted={}",
            userId, stats.separationJobs, stats.renderJobs, stats.localDeleted, stats.r2Deleted,
        )
        stats
    }

    companion object {
        /** 렌더 산출물 확장자 후보 — video=mp4, audio-only=m4a. RenderService.submitRender 와 동기. */
        private val RENDER_OUTPUT_EXTS = listOf("mp4", "m4a")
    }
}
