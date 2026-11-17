package org.nodescope.android.feature.candidatesites

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.*

/** Local tentative coordinates; neither holding the map nor Cancel modifies the caller's draft. */
@Composable
fun CandidateSiteMapPicker(latitude: Double?, longitude: Double?, close: () -> Unit,
                           confirm: (Double, Double) -> Unit) {
    val initial = remember { Coordinate.valid(latitude, longitude) }
    var selected by rememberSaveable { mutableStateOf(initial?.let { listOf(it.latitude, if (it.longitude == 180.0) -180.0 else it.longitude) }) }
    var camera by rememberSaveable { mutableStateOf(listOf(initial?.latitude ?: 0.0, initial?.longitude ?: 0.0, if (initial == null) 1.0 else 14.0, 0.0, 0.0)) }
    val context = LocalContext.current
    var choice by remember { mutableStateOf(loadMapProvider(context)) }
    var layersOpen by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
    val proposedSite = stringResource(R.string.planner_proposed_site)
    LaunchedEffect(engine.overlays, selected, proposedSite) {
        engine.overlays?.setPins(selected?.let {
            listOf(MapPin(Coordinate(it[0], it[1]), MapPin.Kind.TENTATIVE, proposedSite))
        }.orEmpty())
    }
    val place: (MapTap) -> Unit = { tap ->
        tap.coordinate?.let { coordinate ->
            Coordinate.valid(coordinate.latitude, coordinate.longitude)?.let {
                selected = listOf(it.latitude, if (it.longitude == 180.0) -180.0 else it.longitude)
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) }
                    Text(stringResource(R.string.planner_choose_site_location), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selected?.let { confirm(it[0], it[1]) } }, enabled = selected != null && !engine.failed) {
                        Text(stringResource(R.string.planner_use_coordinates))
                    }
                }
                Text(stringResource(R.string.planner_touch_and_hold_to_place_a_tentative_pin), Modifier.padding(12.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (engine) {
                        is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), retry, 0, camera,
                            { camera = it }, { false }, Modifier.fillMaxSize(), onLongPress = place)
                        is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, 0, camera,
                            { camera = it }, { false }, Modifier.fillMaxSize(), hidesBusinesses = true, onLongPress = place)
                    }
                    Box(Modifier.align(Alignment.TopEnd)) {
                        TextButton(onClick = { layersOpen = true }) { Text(stringResource(R.string.planner_map_layers)) }
                        MapLayersMenu(layersOpen, { layersOpen = false }, choice, dark) {
                            choice = it; saveMapProvider(context, it); layersOpen = false
                        }
                    }
                    if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (engine.failed) Surface(Modifier.align(Alignment.Center)) {
                        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.planner_map_failed_to_load_retry)) }
                    }
                    val uri = LocalUriHandler.current
                    if (engine is MapLibreEngine) Surface(Modifier.align(Alignment.BottomEnd)) {
                        Row {
                            TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text(stringResource(R.string.common_carto_attribution), style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text(stringResource(R.string.common_openstreetmap_attribution), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected?.let { "${it[0]}, ${it[1]}" } ?: stringResource(R.string.planner_no_location_selected), fontFamily = if (selected != null) MonaspaceNeon else null)
                    Text(stringResource(R.string.planner_use_coordinates_updates_the_site_draft_apply_the), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
