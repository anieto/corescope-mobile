package org.nodescope.android

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nodescope.android.core.model.MeshCoreContactLink

class MeshCoreContactLinkTest {
    private val key = "9CD8FCF22A47333B591D96A2B848B73F457B1BB1A3EA2453A885F9E5787765B1"

    @Test fun buildsTheMeshCoreAppContactLink() {
        assertEquals("meshcore://contact/add?name=Example%20Contact&public_key=${key.lowercase()}&type=1",
            MeshCoreContactLink.from("Example Contact", key, "companion")?.url)
    }

    @Test fun mapsRolesToContactTypes() {
        assertEquals(2, MeshCoreContactLink.from("R", key, "repeater")?.type)
        assertEquals(3, MeshCoreContactLink.from("R", key, "Room")?.type)
        assertEquals(4, MeshCoreContactLink.from("S", key, "sensor")?.type)
        assertNull(MeshCoreContactLink.from("?", key, "unknown"))
    }

    @Test fun escapesReservedCharactersInNames() {
        val url = MeshCoreContactLink.from("A+B & C=D #1", key, "repeater")!!.url
        assertTrue(url.startsWith("meshcore://contact/add?name=A%2BB%20%26%20C%3DD%20%231&"))
        val name = URI(url).rawQuery.split('&').first().removePrefix("name=")
        assertEquals("A+B & C=D #1", URLDecoder.decode(name, "UTF-8"))
    }

    @Test fun fallsBackToAKeyPrefixForUnnamedNodes() {
        assertEquals("9CD8FCF2", MeshCoreContactLink.from("  ", key, "repeater")?.name)
    }

    @Test fun rejectsIncompleteKeys() {
        assertNull(MeshCoreContactLink.from("R", "9cd8fcf2", "repeater"))
        assertNull(MeshCoreContactLink.from("R", "z".repeat(64), "repeater"))
    }
}
