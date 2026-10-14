package org.nodescope.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.*

/**
 * Beacon Phase 3 live acceptance (H3) against the approved servers. Skipped unless
 * `NODESCOPE_LIVE=1`; it makes about 30–60 read-only requests per server, well under Beacon's
 * 300 per minute, and never provokes throttling. Results go to the test's standard output.
 */
class LiveAcceptanceTest {
    private class Request(val path: String, val status: Int, val bytes: Long, val millis: Long)

    @Test fun phaseThreeAcceptance() = runBlocking {
        assumeTrue(System.getenv("NODESCOPE_LIVE") == "1")
        for (host in listOf("beacon.meshtexas.org", "map.meshcore.coloradomesh.org", "dev.meshcore.ca")) {
            println("== $host")
            val log = mutableListOf<Request>()
            // The app's timeouts (NodeScopeApplication); dev.meshcore.ca can take ~10 s to answer.
            val http = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain ->
                val started = System.nanoTime()
                val response = chain.proceed(chain.request())
                val body = response.peekBody(Long.MAX_VALUE)
                val url = chain.request().url
                synchronized(log) {
                    log += Request(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""), response.code,
                        body.contentLength(), (System.nanoTime() - started) / 1_000_000)
                }
                response
            }.build()
            suspend fun <T> step(label: String, block: suspend () -> T, describe: (T) -> String): T? {
                val mark = synchronized(log) { log.size }
                val started = System.currentTimeMillis()
                return try {
                    block().also { value ->
                        val requests = synchronized(log) { log.drop(mark) }
                        println("  %-34s %2d req %7d B %5d ms  %s".format(label, requests.size, requests.sumOf { it.bytes },
                            System.currentTimeMillis() - started, describe(value)))
                        if (requests.any { it.status >= 400 }) println("     statuses ${requests.map { it.status }}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val requests = synchronized(log) { log.drop(mark) }
                    println("  $label: ERROR ${e::class.simpleName}: ${e.message} [${requests.size} req, ${requests.map { it.status }}]")
                    null
                }
            }

            val session = AnalyzerSourceSession()
            val client = BeaconApiClient("https://$host", session, http)
            val info = step("info", { client.serverInfo() }) { "Beacon ${it.serverVersion}" } ?: continue
            val backend = BeaconBackend(client, info)
            val diagnostics = step("diagnostics", { AnalyzerDiagnostics(http).runBeacon(host, backend) }) { report ->
                "compat ${report.compatibilityLevel} · " + report.capabilities.joinToString(" ") { "${it.capability.name.lowercase()}=${it.level.name.lowercase()}" }
            }
            check(diagnostics?.capabilities?.none { it.level == DiagnosticLevel.FAILURE } != false) { "Diagnostics reported a failure" }

            val nodes = step("nodes page 1", { backend.nodeReader.nodePage(null) }) { "${it.items.size} nodes, more=${it.nextCursor != null}" }
                ?.items.orEmpty().sortedByDescending { it.lastHeard ?: 0 }
            nodes.firstOrNull { it.nodeType == 2 }?.let { busy ->
                println("  busy node: ${busy.name} (${busy.publicKey.take(8)})")
                step("neighbors", { backend.neighbors(host, busy.publicKey) }) { "${it.neighbors.size} neighbors" }
                for (days in listOf(1, 7, 30)) step("node analytics ${days}d", { backend.nodeAnalytics(host, busy.publicKey, days) }) { a ->
                    "${a.computedStats?.totalPackets} obs · ${a.context?.completeness?.name?.lowercase()} · range ${a.timeRange?.days}d"
                }
            }
            // The first page holds recently heard nodes; look a few pages further for one outside the window.
            val quietNode = step("find quiet node (≤5 pages)", {
                var cursor: org.nodescope.android.core.network.AnalyzerCursor? = null
                var found: org.nodescope.android.core.network.BeaconNode? = null
                for (i in 0 until 5) {
                    val page = backend.nodeReader.nodePage(null, cursor = cursor)
                    found = page.items.filter { it.lastHeard != null }.minByOrNull { it.lastHeard!! }
                        ?.takeIf { System.currentTimeMillis() - it.lastHeard!! > 8 * 86_400_000L } ?: found
                    cursor = page.nextCursor ?: break
                    if (found != null) break
                }
                found ?: nodes.lastOrNull { it.lastHeard != null }
            }) { "${it?.name}" }
            quietNode?.let { quiet ->
                val age = (System.currentTimeMillis() - quiet.lastHeard!!) / 86_400_000
                println("  quietest node: ${quiet.name} last heard ${age}d ago")
                step("quiet node analytics 7d", { backend.nodeAnalytics(host, quiet.publicKey, 7) }) { a ->
                    "${a.computedStats?.totalPackets} obs · ${a.context?.completeness?.name?.lowercase()}"
                }
                step("quiet neighbors", { backend.neighbors(host, quiet.publicKey) }) { "${it.neighbors.size} neighbors" }
            }
            val observers = step("observers", { backend.observers(host) }) { "${it.size} observers" }.orEmpty()
            // Two observers plus any pyMC ones, whose telemetry uses fractional airtime.
            for (observer in (observers.take(2) + observers.filter { it.name.orEmpty().contains("pyMC") }.take(2)).distinctBy { it.id }) step("observer ${observer.name?.take(18)}", { backend.observerAnalytics(host, observer.id) }) { a ->
                "${a.timeline.sumOf { it.count }} obs · ${a.packetTypes.size} types · telemetry ${a.telemetry?.size}" +
                    (a.telemetry?.lastOrNull()?.let { " · batt ${it.batteryMillivolts} nf ${it.noiseFloorDecibels}" } ?: "")
            }
            val channels = step("channels (keyKnown)", { backend.channels(AnalyzerSelection(host, null)) }) { "${it.size} channels" }.orEmpty()
            channels.firstOrNull()?.let { channel ->
                val first = step("messages ${channel.name}", { backend.channelMessages(host, channel.id) }) { "${it.messages.size} msgs, total ${it.total}" }
                if (first != null && (first.total ?: 0) > first.messages.size) {
                    step("older messages", { backend.channelMessages(host, channel.id, first.messages.size) }) { "${it.messages.size} msgs, total ${it.total}" }
                }
            }
            channels.lastOrNull()?.takeIf { channels.size > 1 }?.let { channel ->
                step("messages ${channel.name}", { backend.channelMessages(host, channel.id) }) { "${it.messages.size} msgs, total ${it.total}" }
            }
            // Source switching: an invalidated session must refuse to load.
            session.invalidate()
            try {
                backend.channels(AnalyzerSelection(host, null))
                println("  source switch: OLD SESSION STILL LOADED (bad)")
            } catch (e: CancellationException) {
                println("  source switch: old session rejected")
            }
            println("  total ${log.size} requests, statuses ${log.groupingBy { it.status }.eachCount()}")
            check(log.none { it.status == 429 }) { "Throttled during acceptance" }
        }
    }
}
