package com.dubcast.bff.routes

import com.dubcast.bff.config.AppConfig
import com.dubcast.bff.model.TtsRequest
import com.dubcast.bff.model.TtsResponse
import com.dubcast.bff.service.AudioUtils
import com.dubcast.bff.service.ElevenLabsClient
import com.dubcast.bff.service.FileStorageService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.ttsV2Routes(
    elevenLabsClient: ElevenLabsClient,
    fileStorage: FileStorageService,
    appConfig: AppConfig,
) {
    post("/tts") {
        val request = call.receive<TtsRequest>()

        val requestId = UUID.randomUUID().toString()
        val (targetFile, blobPath) = fileStorage.reserveTtsPath(requestId)

        elevenLabsClient.textToSpeech(
            voiceId = request.voiceId,
            text = request.text,
            targetFile = targetFile,
            modelId = request.modelId,
            stability = request.stability,
            similarityBoost = request.similarityBoost,
            languageCode = request.languageCode,
        )

        val audioUrl = fileStorage.resolveDownloadUrl(appConfig.baseUrl, blobPath)
        val durationMs = AudioUtils.estimateMp3DurationMs(targetFile)
        call.respond(HttpStatusCode.OK, TtsResponse(audioUrl = audioUrl, durationMs = durationMs))
    }
}
