package org.nodescope.android

import org.nodescope.android.core.design.InAppUriHandler
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.nodescope.android.core.network.StagingPushRoute
import org.nodescope.android.core.network.normalizeHost
import androidx.compose.ui.platform.LocalUriHandler
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nodescope.android.core.network.PushPlatform
import org.nodescope.android.core.network.StagingPush
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.storage.DistanceUnit
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nodescope.android.app.*
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.storage.Appearance

class MainActivity : ComponentActivity() {
    private data class PushNotice(val route: StagingPushRoute, val targetHost: String? = null)

    private val pushNotice = mutableStateOf<PushNotice?>(null)
    private var pushRouteJob: Job? = null
    private var pushRequest = 0L

    private fun openPush(route: StagingPushRoute, model: AppViewModel, confirmedHost: String? = null,
                         requestId: Long = ++pushRequest) {
        pushRouteJob?.cancel()
        pushNotice.value = null
        pushRouteJob = lifecycleScope.launch {
            fun unavailable(reason: String) {
                Log.w("NodeScopePush", reason)
                if (pushRequest == requestId) pushNotice.value = PushNotice(route)
            }

            try {
                val push = StagingPush.get(this@MainActivity)
                val selected = withTimeoutOrNull(12_000) { model.sourceStatus.first { it.host != null } }
                val selectedOrigin = selected?.host?.let { "https://$it/" }
                val origin = push.originForRoute(route, selectedOrigin)
                    ?: return@launch unavailable("Notification tap has no usable binding")
                val host = normalizeHost(origin)
                if (confirmedHost == null && origin != selectedOrigin) {
                    pushNotice.value = PushNotice(route, host)
                    return@launch
                }
                if (confirmedHost != null && confirmedHost != host) {
                    return@launch unavailable("Notification binding changed while switching")
                }

                val source = withTimeoutOrNull(12_000) {
                    model.sourceStatus.first { it.host == host && (it.ready || it.error != null) }
                }
                if (source == null || !source.ready || source.beacon != null) {
                    return@launch unavailable("Notification analyzer is not ready as CoreScope")
                }
                val node = push.nodeForRoute(route, origin)
                    ?: return@launch unavailable("Notification account or binding could not be verified")
                if (model.sourceStatus.value.session == source.session && pushRequest == requestId) {
                    model.openWatchedNode(node, source.session.id)
                } else {
                    unavailable("Notification source changed during account verification")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable("Notification route verification failed")
            }
        }
    }

    private fun confirmPushSwitch(notice: PushNotice, target: String, model: AppViewModel) {
        val origin = "https://$target/"
        val match = runCatching { StagingPush.get(this).originForRoute(notice.route, origin) }.getOrNull()
        if (match != origin) {
            Log.w("NodeScopePush", "Notification binding changed before source switch")
            pushNotice.value = PushNotice(notice.route)
            return
        }
        val requestId = pushRequest
        model.selectSource(target, onSaved = {
            if (requestId == pushRequest) openPush(notice.route, model, target, requestId)
        })
    }

    /** Links opened while NodeScope is already running (singleTop). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent, ViewModelProvider(this, AppViewModel.factory((application as NodeScopeApplication).container))[AppViewModel::class.java])
    }

    /** A nodescope:// link, or a saved-plans file opened (VIEW) or shared (SEND) to NodeScope. */
    private fun route(intent: android.content.Intent?, model: AppViewModel) {
        val pushed = StagingPush.route(intent)
        if (pushed != null) {
            openPush(pushed, model)
            return
        }
        when {
            intent == null -> Unit
            intent.action == android.content.Intent.ACTION_SEND -> model.openImport(
                androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java))
            intent.action == android.content.Intent.ACTION_VIEW && intent.data?.scheme in setOf("content", "file") -> model.openImport(intent.data)
            else -> model.openLink(intent.dataString)
        }
    }

    override fun onResume() {
        super.onResume()
        if (PushPlatform.enabled) lifecycleScope.launch {
            StagingPush.attempt { StagingPush.get(this@MainActivity).refresh(this@MainActivity) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NodeScopeApplication).container
        val appModel = ViewModelProvider(this, AppViewModel.factory(container))[AppViewModel::class.java]
        // A link that launched the app; not re-read after rotation or process recreation.
        if (savedInstanceState == null) route(intent, appModel)
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            val preferences by model.preferences.collectAsStateWithLifecycle()
            NodeScopeTheme(preferences?.appearance ?: Appearance.SYSTEM, backgroundTexture = preferences?.backgroundTexture ?: true) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.3f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                // Settings → Units, read by every screen that shows a distance.
                CompositionLocalProvider(LocalDistanceUnit provides (preferences?.distanceUnit ?: DistanceUnit.defaultUnit()),
                    LocalUriHandler provides remember { InAppUriHandler(this@MainActivity) }) {
                    pushNotice.value?.let { notice ->
                        val target = notice.targetHost
                        AlertDialog(
                            onDismissRequest = { pushNotice.value = null },
                            title = { Text(stringResource(R.string.staging_push_alert_title)) },
                            text = { Text(if (target == null) stringResource(R.string.staging_push_alert_unavailable)
                                else stringResource(R.string.staging_push_alert_source, target)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    pushNotice.value = null
                                    if (target != null) {
                                        confirmPushSwitch(notice, target, model)
                                    }
                                }) {
                                    Text(stringResource(if (target == null) android.R.string.ok else R.string.staging_push_switch_open))
                                }
                            },
                            dismissButton = {
                                if (target != null) TextButton(onClick = { pushNotice.value = null }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }
                    Surface(Modifier.fillMaxSize()) {
                        preferences?.let { NodeScopeApp(model, it) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
