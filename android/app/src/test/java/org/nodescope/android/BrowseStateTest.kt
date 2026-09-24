package org.nodescope.android

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.network.KeyedLoader
import org.nodescope.android.core.storage.*

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseStateTest {
    @Test fun newKeyCancelsOldRequestAndNeverShowsItsData() = runTest {
        val old = CompletableDeferred<String>()
        var oldCancelled = false
        val loader = KeyedLoader(backgroundScope, 60_000, { currentTime }) { key: String ->
            if (key == "old") try { old.await() } catch (e: CancellationException) { oldCancelled = true; throw e } else "fresh $key"
        }
        loader.load("old")
        runCurrent()
        loader.load("new")
        old.complete("stale")
        runCurrent()
        assertTrue(oldCancelled)
        assertEquals("new", loader.state.value.key)
        assertEquals("fresh new", loader.state.value.value)
    }

    @Test fun failedRefreshKeepsSameKeyDataAndCacheAgeIsRespected() = runTest {
        var calls = 0
        var fail = false
        val loader = KeyedLoader(backgroundScope, 60_000, { currentTime }) { _: String ->
            calls++
            if (fail) throw IllegalStateException("offline") else "value $calls"
        }
        loader.load("a"); runCurrent()
        loader.load("a"); runCurrent()
        assertEquals(1, calls) // still fresh
        fail = true
        loader.load("a", force = true); runCurrent()
        assertEquals("value 1", loader.state.value.value)
        assertEquals("offline", loader.state.value.error)
        loader.load("b"); runCurrent()
        assertNull(loader.state.value.value) // another source's data is not carried over
    }

    private class Scrambler : SecretBox {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    @Test fun monitoredChannelsPersistOnlySealed() {
        var stored: String? = null
        val store = MonitoredChannelStore(Scrambler(), { stored }, { stored = it }, clock = { 1L })
        val key = "00112233445566778899aabbccddeeff"
        store.monitorKey(key.uppercase(), "  Team  ")
        store.monitorHashtag("wardriving")
        val reopened = MonitoredChannelStore(Scrambler(), { stored }, { stored = it })
        assertEquals(listOf("#wardriving", "psk:00112233"), reopened.channels.value.map { it.channelName })
        assertEquals("Team", reopened.channels.value.last().title)
        assertEquals(key, reopened.channels.value.last().keyHex)
        assertFalse(checkNotNull(stored).contains(key))
        assertFalse(String(Base64.getDecoder().decode(stored)).contains(key))
        reopened.remove("#wardriving")
        assertEquals(listOf("psk:00112233"), MonitoredChannelStore(Scrambler(), { stored }, { stored = it }).channels.value.map { it.channelName })
    }

    @Test fun invalidInputAndUnreadableStorageAreHandled() {
        val store = MonitoredChannelStore(Scrambler(), { null }, {})
        assertThrows(ChannelMonitorException::class.java) { store.monitorKey("abc", null) }
        assertThrows(ChannelMonitorException::class.java) { store.monitorHashtag(" # ") }
        assertEquals("Public", store.monitorHashtag("#public").channelName)
        assertEquals(store.channels.value.single(), store.matching("user:Public", "Public"))
        assertEquals(store.channels.value.single(), store.matching("Public", "Public"))

        val broken = object : SecretBox {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("key lost")
        }
        val recovered = MonitoredChannelStore(broken, { "AAAA" }, {})
        assertTrue(recovered.recoveryNeeded)
        assertTrue(recovered.channels.value.isEmpty())
    }
}
