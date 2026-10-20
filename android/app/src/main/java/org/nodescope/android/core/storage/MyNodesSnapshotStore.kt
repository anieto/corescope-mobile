package org.nodescope.android.core.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.AnalyzerSourceSession

/** One shared instance across windows. The injected file contains disposable feature data only. */
class MyNodesSnapshotStore(private val file: File) {
    data class Visit(val id: String, val namespace: MyNodesNamespace, val startedAt: Long,
                     val baselines: Map<String, MyNodesBaseline>, val expiredKeys: Set<String>,
                     val omittedObserverCount: Int)
    private data class ActiveVisit(val visit: Visit, var entities: Set<MyNodesEntity>, var latestTime: Long,
                                   val lifetime: AnalyzerSourceSession?)
    @Serializable private data class Entry(
        val namespace: MyNodesNamespace, val entity: MyNodesEntity,
        val baseline: MyNodesBaseline? = null, val candidate: MyNodesBaseline? = null,
        val candidateOwner: String? = null,
    )
    @Serializable private data class Document(val schemaVersion: Int, val entries: Map<String, Entry> = emptyMap())

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private var document: Document? = null
    private val visits = mutableMapOf<String, ActiveVisit>()

    private fun load(): Document {
        document?.let { return it }
        val text = if (file.exists()) file.readText() else null
        val loaded = text?.let { runCatching { json.decodeFromString<Document>(it) }.getOrNull() }
            ?.takeIf { it.schemaVersion == 1 } ?: Document(1)
        val recovered = loaded.copy(entries = loaded.entries.mapValues { (_, entry) ->
            if (entry.candidate == null) entry else entry.copy(baseline = entry.candidate, candidate = null, candidateOwner = null)
        })
        if (file.exists()) save(recovered)
        document = recovered
        return recovered
    }

    suspend fun begin(namespace: MyNodesNamespace, nodes: Set<String>, favoriteObservers: Set<String>,
                      coveringObservers: Set<String> = emptySet(), now: Long,
                      lifetime: AnalyzerSourceSession? = null): Visit = withContext(Dispatchers.IO) {
        mutex.withLock {
            lifetime?.checkCurrent()
            val extra = (coveringObservers - favoriteObservers).sorted()
            val entities = nodes.map { MyNodesEntity.node(it) }.toSet() +
                (favoriteObservers + extra.take(200)).map { MyNodesEntity.observer(it) }
            val next = load().copy(entries = load().entries.filterValues { it.namespace != namespace || it.entity in entities })
            commit(next)
            visits.values.filter { it.visit.namespace == namespace }.forEach { it.entities = it.entities.intersect(entities) }
            val baselines = mutableMapOf<String, MyNodesBaseline>()
            val expired = mutableSetOf<String>()
            next.entries.values.filter { it.namespace == namespace }.forEach { entry ->
                entry.baseline?.let { baseline ->
                    val key = metricKey(entry.entity, baseline.metric.category)
                    val age = difference(now, baseline.metric.evaluatedAt)
                    if (age != null && age in 0..604_800_000L) baselines[key] = baseline else expired += key
                }
            }
            val visit = Visit(UUID.randomUUID().toString(), namespace, now, baselines.toMap(), expired.toSet(), (extra.size - 200).coerceAtLeast(0))
            visits[visit.id] = ActiveVisit(visit, entities, now, lifetime)
            visit
        }
    }

    /** Called only after successful fresh data is rendered. Cache-only display cannot advance a baseline. */
    suspend fun rendered(visit: Visit, entity: MyNodesEntity, metric: MyNodesMetric,
                         freshFetch: Boolean, now: Long): MyNodesEvent? = reviewed(visit, entity, metric, freshFetch, now).event

    /** Call after rendering; distinguishes unavailable comparisons from unchanged evidence. */
    suspend fun reviewed(visit: Visit, entity: MyNodesEntity, metric: MyNodesMetric,
                         freshFetch: Boolean, now: Long): MyNodesReviewResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = visits[visit.id]?.takeIf { it.visit.namespace == visit.namespace } ?: return@withLock MyNodesReviewResult.unavailable
            active.lifetime?.checkCurrent()
            if (now < active.latestTime) { active.lifetime?.invalidate(); visits.remove(visit.id); return@withLock MyNodesReviewResult.unavailable }
            active.latestTime = now
            val age = difference(now, metric.fetchedAt) ?: return@withLock MyNodesReviewResult.unavailable
            val maximumAge = if (metric.category == MyNodesMetric.Category.SIGNAL) 900_000L else 300_000L
            if (entity !in active.entities || !freshFetch || !metric.eligible(now) ||
                metric.fetchedAt < visit.startedAt || metric.evaluatedAt < visit.startedAt ||
                metric.evaluatedAt > now || age !in 0..maximumAge ||
                (entity.kind == MyNodesEntity.Kind.OBSERVER) != (metric.category == MyNodesMetric.Category.OBSERVER)) return@withLock MyNodesReviewResult.unavailable
            val localKey = metricKey(entity, metric.category)
            val key = MyNodesIdentity.encode(listOf(visit.namespace.id, localKey))
            val current = load()
            val entry = current.entries[key] ?: Entry(visit.namespace, entity)
            val previous = entry.candidate ?: entry.baseline
            val result = metric.review(visit.namespace, entity, visit.baselines[localKey], localKey in visit.expiredKeys)
            if (previous != null && (metric.fetchedAt <= previous.metric.fetchedAt ||
                metric.evaluatedAt < previous.metric.evaluatedAt || metric.measuredAt < previous.metric.measuredAt)) return@withLock result
            val candidate = MyNodesBaseline(UUID.randomUUID().toString(), metric)
            commit(current.copy(entries = current.entries + (key to entry.copy(candidate = candidate, candidateOwner = visit.id))))
            result
        }
    }

    suspend fun end(visit: Visit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (visit.id !in visits) return@withLock
            visits[visit.id]?.lifetime?.invalidate()
            commit(load().copy(entries = load().entries.mapValues { (_, entry) ->
                if (entry.candidateOwner == visit.id) entry.copy(baseline = entry.candidate, candidate = null, candidateOwner = null) else entry
            }))
            visits.remove(visit.id)
            Unit
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            visits.values.forEach { it.lifetime?.invalidate() }
            visits.clear()
            document = Document(1)
            Files.deleteIfExists(file.toPath())
            Unit
        }
    }

    private fun commit(next: Document) { save(next); document = next }

    private fun save(next: Document) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { stream ->
                stream.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        fun metricKey(entity: MyNodesEntity, category: MyNodesMetric.Category): String =
            MyNodesIdentity.encode(listOf(entity.id, category.wire))
        private fun difference(later: Long, earlier: Long): Long? =
            runCatching { Math.subtractExact(later, earlier) }.getOrNull()
    }
}
