package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import org.nodescope.android.core.design.meshCardColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NodePlannerScreen(onLinkProfile: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Plan before you deploy", style = MaterialTheme.typography.headlineSmall)
        Text("Evaluate proposed locations, terrain paths, and radio assumptions before installing a node.")
        Card(onClick = onLinkProfile, modifier = Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Link Profile", style = MaterialTheme.typography.titleLarge)
                Text("Estimate terrain clearance and modeled path loss between two locations.")
                Text("Open Tool", color = MaterialTheme.colorScheme.primary)
            }
        }
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Candidate Sites", style = MaterialTheme.typography.titleLarge)
                Text("Compare proposed node locations, potential links, and estimated coverage.")
                Text("Coming next", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Planner results are estimates based on terrain data and the assumptions you enter. Buildings, vegetation, interference, hardware variation, and weather can change real-world performance.", style = MaterialTheme.typography.bodySmall)
    }
}
