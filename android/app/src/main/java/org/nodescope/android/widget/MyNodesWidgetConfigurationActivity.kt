package org.nodescope.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.nodescope.android.MainActivity
import org.nodescope.android.R
import org.nodescope.android.app.NodeScopeApplication
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.design.meshPageBackground
import org.nodescope.android.core.network.AccountException
import org.nodescope.android.core.network.CoreScopeAccountClient
import org.nodescope.android.core.network.normalizeHost
import org.nodescope.android.core.storage.AccountSelectionStore

class MyNodesWidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        val manager = AppWidgetManager.getInstance(this)
        if (manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, MyNodesPrototypeReceiver::class.java)) {
            finish()
            return
        }

        enableEdgeToEdge()
        val widgetStore = MyNodesWidgetStore(this)
        val container = (application as NodeScopeApplication).container
        setContent {
            val preferences by container.preferences.values.collectAsStateWithLifecycle(initialValue = null)
            val current = preferences ?: return@setContent
            val source = normalizeHost(current.host).lowercase()
            val account = AccountSelectionStore.shared(this).activeId(source)
            var choices by remember(source, account) { mutableStateOf(widgetStore.nodes(current)) }
            var selected by rememberSaveable(source) { mutableStateOf(ArrayList(widgetStore.selection(widgetId)?.ids.orEmpty())) }
            var saving by remember { mutableStateOf(false) }
            val eligible = choices.mapTo(mutableSetOf()) { it.id }
            val selectedEligible = selected.filter { it in eligible }
            var loading by remember(source, account) { mutableStateOf(account != null) }
            LaunchedEffect(source, account) {
                if (account != null) loadWatches(source, account, widgetStore)
                choices = widgetStore.nodes(widgetStore.appPreferences())
                loading = false
            }
            NodeScopeTheme(current.appearance, current.backgroundTexture) {
                Surface {
                    Column(Modifier.fillMaxSize().meshPageBackground(anchored = false)
                        .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.widget_choose_nodes_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.widget_choose_nodes_description), style = MaterialTheme.typography.bodyMedium)
                        if (loading && choices.isEmpty()) {
                            CircularProgressIndicator()
                        } else if (choices.isEmpty()) {
                            Text(stringResource(R.string.widget_save_or_watch))
                            TextButton(onClick = { startActivity(Intent(this@MyNodesWidgetConfigurationActivity, MainActivity::class.java)) }) {
                                Text(stringResource(R.string.widget_open_app))
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f, fill = false)) {
                                items(choices, key = { it.id }) { node ->
                                    val checked = node.id in selectedEligible
                                    ListItem(
                                        headlineContent = { Text(node.name, fontFamily = MonaspaceNeon) },
                                        trailingContent = {
                                            Checkbox(checked = checked, onCheckedChange = null)
                                        },
                                        modifier = Modifier.toggleable(
                                            value = checked,
                                            enabled = !saving && (checked || selectedEligible.size < MAX_WIDGET_NODES),
                                            role = Role.Checkbox,
                                            onValueChange = { include ->
                                                selected = ArrayList(if (include) selectedEligible + node.id
                                                    else selectedEligible - node.id)
                                            },
                                        ),
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                        if (choices.isNotEmpty()) {
                            Button(enabled = selectedEligible.isNotEmpty() && !saving, onClick = {
                                saving = true
                                lifecycleScope.launch {
                                    val latest = widgetStore.nodes(widgetStore.appPreferences()).associateBy { it.id }
                                    val nodes = selectedEligible.mapNotNull(latest::get)
                                    if (nodes.size != selectedEligible.size) {
                                        choices = latest.values.toList()
                                        saving = false
                                        return@launch
                                    }
                                    widgetStore.select(widgetId, nodes)
                                    MyNodesWidgetStore.refresh(this@MyNodesWidgetConfigurationActivity)
                                    val glanceId = GlanceAppWidgetManager(this@MyNodesWidgetConfigurationActivity)
                                        .getGlanceIdBy(widgetId)
                                    MyNodesPrototypeWidget().update(this@MyNodesWidgetConfigurationActivity, glanceId)
                                    setResult(RESULT_OK, result)
                                    finish()
                                }
                            }) {
                                Text(stringResource(R.string.widget_save_selection))
                            }
                        }
                        TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
        }
    }

    private suspend fun loadWatches(source: String, account: Long, store: MyNodesWidgetStore) {
        try {
            val client = CoreScopeAccountClient.forSource(this, source)
            if (!client.hasSession || client.me().id != account) {
                MyNodesWidgetStore.publishWatches(this, source, account, emptyList())
                return
            }
            val watches = client.notifications().watches
            if (normalizeHost(store.appPreferences().host).equals(source, ignoreCase = true)) {
                MyNodesWidgetStore.publishWatches(this, source, account, watches)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AccountException) {
            if (error.kind == AccountException.Kind.SIGNED_OUT) {
                MyNodesWidgetStore.publishWatches(this, source, account, emptyList())
            }
        } catch (_: Exception) {
            // Keep previously verified watches available when the analyzer is offline.
        }
    }
}
