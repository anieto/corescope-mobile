package org.nodescope.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nodescope.android.core.model.FieldEntityId
import org.nodescope.android.core.model.FieldQrCode
import org.nodescope.android.core.model.FieldRequestBudget
import org.nodescope.android.core.model.FieldSourceId
import org.nodescope.android.core.model.MeshCoreContactLink

class FieldContractsTest {
    private val key = "9CD8FCF22A47333B591D96A2B848B73F457B1BB1A3EA2453A885F9E5787765B1"

    @Test fun identityIsSourceQualifiedAndNormalizesNodeKeys() {
        val coreScope = requireNotNull(FieldSourceId.create("CoreScope", "https://one.example/base"))
        val beacon = requireNotNull(FieldSourceId.create("beacon", "https://one.example/base"))
        val first = requireNotNull(FieldEntityId.create(coreScope, FieldEntityId.Kind.NODE, key))
        val same = requireNotNull(FieldEntityId.create(coreScope, FieldEntityId.Kind.NODE, key.lowercase()))
        val otherSource = requireNotNull(FieldEntityId.create(beacon, FieldEntityId.Kind.NODE, key))
        assertEquals(first, same)
        assertEquals(first.id, same.id)
        assertNotEquals(first, otherSource)
        assertNotEquals(first.id, otherSource.id)
    }

    @Test fun identityEncodingHasNoDelimiterCollision() {
        val first = requireNotNull(FieldEntityId.create(requireNotNull(FieldSourceId.create("a", "bc")), FieldEntityId.Kind.OBSERVER, "d"))
        val second = requireNotNull(FieldEntityId.create(requireNotNull(FieldSourceId.create("ab", "c")), FieldEntityId.Kind.OBSERVER, "d"))
        assertNotEquals(first.id, second.id)
    }

    @Test fun parsesTheThreeApprovedNodeCodeForms() {
        val normalized = key.lowercase()
        assertEquals(FieldQrCode.PublicKey(normalized), FieldQrCode.parse(key))
        assertEquals(FieldQrCode.NodeScope(FieldEntityId.Kind.NODE, normalized), FieldQrCode.parse("nodescope://node/$key"))
        val contact = requireNotNull(MeshCoreContactLink.from("Hill Top + West", key, 2))
        assertEquals(FieldQrCode.MeshCoreContact(contact), FieldQrCode.parse(contact.url))
    }

    @Test fun parsesNodeScopeObserverWithoutTreatingItAsANodeKey() {
        assertEquals(FieldQrCode.NodeScope(FieldEntityId.Kind.OBSERVER, "SAT-01"), FieldQrCode.parse("nodescope://observer/SAT-01"))
    }

    @Test fun rejectsUnsupportedOrAmbiguousPayloads() {
        val invalid = listOf(
            "", "abcd", "z".repeat(64), "https://example.com/node/$key",
            "nodescope://node/abcd", "nodescope://channel/test", "nodescope://node/$key?source=evil",
            "meshcore://contact/add?name=R&public_key=$key&type=2&type=3",
            "meshcore://contact/add?name=R&public_key=$key&type=5",
            "meshcore://contact/delete?name=R&public_key=$key&type=2",
            "a".repeat(FieldQrCode.MAXIMUM_PAYLOAD_BYTES + 1),
        )
        invalid.forEach { assertNull(FieldQrCode.parse(it)) }
    }

    @Test fun meshCoreNamesAreBoundedByUtf8Bytes() {
        assertNull(MeshCoreContactLink.from("é".repeat(65), key, 2))
    }

    @Test fun requestBudgetsMatchTheSharedPlatformContract() {
        assertEquals(30_000L, FieldRequestBudget.AUTOMATIC_CATALOG_REFRESH_INTERVAL_MILLIS)
        assertEquals(604_800_000L, FieldRequestBudget.STALE_CATALOG_LIFETIME_MILLIS)
        assertEquals(300_000L, FieldRequestBudget.PINNED_EVIDENCE_FRESH_LIFETIME_MILLIS)
        assertEquals(20, FieldRequestBudget.MAXIMUM_BEACON_CATALOG_PAGES_PER_KIND)
        assertEquals(200, FieldRequestBudget.MAXIMUM_LIVE_PACKETS)
        assertEquals(1_000, FieldRequestBudget.MAXIMUM_LIVE_METADATA_ENTRIES)
    }
}
