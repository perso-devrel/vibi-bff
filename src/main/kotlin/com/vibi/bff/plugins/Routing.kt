package com.vibi.bff.plugins

import com.vibi.bff.config.AppConfig
import com.vibi.bff.routes.adminRoutes
import com.vibi.bff.routes.assetRoutes
import com.vibi.bff.routes.authRoutes
import com.vibi.bff.routes.creditRoutes
import com.vibi.bff.routes.renderRoutes
import com.vibi.bff.routes.separationRoutes
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.FileStorageService
import com.vibi.bff.service.ObjectStore
import com.vibi.bff.service.PersoClient
import com.vibi.bff.service.RenderInputCacheService
import com.vibi.bff.service.RenderService
import com.vibi.bff.service.SeparationQueueRepository
import com.vibi.bff.service.SeparationService
import com.vibi.bff.service.SignedUrlService
import com.vibi.bff.service.UserRepository
import com.vibi.bff.service.iap.AppleReceiptVerifier
import com.vibi.bff.service.iap.GoogleReceiptVerifier
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
    separationQueue: SeparationQueueRepository?,
    signedUrlService: SignedUrlService,
    renderInputCache: RenderInputCacheService,
    authService: AuthService,
    objectStore: ObjectStore?,
    adminRepository: AdminRepository,
    userRepository: UserRepository,
    creditRepository: CreditRepository,
    appleReceiptVerifier: AppleReceiptVerifier?,
    googleReceiptVerifier: GoogleReceiptVerifier?,
) {
    val log = org.slf4j.LoggerFactory.getLogger("BootCheck")
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/vibi-bff.yaml")

        // Admin SPA — Swagger 와 같은 패턴으로 BFF 가 직접 서빙. ADMIN_SLUG blank 면 마운트 자체 skip
        // (= 외부에선 admin 페이지 존재도 인지 불가). Vite 빌드 산출물이 src/main/resources/admin/ 에 있다.
        // SPA HashRouter 라 server 측 fallback 필요 없음 — /${slug}/ 로 들어오면 index.html 만 떨어뜨리면
        // # 뒤는 모두 client-side routing.
        val adminSlug = appConfig.admin.slug
        if (adminSlug.isNotBlank()) {
            val spaIndexExists = this::class.java.classLoader
                .getResource("${appConfig.admin.resourcePath}/index.html") != null
            if (spaIndexExists) {
                staticResources("/$adminSlug", appConfig.admin.resourcePath) {
                    default("index.html")
                }
                log.info("Admin SPA mounted at /{}/ (resource={})", adminSlug, appConfig.admin.resourcePath)
            } else {
                log.warn(
                    "ADMIN_SLUG='{}' set but classpath:/{}/index.html not found — " +
                        "did you run `npm run build` in admin-ui/?",
                    adminSlug, appConfig.admin.resourcePath,
                )
            }
        }

        route("/api/v2") {
            authRoutes(authService, userRepository, jwtSecret = appConfig.auth.jwtSecret)
            creditRoutes(
                creditRepository,
                appleVerifier = appleReceiptVerifier,
                googleVerifier = googleReceiptVerifier,
                jwtSecret = appConfig.auth.jwtSecret,
            )
            assetRoutes(objectStore, jwtSecret = appConfig.auth.jwtSecret)
            renderRoutes(
                renderService, fileStorage,
                separationService, signedUrlService, renderInputCache,
                objectStore,
                jwtSecret = appConfig.auth.jwtSecret,
            )
            separationRoutes(
                separationService, signedUrlService, fileStorage,
                appConfig, objectStore,
                queueRepository = separationQueue,
                jwtSecret = appConfig.auth.jwtSecret,
                creditRepository = creditRepository,
            )
            adminRoutes(adminRepository, jwtSecret = appConfig.auth.jwtSecret)

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
                        com.vibi.bff.model.TestdataSeparationFolder(
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
