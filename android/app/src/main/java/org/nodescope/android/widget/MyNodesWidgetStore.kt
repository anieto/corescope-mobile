package org.nodescope.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nodescope.android.app.NodeScopeApplication
import org.nodescope.android.core.network.CoreScopeFavoriteMerge
import org.nodescope.android.core.network.CoreScopeWatchedNode
import org.nodescope.android.core.network.normalizeHost
import org.nodescope.android.core.storage.AccountSelectionStore
import org.nodescope.android.core.storage.AppPreferences
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.SavedKind
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class WidgetNode(
    val source: String,
    val key: String,
    val name: String,
    val account: Long? = null,
) {
    val id: String get() = "$source|${account ?: "device"}|$key"
}

internal const val MAX_WIDGET_NODES = 3

@Serializable
internal data class WidgetSelection(
    val id: String,
    val revision: String = UUID.randomUUID().toString(),
    val extraIDs: List<String> = emptyList(),
) {
    // The first ID keeps the format used by the original compact widget.
    val ids: List<String> get() = (listOf(id) + extraIDs).distinct().take(MAX_WIDGET_NODES)
}

@Serializable
internal data class WidgetReading(val lastHeard: Long?, val checked: Long)

internal class MyNodesWidgetStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("my-nodes-widget", Context.MODE_PRIVATE)

    val preferences get() = (context.applicationContext as NodeScopeApplication).container.preferences.values

    suspend fun appPreferences(): AppPreferences = preferences.first()

    fun changes() = callbackFlow {
        val sources = listOf(prefs, context.getSharedPreferences("node-library", Context.MODE_PRIVATE),
            context.getSharedPreferences("corescope-favorite-baselines", Context.MODE_PRIVATE))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Any()) }
        sources.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        trySend(Any())
        awaitClose { sources.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }

    fun nodes(preferences: AppPreferences): List<WidgetNode> {
        val source = normalizeHost(preferences.host).lowercase()
        if (!preferences.onboarded || prefs.getString("verified-source", null) != source) return emptyList()
        val device = NodeLibrary.forAnalyzer(context, source).state.favorites.asSequence()
            .filter { it.kind == SavedKind.NODE }
            .map { WidgetNode(source, it.entityId.lowercase(), it.title) }
        val account = AccountSelectionStore.shared(context).activeId(source)
        val watched = account?.let { id -> read<List<CoreScopeWatchedNode>>("watches|$source|$id").orEmpty()
            .asSequence().map {
                val key = it.pubkey.lowercase()
                WidgetNode(source, key, it.name.ifBlank { key.take(12) }, id)
            } }.orEmpty()
        return (device + watched).filter { CoreScopeFavoriteMerge.validKey(it.key) }
            .distinctBy { it.key }.sortedBy { it.name.lowercase() }.toList()
    }

    fun selection(widgetId: Int): WidgetSelection? = read("selection|$widgetId")

    fun select(widgetId: Int, nodes: List<WidgetNode>) {
        val ids = nodes.map { it.id }.distinct().take(MAX_WIDGET_NODES)
        val first = ids.firstOrNull() ?: return
        val selection = WidgetSelection(first, extraIDs = ids.drop(1))
        prefs.edit { putString("selection|$widgetId", Json.encodeToString(selection)) }
    }

    fun remove(widgetId: Int) {
        prefs.edit { remove("selection|$widgetId") }
    }

    fun reading(node: WidgetNode): WidgetReading? = read("reading|${node.id}")

    fun save(node: WidgetNode, reading: WidgetReading) {
        prefs.edit { putString("reading|${node.id}", Json.encodeToString(reading)) }
    }

    val accessRevision: String? get() = prefs.getString("access-revision", null)

    fun verifySource(host: String?) {
        val source = host?.let { normalizeHost(it).lowercase() }
        if (prefs.getString("verified-source", null) != source) {
            prefs.edit {
                putString("verified-source", source)
                putString("access-revision", UUID.randomUUID().toString())
            }
        }
    }

    fun invalidateAccess() {
        prefs.edit { putString("access-revision", UUID.randomUUID().toString()) }
    }

    private inline fun <reified T> read(key: String): T? = prefs.getString(key, null)?.let {
        runCatching { Json.decodeFromString<T>(it) }.getOrNull()
    }

    companion object {
        private const val PERIODIC = "my-nodes-prototype"
        private const val IMMEDIATE = "my-nodes-widget-refresh"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MyNodesPrototypeReceiver::class.java))

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<MyNodesPrototypeWorker>(30, TimeUnit.MINUTES)
                .setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        fun refresh(context: Context) {
            schedule(context)
            val work = OneTimeWorkRequestBuilder<MyNodesPrototypeWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.REPLACE, work)
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(IMMEDIATE)
        }

        suspend fun publishWatches(context: Context, host: String, account: Long, watches: List<CoreScopeWatchedNode>) {
            val source = normalizeHost(host).lowercase()
            if (AccountSelectionStore.shared(context).activeId(source) != account) return
            val store = MyNodesWidgetStore(context)
            val key = "watches|$source|$account"
            val document = Json.encodeToString(watches)
            if (store.prefs.getString(key, null) == document) return
            store.prefs.edit {
                putString(key, document)
                putString("access-revision", UUID.randomUUID().toString())
            }
            if (widgetIds(context).isNotEmpty()) MyNodesPrototypeWidget().updateAll(context)
        }
    }
}
