package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.feature.settings.NavigationRow

/** The tools listed as rows, the same row Explore uses to open this screen. */
@Composable
fun NodePlannerScreen(onLinkProfile: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Plan before you deploy", style = MaterialTheme.typography.headlineSmall)
        Text("Evaluate proposed locations, terrain paths, and radio assumptions before installing a node.")
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NavigationRow("Link Profile", "Estimate terrain clearance and modeled path loss between two locations.", Icons.Outlined.Route, onLinkProfile)
                HorizontalDivider()
                ComingSoonRow("Candidate Sites", "Compare proposed node locations, potential links, and estimated coverage.")
            }
        }
        Text("Planner results are estimates based on terrain data and the assumptions you enter. Buildings, vegetation, interference, hardware variation, and weather can change real-world performance.", style = MaterialTheme.typography.bodySmall)
    }
}

/** [NavigationRow]'s layout, greyed, with "Coming soon" in place of the chevron. */
@Composable
private fun ComingSoonRow(title: String, value: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).background(muted.copy(alpha = 0.11f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AddLocationAlt, null, Modifier.size(18.dp), tint = muted)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = muted)
            Text(value, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text("Coming soon", style = MaterialTheme.typography.labelMedium, color = muted)
    }
}
