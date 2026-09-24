package org.nodescope.android

import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.network.ChannelCrypto
import org.nodescope.android.core.network.protocolJson
import java.security.SecureRandom

internal fun fixture(name: String): String =
    checkNotNull(ChannelCryptoTest::class.java.classLoader?.getResourceAsStream(name)) { "Missing fixture $name" }
        .bufferedReader().use { it.readText() }

@Serializable
private data class CryptoVector(
    val channel: String, val keyHex: String, val channelHash: Int, val macHex: String,
    val encryptedHex: String, val senderTimestamp: Long, val sender: String, val text: String,
)
@Serializable private data class CryptoVectors(val vectors: List<CryptoVector>)

class ChannelCryptoTest {
    private val vectors = protocolJson.decodeFromString<CryptoVectors>(fixture("channel-crypto.json")).vectors

    @Test fun sharedKnownAnswerVectorsDecrypt() {
        assertEquals(3, vectors.size)
        vectors.forEach { vector ->
            assertEquals(vector.channel, vector.channelHash, ChannelCrypto.channelHash(vector.keyHex))
            val decrypted = checkNotNull(ChannelCrypto.decrypt(vector.keyHex, vector.macHex, vector.encryptedHex)) { vector.channel }
            assertEquals(vector.sender, decrypted.sender)
            assertEquals(vector.text, decrypted.text)
            assertEquals(vector.senderTimestamp, decrypted.senderTimestamp)
        }
    }

    @Test fun hashtagKeysAreDerivedAndPublicUsesTheWellKnownKey() {
        val hashtag = vectors.first { it.channel.startsWith("#") }
        assertEquals(hashtag.keyHex, ChannelCrypto.keyHexFor(hashtag.channel))
        assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", ChannelCrypto.keyHexFor("Public"))
        // Live traffic identifies Public by channel hash 17, not SHA-256("Public").
        assertEquals(17, ChannelCrypto.channelHash(ChannelCrypto.keyHexFor("Public")))
    }

    @Test fun rejectsTamperedOrMismatchedInput() {
        val vector = vectors.first()
        val flippedMac = vector.macHex.replaceRange(0, 1, if (vector.macHex[0] == '0') "1" else "0")
        assertNull(ChannelCrypto.decrypt(vector.keyHex, flippedMac, vector.encryptedHex))
        assertNull(ChannelCrypto.decrypt(vectors[1].keyHex, vector.macHex, vector.encryptedHex))
        assertNull(ChannelCrypto.decrypt(vector.keyHex, vector.macHex, vector.encryptedHex.dropLast(2)))
        assertNull(ChannelCrypto.decrypt(vector.keyHex, vector.macHex, ""))
        assertNull(ChannelCrypto.decrypt(vector.keyHex.drop(2), vector.macHex, vector.encryptedHex))
        assertNull(ChannelCrypto.decrypt(vector.keyHex, "zz" + vector.macHex.drop(2), vector.encryptedHex))
    }

    @Test fun normalizesHashtagsLikeIos() {
        assertEquals("#wardriving", ChannelCrypto.normalizedHashtag(" wardriving "))
        assertEquals("#dfw", ChannelCrypto.normalizedHashtag("#dfw"))
        assertEquals("Public", ChannelCrypto.normalizedHashtag("#PUBLIC"))
        assertEquals("Public", ChannelCrypto.normalizedHashtag("public"))
        assertNull(ChannelCrypto.normalizedHashtag("  "))
        assertNull(ChannelCrypto.normalizedHashtag("#"))
    }

    @Test fun validatesAndGeneratesKeys() {
        assertTrue(ChannelCrypto.isValidKey("00112233445566778899AABBCCDDEEFF"))
        assertFalse(ChannelCrypto.isValidKey("00112233445566778899aabbccddeef"))
        assertFalse(ChannelCrypto.isValidKey("00112233445566778899aabbccddeefg"))
        val generated = ChannelCrypto.generateKeyHex(SecureRandom())
        assertTrue(ChannelCrypto.isValidKey(generated))
        assertNotEquals(generated, ChannelCrypto.generateKeyHex(SecureRandom()))
    }
}
