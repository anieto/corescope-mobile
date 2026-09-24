package org.nodescope.android.core.network

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore group-channel crypto, byte-compatible with the iOS `ChannelCrypto`:
 * hashtag keys are the first 16 bytes of SHA-256(name), the one-byte channel hash
 * is SHA-256(key)[0], and GRP_TXT payloads carry a 2-byte truncated HMAC-SHA256
 * (key zero-padded to 32 bytes) over AES-128-ECB ciphertext.
 */
object ChannelCrypto {
    /** MeshCore's well-known Public channel PSK; unlike hashtags, it is not derived from the name. */
    const val PUBLIC_KEY_HEX = "8b3387e9c5cdea6ac9e5edbaa115cd72"

    data class Decrypted(val sender: String, val text: String, val senderTimestamp: Long)

    fun isValidKey(keyHex: String): Boolean = keyHex.length == 32 && keyHex.all { it.isHexDigitChar() }

    /** `#name` for hashtags; any spelling of "public" becomes the Public channel. */
    fun normalizedHashtag(value: String): String? {
        val trimmed = value.trim()
        val bare = trimmed.removePrefix("#")
        if (bare.isEmpty()) return null
        if (bare.equals("Public", ignoreCase = true)) return "Public"
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    fun keyHexFor(channelName: String): String =
        if (channelName == "Public") PUBLIC_KEY_HEX
        else sha256(channelName.toByteArray()).copyOf(16).toHex()

    fun channelHash(keyHex: String): Int? = hexBytes(keyHex)?.let { sha256(it)[0].toInt() and 0xFF }

    fun generateKeyHex(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).toHex()

    // ECB is mandated by the MeshCore wire format; this only decrypts received traffic.
    @SuppressLint("GetInstance")
    fun decrypt(keyHex: String, macHex: String, encryptedHex: String): Decrypted? {
        val key = hexBytes(keyHex)?.takeIf { it.size == 16 } ?: return null
        val ciphertext = hexBytes(encryptedHex)?.takeIf { it.isNotEmpty() && it.size % 16 == 0 } ?: return null
        val suppliedMac = hexBytes(macHex)?.takeIf { it.size == 2 } ?: return null
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key + ByteArray(16), "HmacSHA256"))
            doFinal(ciphertext)
        }.copyOf(2)
        if (!MessageDigest.isEqual(expected, suppliedMac)) return null
        val plaintext = runCatching {
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                doFinal(ciphertext)
            }
        }.getOrNull() ?: return null
        return parsePlaintext(plaintext)
    }

    /** Little-endian u32 timestamp, one flags byte, then NUL-terminated UTF-8 "sender: text". */
    private fun parsePlaintext(bytes: ByteArray): Decrypted? {
        if (bytes.size < 5) return null
        val timestamp = (0..3).fold(0L) { value, index -> value or ((bytes[index].toLong() and 0xFF) shl (8 * index)) }
        val body = bytes.copyOfRange(5, bytes.size).let { tail -> tail.copyOf(tail.indexOf(0).takeIf { it >= 0 } ?: tail.size) }
        val message = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        }.getOrNull() ?: return null
        val separator = message.indexOf(':')
        return if (separator >= 0 && ": " in message) {
            Decrypted(message.substring(0, separator), message.substring(separator + 1).trim { it == ' ' || it == '\t' }, timestamp)
        } else Decrypted("Unknown", message, timestamp)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun hexBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !hex.all { it.isHexDigitChar() }) return null
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
