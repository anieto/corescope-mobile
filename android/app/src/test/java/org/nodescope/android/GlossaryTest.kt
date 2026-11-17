package org.nodescope.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nodescope.android.feature.learn.Glossary
import org.nodescope.android.feature.learn.GlossaryTerm
import java.io.File

class GlossaryTest {
    private val android = File("src/main/assets/glossary.json")

    @Test fun matchesTheIosCopy() {
        assertArrayEquals(File("../../CoreScopeViewer/Resources/glossary.json").readBytes(), android.readBytes())
    }

    @Test fun contentOverlaysMatchAcrossPlatforms() {
        fun overlays(folder: File) = folder.listFiles().orEmpty()
            .filter { it.isFile && OVERLAY.matches(it.name) }
            .associateBy { it.name }

        val ios = overlays(File("../../CoreScopeViewer/Resources"))
        val assets = overlays(android.parentFile!!)
        assertEquals("overlay filenames", ios.keys, assets.keys)
        for ((name, file) in ios) {
            assertArrayEquals(name, file.readBytes(), assets.getValue(name).readBytes())
        }
    }

    @Test fun everyTermHasOneEntry() {
        val ids = Glossary.parse(android.readText()).flatMap { section -> section.entries.map { it.id } }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        GlossaryTerm.entries.forEach { assertTrue("no entry for ${it.id}", it.id in ids) }
        assertTrue(Glossary.parse(android.readText()).flatMap { it.entries }.all { it.term.isNotBlank() && it.summary.isNotBlank() })
    }

    @Test fun rolesMapToTheirEntries() {
        assertEquals(GlossaryTerm.REPEATER, GlossaryTerm.forRole("Repeater"))
        assertEquals(GlossaryTerm.ROOM_SERVER, GlossaryTerm.forRole("room"))
        assertEquals(null, GlossaryTerm.forRole("unknown"))
    }
}

private val OVERLAY = Regex("""(?:glossary|whats-new)\..+\.json""")
