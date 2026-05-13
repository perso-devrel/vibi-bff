package com.vibi.bff.service

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 based signer for short-lived stem / mix download URLs.
 *
 * Token format: "{expiresAtEpochSec}.{urlSafeBase64Signature}"
 * The signed payload binds {jobId, resourceId, expiry} so a token cannot be
 * replayed against a different stem or a different job.
 */
class SignedUrlService(
    private val secret: String,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    init {
        require(secret.length >= 32) { "Signing secret must be at least 32 chars" }
    }

    private val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun sign(jobId: String, resourceId: String, ttlSec: Long): String {
        require(ttlSec in 1..604_800) { "ttlSec out of range: $ttlSec" }
        val expiresAt = clock() + ttlSec
        val signature = computeSignature(jobId, resourceId, expiresAt)
        return "$expiresAt.$signature"
    }

    fun verify(jobId: String, resourceId: String, token: String): Boolean {
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return false
        val expStr = token.substring(0, dot)
        val sig = token.substring(dot + 1)
        val exp = expStr.toLongOrNull() ?: return false
        if (clock() > exp) return false
        val expected = computeSignature(jobId, resourceId, exp)
        // Constant-time comparison — guard against timing side-channels that
        // could otherwise leak the signature byte-by-byte.
        return MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())
    }

    private fun computeSignature(jobId: String, resourceId: String, expiresAt: Long): String {
        val payload = "$jobId:$resourceId:$expiresAt"
        val mac = Mac.getInstance("HmacSHA256").apply { init(keySpec) }
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
