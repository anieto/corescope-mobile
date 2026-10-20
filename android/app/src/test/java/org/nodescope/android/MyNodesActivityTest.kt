package org.nodescope.android

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.protocolJson

class MyNodesActivityTest {
    @Serializable private data class Record(
        val lastSeen: Long?, val lastHeard: Long?, val fetchedAt: Long,
        val evaluatedAt: Long, val successfulRecord: Boolean,
    ) {
        fun assessment() = MyNodesActivity.assess(lastSeen, lastHeard, fetchedAt, evaluatedAt, successfulRecord)
    }

    @Serializable private data class Case(
        val name: String, val current: Record, val baseline: Record?,
        val expectedState: MyNodesActivityState, val expectedEligible: Boolean,
        val expectedChange: MyNodesActivityChange?,
    )

    @Test fun sharedActivityCases() {
        val cases = protocolJson.decodeFromString<List<Case>>(fixture("my-nodes/activity-cases.json"))
        assertEquals(22, cases.size)
        for (entry in cases) {
            val current = entry.current.assessment()
            assertEquals(entry.name, entry.expectedState, current.state)
            assertEquals(entry.name, entry.expectedEligible, current.eligible)
            assertEquals(entry.name, entry.expectedChange, entry.baseline?.let { current.change(it.assessment()) })
        }
    }
}
