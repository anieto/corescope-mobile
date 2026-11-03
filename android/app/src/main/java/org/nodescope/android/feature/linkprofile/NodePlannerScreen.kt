package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.feature.settings.NavigationRow

/** The tools listed as rows, the same row Explore uses to open this screen. */
@Composable
fun NodePlannerScreen(onLinkProfile: () -> Unit, onCandidateSites: () -> Unit, onEstimatedCoverage: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Plan before you deploy", style = MaterialTheme.typography.headlineSmall)
        Text("Evaluate proposed locations, terrain paths, and radio assumptions before installing a node.")
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NavigationRow("Link Profile", "Estimate terrain clearance and modeled path loss between two locations.", Icons.Outlined.Route, onLinkProfile)
                HorizontalDivider()
                NavigationRow("Estimated Coverage", "Set up an area study and review known nodes around a proposed transmitter.", Icons.Outlined.AddLocationAlt, onEstimatedCoverage)
                HorizontalDivider()
                NavigationRow("Candidate Sites", "Save proposed locations and equipment assumptions in deployment plans.", Icons.Outlined.AddLocationAlt, onCandidateSites)
            }
        }
        Text("Planner results are estimates based on terrain data and the assumptions you enter. Buildings, vegetation, interference, hardware variation, and weather can change real-world performance.", style = MaterialTheme.typography.bodySmall)
    }
}
