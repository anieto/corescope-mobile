package org.nodescope.android.feature.coverage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.design.MonaspaceNeon

internal fun CoverageStudyRunFailure.coverageMessage(): String = when (this) {
    CoverageStudyRunFailure.network -> "Terrain request failed. Check your connection and retry; the provider may be temporarily unavailable."
    CoverageStudyRunFailure.terrain -> "Required terrain is missing, changed or invalid. Retry, or choose another location. Missing heights are never replaced with sea level."
    CoverageStudyRunFailure.storage -> "Terrain cache could not be prepared. Free device storage or wait for another calculation to finish, then retry with a smaller radius."
    CoverageStudyRunFailure.limits -> "This study exceeds the download or calculation budget. Choose a smaller radius or wider sample spacing."
    else -> "Calculation could not produce a valid result. Review radio inputs and try a smaller area. No partial result was published."
}

internal fun CoverageNodeStatus.coverageLabel(): String = when (reason) {
    CoverageNodeReason.outsideRadius -> "Outside study radius"
    CoverageNodeReason.processingLimit -> "Not evaluated — work limit"
    CoverageNodeReason.missingTerrain -> "Unknown — missing terrain"
    CoverageNodeReason.unsupportedPath -> "Unknown — unsupported path"
    CoverageNodeReason.accuracyUnresolved -> "Unknown — sampling did not converge"
    CoverageNodeReason.thresholdUnresolved -> "Unknown — too close to threshold"
    else -> when (state) {
        CoverageNodeState.inCoverage -> if ((adjustedMarginDb ?: 0.0) <= 10) "Predicted above threshold — limited headroom"
            else "Predicted above threshold — greater headroom"
        CoverageNodeState.belowThreshold -> "Predicted below threshold"
        CoverageNodeState.unknown -> "Unknown"
        CoverageNodeState.stale -> "Changed — calculate again"
        CoverageNodeState.notEvaluated -> "Not evaluated"
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
        Text("Estimated result", style = MaterialTheme.typography.titleMedium)
        Text("${counts.first} above threshold · ${counts.second} below · ${counts.third} unresolved samples", fontFamily = MonaspaceNeon)
        if (result.area.workLimited) Text("Calculation limits left some paths unresolved. Paths that reach their individual limit may need a smaller radius or wider sample spacing. Unresolved cells are not predictions of poor reception.",
            style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFFF9500))
        Text("Predicted margin after reserve: red below 0 dB; amber 0–10 dB; green above 10 dB. Gray hatching means no prediction, including the unsupported 250 m near-site area. Outside the study is unshaded. Green does not establish reachability or a working return link.", style = MaterialTheme.typography.bodySmall)
        Text("This is a sampled estimate, not measured reception or a guarantee. Gaps and work-limited areas remain unknown. The nearest 200 available nodes use the same assumed receiver; the catalog is incomplete.", style = MaterialTheme.typography.bodySmall)
        result.model?.let {
            Text(it.scenario, style = MaterialTheme.typography.bodySmall)
            Text("Model revision: ${it.sourceRevision}", fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelSmall)
        }
        result.terrainSources?.let { sources ->
            Text("${sources.descriptors.size} terrain tiles · EGM96 heights", style = MaterialTheme.typography.bodySmall)
            Text("Mapzen / AWS Skadi uses mixed regional elevation sources; trees and buildings may remain in the terrain surface.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/tilezen/joerd/blob/master/docs/attribution.md") }) { Text("Terrain source credits") }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide terrain provenance" else "Terrain provenance") }
            if (expanded) sources.descriptors.forEach { source ->
                Text(source.tile, fontFamily = MonaspaceNeon)
                Text(source.url, style = MaterialTheme.typography.labelSmall)
                Text(source.sourceETag ?: source.compressedSHA256.orEmpty(), fontFamily = MonaspaceNeon, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
