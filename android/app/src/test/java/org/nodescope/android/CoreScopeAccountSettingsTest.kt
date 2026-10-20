package org.nodescope.android

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.protocolJson

class CoreScopeAccountSettingsTest {
    private fun load(name: String) = protocolJson.decodeFromString<CoreScopeAccountSettings>(fixture("my-nodes/$name.json"))

    @Test fun sharedDocumentPreservesRawStringsAndCategories() {
        val settings = load("account-settings")
        assertEquals(3L, settings.revision)
        assertEquals("synthetic-generation-a", settings.generation)
        assertTrue(settings.supportsNodeFavorites && settings.supportsOwnNodes)
        val document = requireNotNull(settings.doc)
        assertEquals("dark", document.keys["meshcore-theme"])
        assertTrue(requireNotNull(document.keys["meshcore-my-nodes"]).contains("futureField"))
        val roundTrip = protocolJson.decodeFromString<CoreScopeAccountSettings>(protocolJson.encodeToString(settings))
        assertEquals(document.keys, roundTrip.doc?.keys)
    }

    @Test fun emptyDocumentIsDistinctFromResetGeneration() {
        val empty = load("account-settings-empty")
        assertNull(empty.doc)
        assertTrue(empty.hasSupportedDocument && empty.supportsNodeFavorites && empty.supportsOwnNodes)
        assertFalse(empty.copy(generation = "old-generation").hasSupportedDocument)
    }

    @Test fun unknownSchemaOrIdentityDoesNotEnableSync() {
        val settings = load("account-settings")
        val future = settings.copy(doc = AccountSettingsDocument(2, emptyMap()))
        assertFalse(future.supportsNodeFavorites || future.supportsOwnNodes)
        val changed = settings.copy(allowlist = listOf(
            AccountAllowedKey("meshcore-my-nodes", "set", "name"),
            AccountAllowedKey("meshcore-favorites", "future-kind"),
        ))
        assertFalse(changed.supportsNodeFavorites || changed.supportsOwnNodes)
        val duplicate = settings.copy(allowlist = settings.allowlist + settings.allowlist)
        assertFalse(duplicate.supportsNodeFavorites || duplicate.supportsOwnNodes)
    }

    @Test fun nestedArrayInsteadOfRawStringIsRejected() {
        val raw = """{"revision":1,"generation":"a","doc":{"v":1,"keys":{"meshcore-favorites":[]}},"allowlist":[]}"""
        assertThrows(SerializationException::class.java) { protocolJson.decodeFromString<CoreScopeAccountSettings>(raw) }
    }
}
