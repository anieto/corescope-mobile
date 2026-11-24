package org.nodescope.android.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.layout.ContentScale
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.nodescope.android.MainActivity
import org.nodescope.android.R
import org.nodescope.android.core.model.NodeScopeLink
import org.nodescope.android.core.network.endpoint
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

internal const val WIDGET_NODE_LINK = "widget-node"

class MyNodesPrototypeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val store = MyNodesWidgetStore(context)
        val initialPreferences = store.appPreferences()
        provideContent {
            val preferences by store.preferences.collectAsState(initialPreferences)
            // Glance can recompose the same session without calling provideGlance again.
            val change by remember { store.changes() }.collectAsState(null)
            val choices = remember(preferences, change) { store.nodes(preferences) }
            val selection = remember(change) { store.selection(widgetId) }
            val selected = selection?.ids.orEmpty().mapNotNull { id -> choices.firstOrNull { it.id == id } }
            val node = selected.firstOrNull()
            val readings = remember(selected, change) { selected.associate { it.id to store.reading(it) } }
            val reading = readings[node?.id]
            val colors = WidgetColors(context)
            val size = LocalSize.current
            val capacity = when {
                size.height >= 120.dp -> MAX_WIDGET_NODES
                size.height >= 96.dp -> 2
                else -> 1
            }
            val visibleNodes = selected.take(capacity)
            val compact = size.height < 120.dp
            val inset = if (compact) 8.dp else 12.dp
            val background = widgetMesh(colors.dark, preferences.backgroundTexture)
            if (visibleNodes.size > 1) {
                MyNodesList(context, visibleNodes, readings, colors, background)
                return@provideContent
            }
            val open = node?.let { nodeIntent(context, it) } ?: setupIntent(context, widgetId)
            val secondary = TextStyle(color = ColorProvider(Color(colors.secondary)), fontSize = 12.sp)
            Column(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ImageProvider(background))
                    .clickable(actionStartActivity(open))
                    .padding(inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = node?.name ?: context.getString(R.string.widget_my_nodes)
                val width = (size.width - inset * 2).value
                val antenna = ImageProvider(R.drawable.ic_widget_antenna)
                if (compact) {
                    val title = widgetText(context, name, width - 47, colors.primary, fontSize = 14f, lines = 2)
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(antenna, contentDescription = null, modifier = GlanceModifier.size(14.dp))
                        Spacer(GlanceModifier.width(5.dp))
                        Image(ImageProvider(title.bitmap), contentDescription = name,
                            contentScale = ContentScale.FillBounds,
                            modifier = GlanceModifier.defaultWeight().height(title.height.dp))
                        Spacer(GlanceModifier.width(4.dp))
                        NodeCheckShortcut(context)
                    }
                } else {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(antenna, contentDescription = null, modifier = GlanceModifier.size(17.dp))
                        Spacer(GlanceModifier.defaultWeight())
                        NodeCheckShortcut(context, showLabel = true)
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    val title = widgetText(context, name, width, colors.primary, fontSize = 16f)
                    Image(ImageProvider(title.bitmap), contentDescription = name,
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier.fillMaxWidth().height(title.height.dp))
                }
                Spacer(GlanceModifier.height(if (compact) 2.dp else 7.dp))
                if (reading != null) {
                    val now = System.currentTimeMillis()
                    val heard = reading.lastHeard?.let {
                        DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS,
                            if (compact) DateUtils.FORMAT_ABBREV_RELATIVE else 0).toString()
                    } ?: context.getString(R.string.widget_unknown)
                    if (compact) {
                        Text(context.getString(R.string.widget_last_heard_value, heard), style = secondary, maxLines = 1)
                    } else {
                        Text(context.getString(R.string.widget_last_heard), style = secondary)
                        val activity = widgetText(context, heard, width, colors.primary, fontSize = 15f, lines = 1)
                        Image(ImageProvider(activity.bitmap), contentDescription = heard,
                            contentScale = ContentScale.FillBounds,
                            modifier = GlanceModifier.fillMaxWidth().height(activity.height.dp))
                    }
                    if (!compact) Spacer(GlanceModifier.height(3.dp))
                    Text(checkedText(context, reading.checked), style = secondary, maxLines = if (compact) 1 else 2)
                } else {
                    val status = when {
                        node != null -> R.string.widget_waiting
                        choices.isNotEmpty() -> R.string.widget_choose_node
                        else -> R.string.widget_save_or_watch
                    }
                    Text(context.getString(status), style = secondary, maxLines = 3)
                }
            }
        }
    }
}

private fun nodeIntent(context: Context, node: WidgetNode): Intent =
    Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("nodescope").authority(WIDGET_NODE_LINK)
        .appendQueryParameter("id", node.id).build(), context, MainActivity::class.java)

