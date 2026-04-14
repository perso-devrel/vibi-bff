package com.dubcast.bff.config

import io.ktor.server.config.*

data class AppConfig(
    val elevenLabs: ElevenLabsConfig,
    val storage: StorageConfig,
    val baseUrl: String,
)

data class ElevenLabsConfig(
    val apiKey: String,
    val baseUrl: String,
)

data class StorageConfig(
    val basePath: String,
)

fun loadConfig(config: ApplicationConfig): AppConfig {
    val dubcast = config.config("dubcast")
    val el = dubcast.config("elevenlabs")
    val storage = dubcast.config("storage")

    return AppConfig(
        elevenLabs = ElevenLabsConfig(
            apiKey = el.property("apiKey").getString(),
            baseUrl = el.property("baseUrl").getString(),
        ),
        storage = StorageConfig(
            basePath = storage.property("basePath").getString(),
        ),
        baseUrl = dubcast.property("baseUrl").getString(),
    )
}
