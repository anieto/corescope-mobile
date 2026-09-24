package org.nodescope.android.core.storage

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.MonitoredChannel
import org.nodescope.android.core.network.ChannelCrypto

/** Authenticated encryption for data at rest. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a non-exportable Android Keystore key. The app also excludes its data from backup and transfer. */
class KeystoreSecretBox(private val alias: String = "nodescope-monitored-channels") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }
    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        }
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

class ChannelMonitorException(message: String) : Exception(message)

/**
 * Device-wide monitored channels, as on iOS. The record list is only ever persisted sealed;
 * if it cannot be opened (for example after the Keystore key is lost), the list starts empty
 * and [recoveryNeeded] asks the person to add their channels again.
 */
class MonitoredChannelStore(
    private val box: SecretBox,
    private val readSealed: () -> String?,
    private val writeSealed: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<List<MonitoredChannel>>(emptyList())
    val channels: StateFlow<List<MonitoredChannel>> = mutable.asStateFlow()
    var recoveryNeeded = false
        private set

    init {
        readSealed()?.let { stored ->
            runCatching {
                json.decodeFromString<List<MonitoredChannel>>(box.open(Base64.getDecoder().decode(stored)).decodeToString())
            }.onSuccess { mutable.value = it }.onFailure { recoveryNeeded = true }
        }
    }

    fun monitorHashtag(value: String): MonitoredChannel {
        val name = ChannelCrypto.normalizedHashtag(value) ?: throw ChannelMonitorException("Enter a channel name.")
        return add(MonitoredChannel(name, ChannelCrypto.keyHexFor(name), createdAt = clock()))
    }

    fun monitorKey(keyHex: String, displayName: String?): MonitoredChannel {
        val key = keyHex.trim().lowercase()
        if (!ChannelCrypto.isValidKey(key)) throw ChannelMonitorException("Enter a 32-character hexadecimal key.")
        return add(MonitoredChannel("psk:${key.take(8)}", key, displayName?.trim()?.takeIf(String::isNotEmpty), clock()))
    }

    fun generate(displayName: String?): MonitoredChannel = monitorKey(ChannelCrypto.generateKeyHex(), displayName)

    fun remove(channelName: String) = persist(mutable.value.filterNot { it.channelName == channelName })

    /** Monitored rows use `user:<channelName>`; a server channel matches by name. */
    fun matching(channelId: String, name: String): MonitoredChannel? {
        val channelName = channelId.removePrefix("user:").takeIf { channelId.startsWith("user:") } ?: name
        return mutable.value.firstOrNull { it.channelName == channelName }
    }

    private fun add(channel: MonitoredChannel): MonitoredChannel {
        persist((mutable.value.filterNot { it.channelName == channel.channelName } + channel).sortedBy { it.title.lowercase() })
        return channel
    }

    private fun persist(next: List<MonitoredChannel>) {
        val sealed = try {
            Base64.getEncoder().encodeToString(box.seal(json.encodeToString(next).toByteArray()))
        } catch (_: Exception) {
            throw ChannelMonitorException("Couldn't save the channel securely on this device.")
        }
        writeSealed(sealed)
        mutable.value = next
        recoveryNeeded = false
    }

    companion object {
        fun forDevice(context: Context): MonitoredChannelStore {
            val preferences = context.applicationContext.getSharedPreferences("monitored-channels", Context.MODE_PRIVATE)
            return MonitoredChannelStore(KeystoreSecretBox(), readSealed = { preferences.getString("sealed-v1", null) },
                writeSealed = { preferences.edit { putString("sealed-v1", it) } })
        }
    }
}
