package org.nodescope.android.feature.coverage

import org.nodescope.android.core.design.UiText
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.design.MonaspaceNeon

internal fun CoverageStudyRunFailure.coverageMessage(): UiText = when (this) {
    CoverageStudyRunFailure.network -> UiText.Resource(R.string.coverage_terrain_request_failed_check_your_connection_and_retry)
    CoverageStudyRunFailure.terrain -> UiText.Resource(R.string.coverage_required_terrain_is_missing_changed_or_invalid_retry)
    CoverageStudyRunFailure.storage -> UiText.Resource(R.string.coverage_terrain_cache_could_not_be_prepared_free_device)
    CoverageStudyRunFailure.limits -> UiText.Resource(R.string.coverage_this_study_exceeds_the_download_or_calculation_budget)
    else -> UiText.Resource(R.string.coverage_calculation_could_not_produce_a_valid_result_review)
}

internal fun CoverageNodeStatus.coverageLabel(): UiText = when (reason) {
    CoverageNodeReason.outsideRadius -> UiText.Resource(R.string.coverage_outside_study_radius)
    CoverageNodeReason.processingLimit -> UiText.Resource(R.string.coverage_not_evaluated_work_limit)
    CoverageNodeReason.pathWorkLimit -> UiText.Resource(R.string.coverage_unknown_individual_path_work_limit)
    CoverageNodeReason.missingTerrain -> UiText.Resource(R.string.coverage_unknown_missing_terrain)
    CoverageNodeReason.unsupportedPath -> UiText.Resource(R.string.coverage_unknown_unsupported_path)
    CoverageNodeReason.accuracyUnresolved -> UiText.Resource(R.string.coverage_unknown_sampling_did_not_converge)
    CoverageNodeReason.thresholdUnresolved -> UiText.Resource(R.string.coverage_unknown_too_close_to_threshold)
    else -> when (state) {
        CoverageNodeState.inCoverage -> if ((adjustedMarginDb ?: 0.0) <= 10) UiText.Resource(R.string.coverage_predicted_above_threshold_limited_headroom)
            else UiText.Resource(R.string.coverage_predicted_above_threshold_greater_headroom)
        CoverageNodeState.belowThreshold -> UiText.Resource(R.string.coverage_predicted_below_threshold)
        CoverageNodeState.unknown -> UiText.Resource(R.string.coverage_unknown)
        CoverageNodeState.stale -> UiText.Resource(R.string.coverage_changed_calculate_again)
        CoverageNodeState.notEvaluated -> UiText.Resource(R.string.coverage_not_evaluated_3)
    }
}

@Composable internal fun CoverageResults(result: CoverageStudyResult) {
    val uri = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    val counts = remember(result.executionID) {
        val qualified = result.area.cells.filter { it.state == CoverageAreaState.qualified }
        val above = qualified.count { (it.marginDb ?: Double.NEGATIVE_INFINITY) >= 0 }
        Triple(above, qualified.size - above, result.area.cells.count {
            it.state !in listOf(CoverageAreaState.qualified, CoverageAreaState.nearSite, CoverageAreaState.outsideRadius)
        })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.coverage_estimated_result), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.coverage_above_threshold_below_unresolved_samples, counts.first, counts.second, counts.third), fontFamily = MonaspaceNeon)
        if (result.area.workLimited) Text(stringResource(R.string.coverage_calculation_limits_left_some_paths_unresolved_paths_that),
            style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFFF9500))
        Text(stringResource(R.string.coverage_predicted_margin_after_reserve_red_below_0_db), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.coverage_this_is_a_sampled_estimate_not_measured_reception), style = MaterialTheme.typography.bodySmall)
        result.model?.let {
            Text(it.scenario, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coverage_model_revision, it.sourceRevision), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelSmall)
        }
        result.terrainSources?.let { sources ->
            Text(pluralStringResource(R.plurals.coverage_terrain_tiles_egm96_heights, sources.descriptors.size, sources.descriptors.size), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.coverage_mapzen_aws_skadi_uses_mixed_regional_elevation_sources), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/tilezen/joerd/blob/master/docs/attribution.md") }) { Text(stringResource(R.string.coverage_terrain_source_credits)) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.coverage_hide_terrain_provenance) else stringResource(R.string.coverage_terrain_provenance)) }
            if (expanded) sources.descriptors.forEach { source ->
                Text(source.tile, fontFamily = MonaspaceNeon)
                Text(source.url, style = MaterialTheme.typography.labelSmall)
                Text(source.sourceETag ?: source.compressedSHA256.orEmpty(), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
