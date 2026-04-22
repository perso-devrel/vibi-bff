package com.dubcast.bff

import com.dubcast.bff.service.SignedUrlService
import kotlin.test.*

class SignedUrlServiceTest {

    private val secret = "a".repeat(64)

    @Test
    fun `sign and verify round-trips for valid token`() {
        val now = 1_000_000L
        val s = SignedUrlService(secret, clock = { now })
        val token = s.sign("job-1", "background", ttlSec = 600)
        assertTrue(s.verify("job-1", "background", token))
    }

    @Test
    fun `verify rejects expired token`() {
        var clock = 1_000_000L
        val s = SignedUrlService(secret, clock = { clock })
        val token = s.sign("job-1", "background", ttlSec = 60)
        clock += 61
        assertFalse(s.verify("job-1", "background", token))
    }

    @Test
    fun `verify rejects token bound to different jobId`() {
        val s = SignedUrlService(secret, clock = { 1_000_000L })
        val token = s.sign("job-1", "background", 600)
        assertFalse(s.verify("job-2", "background", token))
    }

    @Test
    fun `verify rejects token bound to different stemId`() {
        val s = SignedUrlService(secret, clock = { 1_000_000L })
        val token = s.sign("job-1", "background", 600)
        assertFalse(s.verify("job-1", "speaker_0", token))
    }

    @Test
    fun `verify rejects malformed tokens`() {
        val s = SignedUrlService(secret, clock = { 1L })
        assertFalse(s.verify("j", "r", ""))
        assertFalse(s.verify("j", "r", "no-dot"))
        assertFalse(s.verify("j", "r", "notanumber.sig"))
        assertFalse(s.verify("j", "r", "."))
        assertFalse(s.verify("j", "r", "123."))
    }

    @Test
    fun `constructor rejects short secret`() {
        assertFailsWith<IllegalArgumentException> { SignedUrlService("short") }
    }
}
