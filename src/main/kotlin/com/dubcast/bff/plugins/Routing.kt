package com.dubcast.bff.plugins

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.routes.autoDubRoutes
import com.dubcast.bff.routes.languageRoutes
import com.dubcast.bff.routes.renderRoutes
import com.dubcast.bff.routes.separationRoutes
import com.dubcast.bff.routes.subtitleRoutes
import com.dubcast.bff.routes.ttsV2Routes
import com.dubcast.bff.routes.voiceRoutes
import com.dubcast.bff.service.AutoDubService
import com.dubcast.bff.service.AutoSubtitleService
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
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
    elevenLabsClient: ElevenLabsClient,
    persoClient: PersoClient,
    appConfig: AppConfig,
    renderService: RenderService,
    separationService: SeparationService,
    stemMixService: StemMixService,
    signedUrlService: SignedUrlService,
    autoSubtitleService: AutoSubtitleService,
    autoDubService: AutoDubService,
    httpClient: HttpClient,
    renderInputCache: RenderInputCacheService,
) {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/dubcast-bff.yaml")

        staticFiles("/files/tts", File(appConfig.storage.basePath + "/tts"))

        route("/api/v2") {
            voiceRoutes(elevenLabsClient)
            languageRoutes(persoClient)
            ttsV2Routes(elevenLabsClient, fileStorage, appConfig)
            renderRoutes(
                renderService, fileStorage, stemMixService,
                separationService, signedUrlService, httpClient, renderInputCache,
            )
            separationRoutes(separationService, stemMixService, signedUrlService, fileStorage, appConfig)
            subtitleRoutes(autoSubtitleService, signedUrlService, fileStorage, appConfig)
            autoDubRoutes(autoDubService, signedUrlService, fileStorage, appConfig)

            // 임시 — 음성분리 mock. testdata/<startSec>-<endSec>/ 디렉터리 구조.
            // 각 폴더 안에 stem mp3 들 (background.mp3 / speaker1.mp3 / ... / 또는 한글 파일명).
            // 모바일은 list endpoint 로 폴더 목록 + stem 이름 받아 directive 들로 등록.
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
                            f.isFile && f.extension.equals("mp3", ignoreCase = true)
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

            // 폴더별 stem mp3 — 모바일이 SeparationDirective.audioUrl 로 직접 가리킴.
            get("/testdata/separation/{folder}/{stem}") {
                val folder = call.parameters["folder"] ?: throw NotFoundException("folder required")
                val stem = call.parameters["stem"] ?: throw NotFoundException("stem required")
                if (!folder.matches(Regex("^\\d+-\\d+$"))) throw NotFoundException("invalid folder: $folder")
                val candidates = listOf(
                    File("testdata/$folder/$stem.mp3"),
                    File("../testdata/$folder/$stem.mp3"),
                    File(System.getProperty("user.dir"), "testdata/$folder/$stem.mp3"),
                    File(System.getProperty("user.dir"), "../testdata/$folder/$stem.mp3"),
                )
                val file = candidates.firstOrNull { it.exists() }
                    ?: throw NotFoundException("testdata stem missing: $folder/$stem.mp3")
                call.response.header(HttpHeaders.ContentType, ContentType("audio", "mpeg").toString())
                call.respondFile(file)
            }
        }
    }
}
