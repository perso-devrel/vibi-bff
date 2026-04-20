package com.dubcast.bff.service

import com.dubcast.bff.config.StorageConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.UUID

class FileStorageService(private val config: StorageConfig) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val baseDir get() = File(config.basePath)
    private val uploadsDir get() = File(baseDir, "uploads")
    private val ttsDir get() = File(baseDir, "tts")
    private val lipsyncDir get() = File(baseDir, "lipsync")
    val renderDir get() = File(baseDir, "render")

    fun init() {
        uploadsDir.mkdirs()
        ttsDir.mkdirs()
        lipsyncDir.mkdirs()
        renderDir.mkdirs()
        log.info("Storage initialized at {}", baseDir.absolutePath)
    }

    fun saveUpload(fileName: String, inputStream: InputStream, maxSize: Long = Long.MAX_VALUE): String {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val blobName = "${UUID.randomUUID()}_$safeName"
        val target = File(uploadsDir, blobName)

        inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if (totalBytes > maxSize) {
                        output.close()
                        target.delete()
                        throw IllegalArgumentException("File exceeds maximum size of ${maxSize / 1024 / 1024}MB")
                    }
                    output.write(buffer, 0, bytesRead)
                }
            }
        }

        val blobPath = "uploads/$blobName"
        log.info("Saved upload: {} ({} bytes)", blobPath, target.length())
        return blobPath
    }

    fun getUploadFile(blobPath: String): File {
        val file = File(baseDir, blobPath)
        require(file.canonicalPath.startsWith(baseDir.canonicalPath)) {
            "Invalid file path: $blobPath"
        }
        require(file.exists()) { "Upload file not found: $blobPath" }
        return file
    }

    fun reserveTtsPath(requestId: String): Pair<File, String> {
        val target = File(ttsDir, "$requestId.mp3")
        val blobPath = "tts/$requestId.mp3"
        return target to blobPath
    }

    fun getLipSyncResultFile(lipSyncId: String): Pair<File, String> {
        val target = File(lipsyncDir, "$lipSyncId.mp4")
        require(target.canonicalPath.startsWith(lipsyncDir.canonicalPath)) {
            "Invalid lip-sync ID: $lipSyncId"
        }
        return target to "lipsync/$lipSyncId.mp4"
    }

    fun resolveDownloadUrl(baseUrl: String, filePath: String): String {
        return "$baseUrl/files/$filePath"
    }
}
