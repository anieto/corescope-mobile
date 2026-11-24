package org.nodescope.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit

private const val WORK = "my-nodes-prototype"
private const val ENDPOINT = "https://analyzer.meshtexas.org/api/nodes/" +
    "5744bc79d51c2cdb3386a56a9513bd34a15f43c9a4e3d5446992b0ea601c349b"

private fun Context.snapshot() = getSharedPreferences(WORK, Context.MODE_PRIVATE)

class MyNodesPrototypeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = context.snapshot()
        val name = snapshot.getString("name", null) ?: "My Nodes"
        val checked = snapshot.getLong("checked", 0)
        val heard = snapshot.getLong("lastHeard", 0)
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        provideContent {
            Column(modifier = GlanceModifier.padding(12.dp)) {
                Text(name)
                if (checked == 0L) {
                    Text("Waiting for data")
                } else {
                    Text(if (heard == 0L) "Last heard unknown" else "Last heard " + formatter.format(Date(heard)))
                    Text("Checked " + formatter.format(Date(checked)))
                }
            }
        }
    }
}

class MyNodesPrototypeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MyNodesPrototypeWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        val work = PeriodicWorkRequestBuilder<MyNodesPrototypeWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

class MyNodesPrototypeWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetIds(ComponentName(context, MyNodesPrototypeReceiver::class.java)).isEmpty()) {
            return Result.success()
        }
        val node = try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(ENDPOINT).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(WORK, "Node check returned HTTP ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string() ?: return@use null
                    JSONObject(body).optJSONObject("node")
                }
            }
        } catch (error: IOException) {
            Log.w(WORK, "Node check failed: ${error.javaClass.simpleName}")
            null
        } catch (error: org.json.JSONException) {
            Log.w(WORK, "Node check returned invalid JSON")
            null
        } ?: return Result.success()

        val heard = maxOf(node.timestamp("last_heard"), node.timestamp("last_seen"))
        context.snapshot().edit()
            .putString("name", node.optString("name", "MeshTexas node"))
            .putLong("lastHeard", heard)
            .putLong("checked", System.currentTimeMillis())
            .apply()
        MyNodesPrototypeWidget().updateAll(context)
        return Result.success()
    }

    private fun JSONObject.timestamp(field: String): Long =
        runCatching { Instant.parse(optString(field)).toEpochMilli() }.getOrDefault(0)

    private companion object {
        val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
