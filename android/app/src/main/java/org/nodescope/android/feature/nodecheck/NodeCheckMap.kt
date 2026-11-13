package org.nodescope.android.feature.nodecheck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.nodescope.android.core.model.Coordinate
import org.nodescope.android.core.model.HeardObserver
import org.nodescope.android.core.model.MeshObserver
import org.nodescope.android.feature.map.*

/** The dot colours: the map's own companion green and room blue, as iOS uses green and blue. */
private val DirectGreen = Color(0xFF45C99D)
private val RelayedBlue = Color(0xFF299EFF)

/**
 * The observers that heard a live test's advert: green when they heard it directly, blue
 * through repeaters. Drawn by whichever engine the live map uses (MapLibre or Google), framed
 * on the observers as they arrive. Touch is off so the page scrolls over it, as on iOS.
 */
@Composable
internal fun NodeCheckMap(heard: List<HeardObserver>, observers: Map<String, MeshObserver>, modifier: Modifier = Modifier) {
    val points = remember(heard, observers) {
        heard.mapNotNull { entry -> observers[entry.observerId]?.coordinate?.let { entry to it } }
    }
    if (points.isEmpty()) return
    val context = LocalContext.current
    val choice = remember { loadMapProvider(context) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val density = LocalDensity.current.density
    val engine: MapEngineState = if (choice.provider == MapProvider.GOOGLE) rememberGoogleMapEngine() else rememberMapLibreEngine()
    var camera by remember { mutableStateOf<SavedCamera>(listOf(points[0].second.latitude, points[0].second.longitude, 8.0, 0.0, 0.0)) }

    // Route-node anchors draw as filled role-coloured dots on both engines; companion is green
    // and room is blue, matching the legend below.
    LaunchedEffect(engine.overlays, points) {
        engine.overlays?.setRouteAnchors(points.map { (entry, at) ->
            MapMarker(at, if (entry.hops == 0) "companion" else "room", name = entry.name)
        })
    }
    LaunchedEffect(engine.camera, points.size) {
        val ready = engine.camera ?: return@LaunchedEffect
        val coordinates: List<Coordinate> = points.map { it.second }
        val inset = (28 * density).toInt()
        if (coordinates.size == 1) ready.animate(CameraMove.Center(coordinates[0], 9.0))
        else ready.animate(CameraMove.Fit(boundsOf(coordinates), FramingInsets(inset, inset, inset, inset)))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.small)
            .semantics { contentDescription = "Map of ${points.size} observers that heard this node" }) {
            when (engine) {
                is MapLibreEngine -> MapLibreEngineHost(engine, choice.cartoStyle(dark), 0, 0, camera, { camera = it }, { false }, Modifier.fillMaxSize())
                is GoogleMapEngine -> GoogleMapEngineHost(engine, choice.googleType, dark, 0, camera, { camera = it }, { false }, Modifier.fillMaxSize(),
                    hidesBusinesses = true)
            }
            // Takes every touch before the map does, without consuming it, so drags scroll the page.
            Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
            // Google draws its own logo and attribution; CARTO's is credited here, above the touch layer.
            if (engine is MapLibreEngine) {
                val uri = LocalUriHandler.current
                Row(Modifier.align(Alignment.BottomEnd)) {
                    val style = MaterialTheme.typography.labelSmall
                    TextButton(onClick = { uri.openUri("https://carto.com/attributions") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("© CARTO", style = style)
                    }
                    TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("© OpenStreetMap", style = style)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(DirectGreen, "Heard it directly")
            Legend(RelayedBlue, "Through repeaters")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
