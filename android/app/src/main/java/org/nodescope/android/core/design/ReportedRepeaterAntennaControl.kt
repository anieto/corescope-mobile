package org.nodescope.android.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.model.RepeaterMetadata
import org.nodescope.android.core.model.ReportedAntennaSource
import org.nodescope.android.core.model.ReportedRepeaterSelection
import org.nodescope.android.core.model.antennaSource

@Composable
internal fun ReportedRepeaterAntennaControl(
    selection: ReportedRepeaterSelection?,
    minimumHeight: Double = 0.0,
    load: suspend (ReportedRepeaterSelection) -> RepeaterMetadata?,
    applyHeight: (Double, ReportedAntennaSource) -> Unit,
    applyGain: (Double, ReportedAntennaSource) -> Unit,
) {
    var metadata by remember(selection) { mutableStateOf<RepeaterMetadata?>(null) }
    LaunchedEffect(selection) { metadata = selection?.let { load(it) } }
    val reported = metadata ?: return
    val host = selection?.analyzerHost ?: return
    val gain = reported.applicableGainDbi
    val height = reported.applicableHeightMeters(minimumHeight)
    if (height == null && gain == null) return

    OutlinedButton(onClick = {
        val source = reported.antennaSource(host)
        height?.let { applyHeight(it, source) }
        gain?.let { applyGain(it, source) }
    }) {
        Text(stringResource(R.string.repeater_use_values))
    }
    val numbers = java.text.NumberFormat.getNumberInstance()
    if (height != null) {
        val unit = LocalDistanceUnit.current
        val value = org.nodescope.android.feature.linkprofile.profileDisplayHeight(height, unit)
        val display = stringResource(if (unit.usesFeet) R.string.repeater_feet else R.string.repeater_meters, numbers.format(value))
        Text(stringResource(R.string.repeater_reported_height_agl, display), style = MaterialTheme.typography.bodySmall)
    }
    if (gain != null) {
        Text(stringResource(R.string.repeater_reported_gain, numbers.format(gain)),
            style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
internal fun reportedAntennaSourceText(height: Boolean, gain: Boolean): String? = when {
    height && gain -> stringResource(R.string.repeater_antenna_applied)
    height -> stringResource(R.string.repeater_height_applied)
    gain -> stringResource(R.string.repeater_gain_applied)
    else -> null
}

internal val reportedStatuses = listOf("active", "maintenance", "decommissioned")

internal fun reportedStatusLabel(status: String): Int = when (status) {
    "active" -> R.string.repeater_reported_active
    "maintenance" -> R.string.repeater_reported_maintenance
    else -> R.string.repeater_reported_decommissioned
}

@Composable
internal fun ReportedStatusFilter(selection: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(stringResource(selection?.let(::reportedStatusLabel) ?: R.string.repeater_status_filter))
        }
        androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + reportedStatuses).forEach { status ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(status?.let(::reportedStatusLabel) ?: R.string.repeater_any_status)) },
                    onClick = { onSelect(status); expanded = false },
                )
            }
        }
    }
}
