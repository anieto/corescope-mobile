package org.nodescope.android

import org.nodescope.android.core.network.StagingPushBinding
import org.nodescope.android.core.network.inactivePushAnalyzer
import org.nodescope.android.core.network.matchingPushOrigin
import org.nodescope.android.core.network.StagingPushCapability
import org.nodescope.android.core.network.StagingPushRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StagingPushRouteTest {
    @Test
    fun onlyVersionedSourceBoundNodePayloadsCanRoute() {
        val node = "a".repeat(64)
        val valid = mapOf("version" to "1", "analyzer_id" to "analyzer_example",
            "subscription_id" to "sub_example", "node_pubkey" to node)
        assertEquals(StagingPushRoute("analyzer_example", "sub_example", node), StagingPushRoute.parse(valid::get))
        for ((key, bad) in mapOf("version" to "2", "analyzer_id" to "a/b",
            "subscription_id" to "", "node_pubkey" to "nodescope://node/$node")) {
            assertNull(StagingPushRoute.parse((valid + (key to bad))::get))
            assertNull(StagingPushRoute.parse((valid - key)::get))
        }
    }

    @Test
    fun stagingWillOnlyLinkToThePinnedRelayAndPlatform() {
        val valid = StagingPushCapability("https://push-staging.nodescope.app/", "analyzer_a", 1, listOf("android"))
        assertTrue(valid.supported())
        assertFalse(valid.copy(relayOrigin = "https://push-staging.nodescope.app.example.org").supported())
        assertFalse(valid.copy(contractVersion = 2).supported())
        assertFalse(valid.copy(platforms = listOf("ios")).supported())
    }
    @Test
    fun routingFindsOnlyAnExactBoundSource() {
        val route = StagingPushRoute("analyzer", "subscription", "a".repeat(64))
        val origin = "https://analyzer.example.org/"
        val other = "https://other.example.org/"
        val binding = StagingPushBinding("analyzer", 42, "subscription")
        val otherBinding = binding.copy(subscription = "other")
        val bindings = mapOf(origin to binding, other to otherBinding)
        assertEquals(origin, matchingPushOrigin(route, origin, bindings))
        assertEquals(origin, matchingPushOrigin(route, other, bindings))
        assertNull(matchingPushOrigin(route, other, mapOf(other to otherBinding)))
        assertNull(matchingPushOrigin(route, null, mapOf(origin to binding, other to binding)))
    }

    @Test
    fun staleBindingsRequireDefiniteEvidence() {
        val binding = StagingPushBinding("analyzer", 42, "subscription")
        val capability = StagingPushCapability("https://push-staging.nodescope.app", "analyzer", 1, listOf("android"))
        assertFalse(binding.isStale(capability))
        assertTrue(binding.isStale(capability.copy(analyzerId = "replacement")))
        assertTrue(binding.isStale(null))
        assertTrue(inactivePushAnalyzer(410, "analyzer_inactive", linkProof = false))
        assertFalse(inactivePushAnalyzer(503, "analyzer_inactive", linkProof = false))
        assertFalse(inactivePushAnalyzer(410, "inactive_proof", linkProof = false))
        assertTrue(inactivePushAnalyzer(404, "unknown_analyzer", linkProof = true))
        assertFalse(inactivePushAnalyzer(404, "unknown_analyzer", linkProof = false))
    }
}
