package com.vibi.bff.routes

import com.vibi.bff.model.LanguageListResponse
import com.vibi.bff.model.LanguageOption
import com.vibi.bff.service.PersoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 지원 타깃 언어 목록 — Perso 의 `GET /video-translator/api/v1/languages` 프록시.
 *
 * 모바일 클라이언트의 언어 선택 UI 가 정적 목록 대신 BFF 응답을 사용하므로 Perso 의
 * 신규 언어 지원이 자동 반영된다.
 */
fun Route.languageRoutes(persoClient: PersoClient) {
    get("/languages") {
        val perso = persoClient.getLanguages()
        val languages = perso.languages.map { p ->
            LanguageOption(
                code = p.code,
                name = p.name,
                nativeName = p.nativeName,
                supportsDubbing = p.supportsDubbing,
                supportsSubtitles = p.supportsSubtitles,
            )
        }
        call.respond(HttpStatusCode.OK, LanguageListResponse(languages = languages))
    }
}
