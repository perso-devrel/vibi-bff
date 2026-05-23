package com.vibi.bff.service

import java.io.File
import java.util.UUID

/**
 * Resolve the source media for the separation submit route. The mobile
 * client may either upload a fresh file *or* reference an already-rendered
 * output by [editedRenderJobId]. This lets the user edit a video, render
 * it once on the server, then run separation on that exact output without
 * round-tripping the bytes back through the mobile client.
 *
 * Resolution rules:
 * - [editedRenderJobId] != null → look up the render output and **copy**
 *   it into [editedSourceDir]. The copy is owned by the caller and may be
 *   freely deleted/renamed by separation (which deletes the source after
 *   Perso upload). If the file part was also supplied, the render output
 *   **wins** and the uploaded file is deleted to avoid stranding bytes on
 *   disk.
 * - otherwise → use [filePart] (the upload itself is already caller-owned
 *   single-use bytes — no copy needed).
 * - neither → [IllegalArgumentException].
 *
 * The copy + lastAccessedAt bump happen atomically inside
 * [RenderService.acquireRenderOutputCopy] so the cleanup sweep can't reap
 * the render output between the exists-check and the byte read.
 */
class MediaSourceResolver(
    private val renderService: RenderService,
    /** Directory under which copies of edited render outputs live until
     * the downstream pipeline disposes of them. Survives at the same
     * scope as render outputs (FileStorage.editedSourceDir). */
    private val editedSourceDir: File,
) {
    fun resolve(filePart: File?, editedRenderJobId: String?, callerUserId: UUID? = null): File {
        if (editedRenderJobId != null) {
            // editedRenderJobId 가 우선 — file 파트가 같이 올라온 경우 dead bytes 가
            // 디스크에 남지 않도록 정리. resolve() 호출 후엔 caller 가 file 파트의
            // 존재를 잊어도 된다.
            filePart?.delete()
            // acquireRenderOutputCopy 가 ownerUserId mismatch 일 때 null 반환 — 동일
            // not-found 메시지로 흘려 보내서 IDOR 시 owner 존재 여부 oracle 노출 차단.
            return renderService.acquireRenderOutputCopy(editedRenderJobId, editedSourceDir, callerUserId)
                ?: throw IllegalArgumentException(
                    "editedRenderJobId not found or not ready: $editedRenderJobId"
                )
        }
        return filePart
            ?: throw IllegalArgumentException(
                "either file part or editedRenderJobId is required"
            )
    }
}
