package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.design.UiText
import org.nodescope.android.core.model.NodeCheckInput
import org.nodescope.android.core.model.NodeCheckLevel
import org.nodescope.android.core.model.NodeCheckRules

class NodeCheckRulesTest {
    private val now = 1_000_000_000L
    private val day = 86_400_000L

    @Test fun quietAndSilentRepeatersKeepTheirThresholdsAndWording() {
        val quiet = NodeCheckRules.report(input(now - day - 1), now)
        assertEquals(NodeCheckLevel.WARN, quiet.level)
        assertEquals(R.string.node_check_rule_headline_warn, (quiet.headline as UiText.Resource).id)
        val title = quiet.findings.first().title as UiText.Resource
        assertEquals(R.string.node_check_rule_quiet, title.id)
        assertEquals(R.string.node_check_rule_ago_a_day, (title.args.single() as UiText.Resource).id)

        val silent = NodeCheckRules.report(input(now - 2 * day - 1), now)
        assertEquals(NodeCheckLevel.BAD, silent.level)
        assertEquals(R.string.node_check_rule_headline_bad, (silent.headline as UiText.Resource).id)
        val duration = (silent.findings.first().title as UiText.Resource).args.single() as UiText.Plural
        assertEquals(R.plurals.node_check_rule_days, duration.id)
        assertEquals(2, duration.count)
        assertArrayEquals(arrayOf<Any>(2), duration.args)
    }

    @Test fun floodCountsAndObserverNamesRemainSeparateArguments() {
        val observation = NodeCheckInput.Observation("o1", "Hilltop", "DFW", 0, 4.0, null)
        val report = NodeCheckRules.report(input(now).copy(
            adverts = listOf(NodeCheckInput.Advert(1, now, "flood", listOf(observation))),
            directObservers = listOf(NodeCheckInput.DirectObserver("o1", "Hilltop", null, null, 2)),
        ), now)
        val good = report.findings.filter { it.level == NodeCheckLevel.GOOD }
        assertEquals(R.string.node_check_rule_heard, (good[0].title as UiText.Resource).id)
        val flood = good[1].title as UiText.Resource
        assertEquals(R.string.node_check_rule_flood_reached_regions, flood.id)
        assertEquals(1, (flood.args[0] as UiText.Plural).count)
        assertEquals(1, (flood.args[1] as UiText.Plural).count)
        assertEquals("DFW", flood.args[2])
        val direct = good[2].title as UiText.Plural
        assertEquals(R.plurals.node_check_rule_direct_observers, direct.id)
        assertEquals(1, direct.count)
        assertEquals(UiText.Raw("Hilltop"), (good[2].detail as UiText.Resource).args.single())
    }

    private fun input(lastHeard: Long) = NodeCheckInput(
        NodeCheckInput.Node("key", "Test node", "repeater", 32.0, -97.0, lastHeard, null, null, null),
        emptyList(), emptyList(), null, null, emptyMap(),
    )
}
