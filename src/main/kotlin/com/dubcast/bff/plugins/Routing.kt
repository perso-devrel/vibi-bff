package com.dubcast.bff.plugins

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.routes.renderRoutes
import com.dubcast.bff.routes.separationRoutes
import com.dubcast.bff.routes.ttsRoutes
import com.dubcast.bff.routes.ttsV2Routes
import com.dubcast.bff.routes.uploadRoutes
import com.dubcast.bff.routes.voiceRoutes
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import com.dubcast.bff.service.RenderService
import com.dubcast.bff.service.SeparationService
import com.dubcast.bff.service.SignedUrlService
import com.dubcast.bff.service.StemMixService
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting(
    fileStorage: FileStorageService,
    elevenLabsClient: ElevenLabsClient,
    appConfig: AppConfig,
    renderService: RenderService,
    separationService: SeparationService,
    stemMixService: StemMixService,
    signedUrlService: SignedUrlService,
) {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/dubcast-bff.yaml")

        staticFiles("/files/tts", File(appConfig.storage.basePath + "/tts"))

        route("/api/v1") {
            uploadRoutes(fileStorage)
            voiceRoutes(elevenLabsClient)
            ttsRoutes(elevenLabsClient, fileStorage, appConfig)
        }

        route("/api/v2") {
            voiceRoutes(elevenLabsClient)
            ttsV2Routes(elevenLabsClient, fileStorage, appConfig)
            renderRoutes(renderService, fileStorage)
            separationRoutes(separationService, stemMixService, signedUrlService, fileStorage, appConfig)
        }
    }
}
