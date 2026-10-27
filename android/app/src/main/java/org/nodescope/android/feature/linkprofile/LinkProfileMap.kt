package org.nodescope.android.feature.linkprofile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.feature.map.*

/** Persistent map: tentative taps are committed only by Use Point; cancel leaves inputs intact. */
@Composable
internal fun LinkProfileMap(endpoint: String?, pointA: ProfileCoordinate?, pointB: ProfileCoordinate?,
    nodes: List<org.nodescope.android.core.model.MeshNode>, onCancel: () -> Unit, onChoose: (ProfileCoordinate) -> Unit,
    modifier: Modifier = Modifier) {
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            var latitude by rememberSaveable(endpoint) { mutableStateOf<Double?>(null) }
            var longitude by rememberSaveable(endpoint) { mutableStateOf<Double?>(null) }
            val origin = pointA ?: pointB
            var camera by rememberSaveable { mutableStateOf(listOf(origin?.latitude ?: 0.0, origin?.longitude ?: 0.0, if (origin != null) 10.0 else 1.0, 0.0, 0.0)) }
            var retry by remember { mutableIntStateOf(0) }
            val engine = rememberMapLibreEngine()
            val map = engine.map
            // Selection state is recreated for each endpoint; the long-lived native listener
            // must invoke the latest setters after A advances to B.
            val onCoordinateTap by rememberUpdatedState<(Double, Double) -> Boolean>({ lat, lon ->
                if (endpoint != null) { latitude = lat; longitude = lon; true } else false
            })
            DisposableEffect(map) {
                val listener = MapLibreMap.OnMapClickListener { point -> onCoordinateTap(point.latitude, point.longitude) }
                map?.addOnMapClickListener(listener)
                onDispose { map?.removeOnMapClickListener(listener) }
            }
            LaunchedEffect(engine.overlays, latitude, longitude, pointA, pointB, nodes) {
                val a = pointA?.let { Coordinate.gps(it.latitude, it.longitude) }
                val b = pointB?.let { Coordinate.gps(it.latitude, it.longitude) }
                val tentative = latitude?.let { lat -> longitude?.let { lon -> Coordinate.gps(lat, lon) } }
                engine.overlays?.setNodes(nodeMarkers(nodes), grouped = true)
                engine.overlays?.setRouteAnchors(listOfNotNull(
                    a?.let { MapMarker(it, "repeater", name = "Point A") },
                    b?.let { MapMarker(it, "observer", name = "Point B") },
                    tentative?.let { MapMarker(it, "repeater", name = "Tentative location") }))
                engine.overlays?.setSampleRoute(if (a != null && b != null) listOf(a, b) else emptyList())
            }
            Text(if (endpoint != null) "Choose Point $endpoint" else "Link endpoints", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            if (endpoint != null) Text("Confirming a point sends its coordinates to Open-Meteo for elevation.",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MapLibreEngineHost(engine, if (MaterialTheme.colorScheme.background.luminance() < 0.3f) 2 else 0,
                    retry, 0, camera, { camera = it }, { tap ->
                        val node = nodes.firstOrNull { it.publicKey == tap.nodeKey }?.coordinate
                        if (endpoint != null && node != null) { latitude = node.latitude; longitude = node.longitude; true }
                        else { tap.expandGroup?.invoke(); tap.expandGroup != null }
                    }, Modifier.fillMaxSize())
                if (engine.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (engine.failed) Surface(Modifier.align(Alignment.Center)) {
                    TextButton(onClick = { retry++ }) { Text("Map failed to load. Retry") }
                }
            }
            val uri = LocalUriHandler.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { uri.openUri("https://carto.com/attributions") }) { Text("© CARTO") }
                TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap") }
            }
            if (endpoint != null) {
                Text(latitude?.let { String.format(java.util.Locale.getDefault(), "%.6f, %.6f", it, longitude) } ?: "Tap the map or a known node", Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = {
                        val lat = latitude
                        val lon = longitude
                        if (lat != null && lon != null) onChoose(ProfileCoordinate(lat, lon))
                    }, enabled = latitude != null && longitude != null) { Text("Use Point $endpoint") }
                }
            }
        }
    }
}
