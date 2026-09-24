package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.feature.map.*
import kotlin.math.cos
import kotlin.math.hypot

class LiveRouteAnimationTest {
    private val a = Coordinate(30.0, -97.0)
    private val b = Coordinate(30.0, -96.0)
    private val c = Coordinate(31.0, -96.0)
    private fun route(vararg chains: List<Coordinate>, at: Long = 1_000) = LiveRoute("r", at, "#66B9FF", emptyList(), chains.toList())

    @Test fun hopsTravelInSequenceAndDrawProgressively() {
        val route = route(listOf(a, b, c))
        assertEquals(listOf(1_000L, 1_660L), route.hops.map { it.startsAt })
        val halfway = routeFrame(listOf(route), 1_330, animate = true)
        assertEquals(1, halfway.lines.size) // second hop has not started
        assertEquals(-96.5, halfway.lines.single().points.last().longitude, 0.001)
        assertEquals(1, halfway.heads.size)
        val second = routeFrame(listOf(route), 1_700, animate = true)
        assertEquals(listOf(1f, 1f), second.lines.map { it.opacity }) // first hop complete, second drawing
        assertEquals(1, second.rings.size) // arrival ring at the first hop's end
        assertEquals(b, second.rings.single().center)
    }

    @Test fun completedHopsHoldThenFade() {
        val route = route(listOf(a, b))
        assertEquals(1f, routeFrame(listOf(route), 1_000 + 660 + 450, true).lines.single().opacity, 0.001f)
        assertEquals(0.5f, routeFrame(listOf(route), 1_000 + 660 + 450 + 275, true).lines.single().opacity, 0.01f)
        assertTrue(routeFrame(listOf(route), 1_000 + 660 + 450 + 550, true).lines.isEmpty())
        assertEquals(1_000L + 660 + 450 + 550, route.endsAt)
        assertFalse(route.isActive(route.endsAt))
        assertFalse(route.isActive(999))
    }

    @Test fun separateSubchainsStartTogetherAndIsolatedPointsPulse() {
        val route = route(listOf(a, b), listOf(c, Coordinate(31.0, -95.0)), listOf(Coordinate(29.0, -98.0)))
        assertEquals(listOf(1_000L, 1_000L), route.hops.map { it.startsAt })
        val frame = routeFrame(listOf(route), 1_100, true)
        assertEquals(2, frame.heads.size)
        assertEquals(2, frame.rings.size) // outer and inner solo pulse rings
        assertEquals(4_000L, route.endsAt)
    }

    @Test fun reducedMotionShowsCompleteHopsWithoutMovement() {
        val frame = routeFrame(listOf(route(listOf(a, b, c))), 1_100, animate = false)
        assertEquals(listOf(listOf(a, b)), frame.lines.map { it.points })
        assertTrue(frame.heads.isEmpty())
        assertTrue(frame.rings.isEmpty())
    }

    @Test fun onlyTheNewestRoutesAnimate() {
        val routes = (0 until 25).map { route(listOf(a, b), at = 1_000L + it) }
        val frame = routeFrame(routes, 1_100, true)
        assertEquals(20, frame.heads.size)
    }

    @Test fun coLocatedNodesSpreadByAFixedScreenDistance() {
        fun node(key: String, at: Coordinate) = MeshNode(key, key, "repeater", at.latitude, at.longitude, "2026-09-23T00:00:00Z")
        val nodes = listOf(node("b", a), node("a", a), node("solo", b))
        val near = spreadCoincidentNodes(nodes, zoom = 12.0)
        assertEquals(setOf("a", "b"), near.keys)
        val first = near.getValue("a")
        val second = near.getValue("b")
        assertTrue(first.latitude > a.latitude) // first node (by key) sits above the shared position
        assertEquals(a.latitude, (first.latitude + second.latitude) / 2, 1e-9)
        fun separationDp(map: Map<String, Coordinate>, zoom: Double): Double {
            val degreesPerDp = 360.0 / (512.0 * Math.pow(2.0, zoom))
            val p = map.getValue("a"); val q = map.getValue("b")
            return hypot(p.longitude - q.longitude, (p.latitude - q.latitude) / cos(Math.toRadians(a.latitude))) / degreesPerDp
        }
        assertEquals(36.0, separationDp(near, 12.0), 0.01)
        assertEquals(36.0, separationDp(spreadCoincidentNodes(nodes, 15.0), 15.0), 0.01)
        assertEquals(near.getValue("a"), nodeFeatures(nodes, near).features()!!
            .first { it.getStringProperty("publicKey") == "a" }.geometry().let { it as org.maplibre.geojson.Point }
            .let { Coordinate(it.latitude(), it.longitude()) })
    }

    @Test fun nodeNamesShowOnlyWhenCloseAndSparse() {
        assertTrue(showsNodeLabels(0.08, 60))
        assertFalse(showsNodeLabels(0.081, 10))
        assertFalse(showsNodeLabels(0.05, 61))
    }

    @Test fun historyRoutesAppearArrivedAndFadeOverTwelveSeconds() {
        val arrivedAt = 10_000L
        val route = LiveRoute("h", arrivedAt, "#66B9FF", emptyList(), listOf(listOf(a, b, c), listOf(Coordinate(29.0, -98.0))), historical = true)
        assertTrue(route.pulses.isEmpty()) // isolated points do not pulse for history
        assertEquals(arrivedAt - 660 + 12_000, route.endsAt)
        val soon = routeFrame(listOf(route), arrivedAt + 1_000, animate = true)
        assertEquals(listOf(listOf(a, b), listOf(b, c)), soon.lines.map { it.points }) // complete, not replayed
        assertTrue(soon.heads.isEmpty())
        assertEquals(1f - 1_660f / 12_000f, soon.lines.first().opacity, 0.001f)
        assertEquals(soon.lines[0].opacity, soon.lines[1].opacity, 0.0001f) // hops fade together
        assertFalse(route.isActive(route.endsAt))
        assertTrue(routeFrame(listOf(route), route.endsAt, true).lines.isEmpty())
    }
}
