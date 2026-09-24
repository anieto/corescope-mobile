package org.nodescope.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
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
    /** Links opened while NodeScope is already running (singleTop). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ViewModelProvider(this, AppViewModel.factory((application as NodeScopeApplication).container))[AppViewModel::class.java]
            .openLink(intent.dataString)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NodeScopeApplication).container
        val appModel = ViewModelProvider(this, AppViewModel.factory(container))[AppViewModel::class.java]
        // A link that launched the app; not re-read after rotation or process recreation.
        if (savedInstanceState == null) appModel.openLink(intent?.dataString)
        setContent {
            val model: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            val preferences by model.preferences.collectAsStateWithLifecycle()
            NodeScopeTheme(preferences?.appearance ?: Appearance.SYSTEM) {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.3f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
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
