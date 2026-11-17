package org.nodescope.android.feature.linkprofile

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
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
fun NodePlannerScreen(onLinkProfile: () -> Unit, onSavedPlans: () -> Unit, onEstimatedCoverage: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.link_profile_plan_before_you_deploy), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.link_profile_evaluate_proposed_locations_terrain_paths_and_radio_assumptions))
        Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NavigationRow(stringResource(R.string.link_profile_link_profile), stringResource(R.string.link_profile_estimate_terrain_clearance_and_modeled_path_loss_between), Icons.Outlined.Route, onLinkProfile)
                HorizontalDivider()
                NavigationRow(stringResource(R.string.link_profile_estimated_coverage), stringResource(R.string.link_profile_set_up_an_area_study_and_review_known), Icons.Outlined.AddLocationAlt, onEstimatedCoverage,
                    badge = stringResource(R.string.link_profile_beta))
                HorizontalDivider()
                NavigationRow(stringResource(R.string.link_profile_saved_plans), stringResource(R.string.link_profile_reopen_saved_link_profiles_coverage_studies_and_candidate), Icons.Outlined.AddLocationAlt, onSavedPlans)
            }
        }
        Text(stringResource(R.string.link_profile_planner_results_are_estimates_based_on_terrain_data), style = MaterialTheme.typography.bodySmall)
    }
}
