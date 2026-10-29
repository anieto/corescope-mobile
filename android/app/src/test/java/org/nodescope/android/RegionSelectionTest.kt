package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*

class RegionSelectionTest {
    private val regions = mapOf("AUS" to "Austin, TX", "SAT" to "San Antonio, TX", "ACT" to "Waco, TX", "DFW" to "Dallas-Fort Worth, TX")
    private val central = RegionQuickPick("Central Texas", "Austin, San Antonio and Waco.", listOf("AUS", "SAT", "ACT"))

    @Test fun allIncludesEverything() {
        assertNull(regionCodes(null))
        assertTrue(regionIncludes(null, "SAT"))
        assertTrue(regionIncludes(null, null))
    }

    @Test fun singleAndPickSelectionsCompareByCode() {
        assertTrue(regionIncludes("SAT", "sat"))
        assertFalse(regionIncludes("SAT", "AUS"))
        assertTrue(regionIncludes("AUS,SAT,ACT", "act"))
        assertFalse(regionIncludes("AUS,SAT,ACT", "DFW"))
        assertFalse(regionIncludes("AUS,SAT,ACT", null))
    }

    @Test fun picksKeepOnlyTheHostsRegions() {
        val west = RegionQuickPick("West Texas", null, listOf("ELP", "MAF"))
        val partial = RegionQuickPick("North Texas", null, listOf("DFW", "TXK"))
        val picks = availablePicks(listOf(central, west, partial), regions)
        assertEquals(listOf("Central Texas", "North Texas"), picks.map { it.name })
        assertEquals(listOf("DFW"), picks[1].regions)
    }

    @Test fun pickIsActiveOnlyForItsExactCodes() {
        val picks = availablePicks(listOf(central), regions)
        assertEquals(central.name, activePick(picks, "SAT,ACT,AUS")?.name)
        assertNull(activePick(picks, "AUS,SAT"))
        assertEquals("Central Texas", regionTitle(picks, central.selection))
        assertEquals("SAT", regionTitle(picks, "SAT"))
        assertEquals("AUS, DFW", regionTitle(picks, "DFW,AUS"))
        assertNull(regionTitle(picks, null))
    }

    @Test fun quickPicksDecodeFromTheServer() {
        val json = """{"quickPicks":[{"name":"Central Texas","description":"Austin, San Antonio and Waco.","regions":["AUS","SAT","ACT"]}]}"""
        val response = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<RegionQuickPicksResponse>(json)
        assertEquals(listOf(central), response.quickPicks)
    }
}
