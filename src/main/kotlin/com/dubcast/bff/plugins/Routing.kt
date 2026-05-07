package com.dubcast.bff.plugins

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.routes.authRoutes
import com.dubcast.bff.routes.autoDubRoutes
import com.dubcast.bff.routes.chatRoutes
import com.dubcast.bff.routes.languageRoutes
import com.dubcast.bff.routes.renderRoutes
import com.dubcast.bff.routes.separationRoutes
import com.dubcast.bff.routes.subtitleRoutes
import com.dubcast.bff.service.AuthService
import com.dubcast.bff.service.GeminiClient
import com.dubcast.bff.service.AutoDubService
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.MediaSourceResolver
import com.dubcast.bff.service.PersoClient
import com.dubcast.bff.service.RenderInputCacheService
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import com.dubcast.bff.plugins.NotFoundException
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting(
    fileStorage: FileStorageService,
    persoClient: PersoClient,
    appConfig: AppConfig,
    renderService: RenderService,
    separationService: SeparationService,
    stemMixService: StemMixService,
    signedUrlService: SignedUrlService,
    autoSubtitleService: AutoSubtitleService,
    autoDubService: AutoDubService,
    geminiClient: GeminiClient,
    httpClient: HttpClient,
    renderInputCache: RenderInputCacheService,
    mediaSourceResolver: MediaSourceResolver,
    authService: AuthService,
) {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/dubcast-bff.yaml")

        route("/api/v2") {
            authRoutes(authService)
            languageRoutes(persoClient)
            renderRoutes(
                renderService, fileStorage, stemMixService,
                separationService, signedUrlService, httpClient, renderInputCache,
            )
            separationRoutes(separationService, stemMixService, signedUrlService, fileStorage, appConfig, mediaSourceResolver)
            subtitleRoutes(autoSubtitleService, signedUrlService, fileStorage, appConfig, mediaSourceResolver)
            autoDubRoutes(autoDubService, signedUrlService, fileStorage, appConfig, mediaSourceResolver)
            chatRoutes(geminiClient)

            // 임시 — 음성분리 mock. testdata/<startSec>-<endSec>/ 디렉터리 구조.
            // 각 폴더 안에 stem 오디오 파일들 (배경음/화자1/... 한글 파일명, .wav/.mp3/.m4a 등).
            // 실제 음성분리 결과는 .wav 로 떨어져 — 확장자 화이트리스트는 일반 오디오 포맷 다수 허용.
            // 모바일은 list endpoint 로 폴더 목록 + stem 이름(확장자 제외) 받아 directive 등록.
            val audioExtensions = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")
            get("/testdata/separation/list") {
                val testdataRoots = listOf(
                    File("testdata"),
                    File("../testdata"),
                    File(System.getProperty("user.dir"), "testdata"),
                    File(System.getProperty("user.dir"), "../testdata"),
                )
                val root = testdataRoots.firstOrNull { it.exists() && it.isDirectory }
                    ?: throw NotFoundException("testdata directory missing")
                val rangeRegex = Regex("^(\\d+)-(\\d+)$")
                val folders = (root.listFiles { f -> f.isDirectory } ?: emptyArray())
                    .mapNotNull { folder ->
                        val match = rangeRegex.matchEntire(folder.name) ?: return@mapNotNull null
                        val startSec = match.groupValues[1].toInt()
                        val endSec = match.groupValues[2].toInt()
                        val stems = (folder.listFiles { f ->
                            f.isFile && f.extension.lowercase() in audioExtensions
                        } ?: emptyArray()).map { it.nameWithoutExtension }
                        com.dubcast.bff.model.TestdataSeparationFolder(
                            folder = folder.name,
                            startSec = startSec,
                            endSec = endSec,
                            stems = stems,
                        )
                    }
                    .sortedBy { it.startSec }
                call.respond(folders)
            }

            // 폴더별 stem 오디오 — 모바일이 SeparationDirective.audioUrl 로 직접 가리킴.
            // {stem} 은 확장자 제외 basename — 폴더에서 audioExtensions 중 첫 번째 매치 파일 서브.
            get("/testdata/separation/{folder}/{stem}") {
                val folder = call.parameters["folder"] ?: throw NotFoundException("folder required")
                val stem = call.parameters["stem"] ?: throw NotFoundException("stem required")
                if (!folder.matches(Regex("^\\d+-\\d+$"))) throw NotFoundException("invalid folder: $folder")
                val roots = listOf(
                    File("testdata/$folder"),
                    File("../testdata/$folder"),
                    File(System.getProperty("user.dir"), "testdata/$folder"),
                    File(System.getProperty("user.dir"), "../testdata/$folder"),
                )
                val dir = roots.firstOrNull { it.exists() && it.isDirectory }
                    ?: throw NotFoundException("testdata folder missing: $folder")
                val file = audioExtensions.asSequence()
                    .map { ext -> File(dir, "$stem.$ext") }
                    .firstOrNull { it.exists() }
                    ?: throw NotFoundException("testdata stem missing: $folder/$stem.<audio>")
                val mime = when (file.extension.lowercase()) {
                    "wav" -> ContentType("audio", "wav")
                    "m4a", "aac" -> ContentType("audio", "aac")
                    "ogg" -> ContentType("audio", "ogg")
                    "flac" -> ContentType("audio", "flac")
                    else -> ContentType("audio", "mpeg")
                }
                call.response.header(HttpHeaders.ContentType, mime.toString())
                call.respondFile(file)
            }
        }
    }
}