private fun setupIntent(context: Context, widgetId: Int): Intent =
    Intent(context, MyNodesWidgetConfigurationActivity::class.java)
        .setData(Uri.parse("nodescope://widget-setup/$widgetId"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

@Composable
private fun MyNodesList(
    context: Context, nodes: List<WidgetNode>, readings: Map<String, WidgetReading?>,
    colors: WidgetColors, background: android.graphics.Bitmap,
) {
    val size = LocalSize.current
    val inset = 8.dp
    val spacing = 6.dp
    val timeWidth = (size.width * 0.39f).coerceIn(61.dp, 106.dp)
    val nameWidth = (size.width - inset * 2 - timeWidth - spacing).value
    val label = TextStyle(color = ColorProvider(Color(colors.secondary)), fontSize = 10.sp)
    val value = TextStyle(color = ColorProvider(Color(colors.primary)), fontSize = 12.sp)
    val now = System.currentTimeMillis()
    val sharedCheck = readings[nodes.first().id]?.checked?.takeIf { checked ->
        nodes.all { readings[it.id]?.checked?.div(DateUtils.MINUTE_IN_MILLIS) == checked / DateUtils.MINUTE_IN_MILLIS }
    }
    val rowHeight = ((size.height - if (sharedCheck != null) 52.dp else 40.dp) / nodes.size)
        .coerceIn(24.dp, 52.dp)

    Column(GlanceModifier.fillMaxSize().background(ImageProvider(background)).padding(inset)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_widget_antenna), contentDescription = null,
                modifier = GlanceModifier.size(13.dp))
            Spacer(GlanceModifier.width(5.dp))
            val heading = context.getString(R.string.widget_my_nodes)
            val title = widgetText(context, heading, (size.width - 116.dp).value, colors.primary,
                fontSize = 12f, lines = 1)
            Image(ImageProvider(title.bitmap), contentDescription = heading,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.defaultWeight().height(title.height.dp))
            NodeCheckShortcut(context, showLabel = size.width >= 260.dp)
            Spacer(GlanceModifier.width(spacing))
            Column(GlanceModifier.width(timeWidth), horizontalAlignment = Alignment.End) {
                Text(context.getString(R.string.widget_last_heard), style = label, maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(2.dp))
        nodes.forEach { node ->
            val reading = readings[node.id]
            Row(GlanceModifier.fillMaxWidth().height(rowHeight).clickable(actionStartActivity(nodeIntent(context, node))),
                verticalAlignment = Alignment.CenterVertically) {
                val name = widgetText(context, node.name, nameWidth, colors.primary, fontSize = 13f,
                    lines = if (rowHeight >= 30.dp) 2 else 1)
                Image(ImageProvider(name.bitmap), contentDescription = node.name,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.defaultWeight().height(name.height.dp))
                Spacer(GlanceModifier.width(spacing))
                Column(GlanceModifier.width(timeWidth), horizontalAlignment = Alignment.End) {
                    if (reading == null) {
                        Text(context.getString(R.string.widget_waiting), style = label, maxLines = 1)
                    } else {
                        val heard = reading.lastHeard?.let {
                            DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                        } ?: context.getString(R.string.widget_unknown)
                        Text(heard, style = value, maxLines = 1)
                        if (sharedCheck == null) {
                            Text(checkedText(context, reading.checked), style = label, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (sharedCheck != null) {
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(checkedText(context, sharedCheck), style = label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NodeCheckShortcut(context: Context, showLabel: Boolean = false) {
    val name = context.getString(R.string.app_node_check)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NodeScopeLink.nodeCheck().url), context, MainActivity::class.java)
    Row(
        GlanceModifier.height(22.dp).clickable(actionStartActivity(intent)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.ic_widget_node_check), contentDescription = if (showLabel) null else name,
            modifier = GlanceModifier.size(16.dp))
        if (showLabel) {
            Spacer(GlanceModifier.width(4.dp))
            Text(name, style = TextStyle(color = ColorProvider(Color(0xFF1682DB)), fontSize = 11.sp), maxLines = 1)
        }
    }
}

private fun checkedText(context: Context, checked: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or (if (DateUtils.isToday(checked)) 0
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    return context.getString(R.string.widget_checked, DateUtils.formatDateTime(context, checked, flags))
}

class MyNodesPrototypeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MyNodesPrototypeWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        MyNodesWidgetStore.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = MyNodesWidgetStore(context)
        appWidgetIds.forEach(store::remove)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MyNodesWidgetStore.cancel(context)
    }
}

class MyNodesPrototypeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = lock.withLock {
        val context = applicationContext
        val ids = MyNodesWidgetStore.widgetIds(context)
        if (ids.isEmpty()) return@withLock Result.success()

        val store = MyNodesWidgetStore(context)
        val revision = store.accessRevision
        val choices = store.nodes(store.appPreferences()).associateBy { it.id }
        val requested = ids.asSequence().mapNotNull { id -> store.selection(id)?.let { id to it } }
            .flatMap { (id, selection) -> selection.ids.map { nodeID -> nodeID to (id to selection) } }
            .groupBy({ it.first }, { it.second })
        for ((id, selections) in requested) {
            val node = choices[id] ?: continue
            if (store.reading(node)?.let { System.currentTimeMillis() - it.checked < 5 * 60_000 } == true) continue
            val reading = fetch(node) ?: continue
            if (selections.none { (widgetId, selection) -> store.selection(widgetId) == selection }) continue
            if (store.nodes(store.appPreferences()).none { it.id == node.id } || store.accessRevision != revision) continue
            store.save(node, reading)
        }
        MyNodesPrototypeWidget().updateAll(context)
        Result.success()
    }

    private suspend fun fetch(node: WidgetNode): WidgetReading? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(endpoint(node.source, "api", "nodes", node.key))
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val result = JSONObject(body).optJSONObject("node") ?: return@use null
                val lastHeard = listOf("last_heard", "last_seen").mapNotNull {
                    runCatching { Instant.parse(result.optString(it)).toEpochMilli() }.getOrNull()
                }.maxOrNull()
                WidgetReading(lastHeard, System.currentTimeMillis())
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private companion object {
        val lock = Mutex()
        val client = OkHttpClient.Builder().followRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }
}
