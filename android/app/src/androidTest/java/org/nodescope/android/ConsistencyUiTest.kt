package org.nodescope.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.nodescope.android.core.design.NodeScopeTheme
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.core.network.LiveConnection
import org.nodescope.android.core.storage.AppPreferences
import org.nodescope.android.core.storage.Appearance
import org.nodescope.android.feature.observers.ObserverIdentityCard
import org.nodescope.android.feature.settings.SettingsScreen
import java.io.File

class ConsistencyUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun observerTechnicalDetailsRemainAvailable() {
        compose.setContent { NodeScopeTheme(Appearance.LIGHT) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ObserverIdentityCard(MeshObserver("observer-id-for-copy", "Neighborhood observer", iata = "SAT",
                        model = "Heltec V3", firmware = "1.2.3", packetCount = 1240, packetsLastHour = 47,
                        batteryMv = 4100, noiseFloor = -108.0), System.currentTimeMillis(), false, {})
                }
            }
        } }
        compose.onNodeWithText("All packets").assertIsDisplayed()
        compose.onNodeWithText("Heltec V3").assertDoesNotExist()
        screenshot("observer-summary")
        compose.onNodeWithText("Technical details").performClick()
        compose.onNodeWithText("Heltec V3").assertIsDisplayed()
        compose.onNodeWithText("observer-id-for-copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Observer actions").performScrollTo().performClick()
        compose.onNodeWithText("Copy observer ID").assertIsDisplayed()
        compose.onNodeWithText("Share observer link").assertIsDisplayed()
    }

    @Test fun pausedSettingsUsesAccurateStatusAndConsistentHeadings() {
        compose.setContent { NodeScopeTheme(Appearance.DARK) {
            Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                SettingsScreen(AppPreferences(), LiveConnection.PAUSED, null, null, {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Analyzer offline").assertDoesNotExist()
        compose.onNodeWithText("Live updates are paused").performScrollTo().assertIsDisplayed()
        screenshot("settings-dark")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
