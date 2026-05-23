package com.vibi.bff

import com.vibi.bff.config.StorageConfig
import com.vibi.bff.service.FileStorageService
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.*

class FileStorageServiceTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "vibi-test-storage-unit").apply { mkdirs() }
    private val config = StorageConfig(
        basePath = testDir.path,
        r2Bucket = "",
        r2 = null,
        signedUrlTtlSec = 900,
    )
    private lateinit var service: FileStorageService

    @BeforeTest
    fun setup() {
        testDir.deleteRecursively()
        service = FileStorageService(config)
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun `init creates required directories`() {
        assertTrue(File(testDir, "uploads").exists())
        assertTrue(File(testDir, "render").exists())
        assertTrue(File(testDir, "separation").exists())
        assertTrue(File(testDir, "separation/mix").exists())
        // Phase 1 follow-up: holds caller-owned copies of render outputs that
        // downstream pipelines (separation) consume and may mutate.
        assertTrue(File(testDir, "edited-source").exists())
    }

    @Test
    fun `saveUpload stores file and returns blob path`() {
        val content = "test video content".toByteArray()
        val blobPath = service.saveUpload("test.mp4", ByteArrayInputStream(content))

        assertTrue(blobPath.startsWith("uploads/"))
        assertTrue(blobPath.endsWith("_test.mp4"))

        val saved = service.getUploadFile(blobPath)
        assertEquals("test video content", saved.readText())
    }

    @Test
    fun `saveUpload sanitizes file name`() {
        val content = "data".toByteArray()
        val blobPath = service.saveUpload("my file (1).mp4", ByteArrayInputStream(content))

        assertTrue(blobPath.contains("my_file__1_.mp4"))
    }

    @Test
    fun `saveUpload rejects file exceeding max size`() {
        val content = ByteArray(1024) { 0x41 }
        val exception = assertFailsWith<IllegalArgumentException> {
            service.saveUpload("big.mp4", ByteArrayInputStream(content), maxSize = 512)
        }
        assertTrue(exception.message!!.contains("exceeds maximum size"))
    }

    @Test
    fun `getUploadFile rejects path traversal`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            service.getUploadFile("../../etc/passwd")
        }
        assertTrue(exception.message!!.contains("Invalid file path"))
    }

    @Test
    fun `getUploadFile rejects nonexistent file`() {
        assertFailsWith<IllegalArgumentException> {
            service.getUploadFile("uploads/nonexistent.mp4")
        }
    }

}
