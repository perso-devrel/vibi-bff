package com.dubcast.bff.routes

import com.dubcast.bff.model.Voice
import com.dubcast.bff.model.VoiceListResponse
import com.dubcast.bff.service.ElevenLabsClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.voiceRoutes(elevenLabsClient: ElevenLabsClient) {
    get("/voices") {
        val elVoices = elevenLabsClient.getVoices()

        val voices = elVoices.voices.map { v ->
            Voice(
                voiceId = v.voiceId,
                name = v.name,
                previewUrl = v.previewUrl,
                language = v.labels["language"] ?: v.labels["accent"],
                category = v.category,
                labels = v.labels,
            )
        }

        call.respond(HttpStatusCode.OK, VoiceListResponse(voices = voices))
    }
}
