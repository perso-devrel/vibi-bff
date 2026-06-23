package com.vibi.bff.plugins

import com.vibi.bff.config.AppConfig
import com.vibi.bff.routes.adminRoutes
import com.vibi.bff.routes.assetRoutes
import com.vibi.bff.routes.authRoutes
import com.vibi.bff.routes.creditRoutes
import com.vibi.bff.routes.deviceAuthRoutes
import com.vibi.bff.routes.devicePageHtml
import com.vibi.bff.routes.peaksRoutes
import com.vibi.bff.routes.renderRoutes
import com.vibi.bff.routes.separationRoutes
import com.vibi.bff.service.AccountContentEraser
import com.vibi.bff.service.AdminRepository
import com.vibi.bff.service.AuthService
import com.vibi.bff.service.CreditRepository
import com.vibi.bff.service.DeviceCodeRepository
import com.vibi.bff.service.GoogleOAuthClient
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
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
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
    deviceCodeRepository: DeviceCodeRepository,
    googleOAuthClient: GoogleOAuthClient?,
) {
    val log = org.slf4j.LoggerFactory.getLogger("com.vibi.bff.plugins.BootCheck")
    // 회원탈퇴 시 사용자 콘텐츠(분리 스템·렌더 산출물)를 로컬 + R2 에서 제거 (GDPR/CCPA).
    val accountContentEraser = AccountContentEraser(
        separationDir = fileStorage.separationDir,
        renderDir = fileStorage.renderDir,
        objectStore = objectStore,
    )
    routing {
        // 비인증 liveness/readiness probe — Cloud Run / 외부 uptime 체크용. /api/v2 밖 +
        // 레이트리밋 밖에 두어 콜드스타트 트래픽 라우팅 판단에 쓰이게 한다.
        get("/healthz") {
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
        }
        // device-flow 로그인 페이지 — 비-/api/v2, 브라우저가 직접 연다. `code` 쿼리로 패널이 보여준
        // user-code 를 받아 명시 동의(RFC 8628 피싱 완화) 후 /api/v2/auth/google/start 로 제출.
        get("/device") {
            val code = call.request.queryParameters["code"].orEmpty()
            call.respondText(devicePageHtml(code), ContentType.Text.Html)
        }
        // Swagger UI — 전체 API 스펙을 무인증 노출하므로 운영에선 끈다. ENABLE_SWAGGER=true
        // (로컬 dev) 일 때만 마운트해 정찰 표면을 줄인다.
        if (System.getenv("ENABLE_SWAGGER") == "true") {
            swaggerUI(path = "swagger", swaggerFile = "openapi/vibi-bff.yaml")
            log.info("Swagger UI mounted at /swagger (ENABLE_SWAGGER=true)")
        }

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
            // /auth/* 는 무인증 게이트웨이 — IP 키 레이트리밋으로 가입 보너스 크레딧 양산 차단.
            rateLimit(RL_AUTH) {
                authRoutes(authService, userRepository, accountContentEraser, jwtSecret = appConfig.auth.jwtSecret)
            }
            // UXP 패널 device-code 로그인. 자체적으로 RL_DEVICE(start/poll)·RL_AUTH(google)을
            // 적용하므로 바깥 rateLimit 으로 감싸지 않는다.
            deviceAuthRoutes(
                deviceCodeRepository,
                authService,
                googleOAuthClient,
                baseUrl = appConfig.baseUrl,
                jwtSecret = appConfig.auth.jwtSecret,
            )
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
                userRepository = userRepository,
            )
            separationRoutes(
                separationService, signedUrlService, fileStorage,
                appConfig, objectStore,
                queueRepository = separationQueue,
                jwtSecret = appConfig.auth.jwtSecret,
                creditRepository = creditRepository,
                userRepository = userRepository,
                persoClient = persoClient,
            )
            // 입력 파형 미리보기(UXP 가 mp3/AAC 디코드 불가). 무차감 — 자체 in-flight cap 으로 보호.
            peaksRoutes(fileStorage, jwtSecret = appConfig.auth.jwtSecret)
            adminRoutes(adminRepository, jwtSecret = appConfig.auth.jwtSecret)

            // 임시 — 음성분리 mock. testdata/<startSec>-<endSec>/ 디렉터리 구조.
            // 각 폴더 안에 stem 오디오 파일들 (배경음/화자1/... 한글 파일명, .wav/.mp3/.m4a 등).
            // 실제 음성분리 결과는 .wav 로 떨어져 — 확장자 화이트리스트는 일반 오디오 포맷 다수 허용.
            // 모바일은 list endpoint 로 폴더 목록 + stem 이름(확장자 제외) 받아 directive 등록.
            //
            // dev mock — 운영 surface 에 노출하지 않도록 ENABLE_TESTDATA_MOCK=true 일 때만 마운트.
            // 운영 컨테이너엔 testdata/ 디렉터리도 없어 이전에도 사실상 404 였으나, dead/dev 코드를
            // 운영 라우트 트리에서 아예 제거해 공격 표면을 줄인다.
            if (System.getenv("ENABLE_TESTDATA_MOCK") == "true") {
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
                // path traversal 방어 — stem 은 basename 만 허용 (경로 구분자 / `..` reject).
                // folder 와 달리 정규식 검증이 없던 자리라 defense-in-depth 로 추가.
                if (stem.contains('/') || stem.contains('\\') || stem.contains("..")) {
                    throw NotFoundException("invalid stem: $stem")
                }
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
            } // ENABLE_TESTDATA_MOCK
        }
    }
}
