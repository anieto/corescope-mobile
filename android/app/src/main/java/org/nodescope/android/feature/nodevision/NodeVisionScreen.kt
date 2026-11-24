package org.nodescope.android.feature.nodevision

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import org.nodescope.android.core.design.MeshModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import org.nodescope.android.core.model.MeshNode
import org.nodescope.android.core.model.RepeaterMetadata
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.design.nodeRoleLabel
import org.nodescope.android.core.design.relativeTime
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.location.altitude.AltitudeConverter
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import org.nodescope.android.feature.coverage.CoverageHeightPreset
import org.nodescope.android.feature.linkprofile.LinkTerrainProviding
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.nodescope.android.feature.linkprofile.LinkTerrainProvider
import org.nodescope.android.feature.linkprofile.ProfileCoordinate
import android.provider.Settings
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import org.nodescope.android.BuildConfig
import org.nodescope.android.R
import org.nodescope.android.core.design.ActivityAmber
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.LocalDistanceUnit
import org.nodescope.android.core.design.formatDistance
import org.nodescope.android.core.model.FieldCoordinate
import org.nodescope.android.core.model.FieldGeometry
import org.nodescope.android.core.model.FieldGeometryResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private val locationPermissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeVisionScreen(
    name: String? = null,
    target: FieldCoordinate? = null,
    antennaHeight: Double? = null,
    initialStatus: String? = null,
    targetRole: String? = null,
    catalog: List<MeshNode> = emptyList(),
    catalogIsLoading: Boolean = false,
    loadMetadata: suspend () -> Map<String, RepeaterMetadata> = { emptyMap() },
    openLinkProfile: ((FieldCoordinate, MeshNode) -> Unit)? = null,
    nodeDetails: (@Composable (MeshNode, onDismiss: () -> Unit) -> Unit)? = null,
    close: () -> Unit,
) {
    val discovery = target == null
    val showsCatalogLoading = discovery && catalog.isEmpty() && catalogIsLoading
    var inspectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showsNearby by rememberSaveable { mutableStateOf(false) }
    var showsLocationInfo by rememberSaveable { mutableStateOf(false) }
    var expandsInspection by rememberSaveable { mutableStateOf(false) }
    var repeaterMetadata by remember { mutableStateOf<Map<String, RepeaterMetadata>>(emptyMap()) }
    LaunchedEffect(discovery) {
        if (discovery) {
            repeaterMetadata = try {
                loadMetadata()
            } catch (_: IOException) {
                emptyMap()
            }
        }
    }
    val inspectedNode = catalog.firstOrNull { it.publicKey == inspectedKey }
    val focusedNode = catalog.firstOrNull { it.publicKey == focusedKey }
    val visionTarget = focusedNode?.visionCoordinate() ?: target
    val visionName = focusedNode?.displayName ?: name.orEmpty()
    val reportedHeight = focusedNode?.let { repeaterMetadata[it.publicKey.lowercase()]?.applicableHeightMeters() } ?: antennaHeight
    val visionHeight = visionAntennaHeight(focusedNode?.role ?: targetRole, reportedHeight)
    val ownerStatus = when (focusedNode?.let { repeaterMetadata[it.publicKey.lowercase()]?.status } ?: initialStatus) {
        "maintenance" -> R.string.nodevision_owner_maintenance
        "decommissioned" -> R.string.nodevision_owner_decommissioned
        else -> null
    }
    val context = LocalContext.current
    val distanceUnit = LocalDistanceUnit.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var asked by rememberSaveable { mutableStateOf(false) }
    var permissionUpdate by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionUpdate++
    }
    val cameraGranted = remember(active, permissionUpdate) { granted(Manifest.permission.CAMERA) }
    val locationGranted = remember(active, permissionUpdate) { locationPermissions.any(::granted) }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            val missing = buildList {
                if (!cameraGranted) add(Manifest.permission.CAMERA)
                if (!locationGranted) addAll(locationPermissions)
            }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        }
    }

    val detailedNode = catalog.firstOrNull { it.publicKey == detailedKey }
    val liveView = active && !showsNearby && !showsLocationInfo && detailedNode == null
    val needsLocation = active && locationGranted && (liveView || showsNearby)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(needsLocation) {
        while (needsLocation) {
            now = SystemClock.elapsedRealtime()
            delay(750)
        }
    }
    val readings = rememberVisionReadings(needsLocation, liveView && locationGranted && cameraGranted)
    val fix = readings.fix?.takeIf { now - it.elapsedRealtimeNanos / 1_000_000 <= 15_000 }
    val reference = fix?.let { FieldCoordinate.create(it.latitude, it.longitude) }
    val geometry = reference?.let { from -> visionTarget?.let { FieldGeometry.geometry(from, it) } }
    val discoveryNodes = remember(discovery, reference, catalog, repeaterMetadata) {
        if (!discovery || reference == null) emptyList() else catalog.mapNotNull { node ->
            if (repeaterMetadata[node.publicKey.lowercase()]?.status == "decommissioned") return@mapNotNull null
            val coordinate = node.visionCoordinate() ?: return@mapNotNull null
            val field = FieldGeometry.geometry(reference, coordinate)
            NearbyVisionNode(node, field)
        }.sortedWith(compareBy({ it.geometry.distanceMeters }, { it.node.publicKey }))
    }
    val nearbyRadius = if (distanceUnit.usesMiles) 16_093.44 else 10_000.0
    val nearby = remember(discoveryNodes, nearbyRadius) {
        discoveryNodes.takeWhile { it.geometry.distanceMeters <= nearbyRadius }
    }
    val near = geometry?.let { it.distanceMeters <= maxOf(5.0, (fix?.accuracy?.toDouble() ?: 0.0)) } ?: false
    val heading = readings.heading?.takeIf { now - it.receivedAt <= 2_000 }
    val declination = fix?.let {
        GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), it.time).declination
    }
    val trueHeading = if (heading != null && declination != null) heading.degrees + declination else null

    val spotlightPoint = visionTarget?.let { VisionTerrainPoint(focusedNode?.publicKey.orEmpty().lowercase(), it) }
    val terrainPoints = remember(nearby, spotlightPoint) {
        buildSet {
            nearby.mapNotNullTo(this) { it.node.terrainPoint() }
            spotlightPoint?.let { add(it) }
        }
    }
    val terrain = remember(context) {
        VisionTerrainCache(LinkTerrainProvider(File(context.cacheDir, "nodevision-elevation.json")))
    }
    var ground by remember(terrain) { mutableStateOf<Map<VisionTerrainPoint, Double>>(emptyMap()) }
    LaunchedEffect(terrain, terrainPoints) { ground = terrain.resolve(terrainPoints) }
    val antennaElevation = spotlightPoint?.let(ground::get)?.plus(visionHeight.meters)

    var altitude by remember { mutableStateOf<VisionAltitude?>(null) }
    LaunchedEffect(fix, active) {
        if (fix == null || !active || Build.VERSION.SDK_INT < 34) {
            altitude = null
            return@LaunchedEffect
        }
        altitude = try {
            val current = withContext(Dispatchers.IO) { meanSeaLevelAltitude(context, fix) }
            val previous = altitude
            if (current != null && previous != null && current.accuracy <= 15 && previous.accuracy <= 15 &&
                current.fixTime - previous.fixTime in 0..5_000) {
                current.copy(meters = previous.meters + (current.meters - previous.meters) * 0.3)
            } else current
        } catch (_: IOException) {
            null
        }
    }

    var displayedAltitude by remember { mutableStateOf<Double?>(null) }
    var optics by remember { mutableStateOf<VisionOptics?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var showsDiagnostics by rememberSaveable { mutableStateOf(false) }
    var verticalArrowShown by remember { mutableStateOf(false) }
    var edge by remember { mutableStateOf(false) }
    var truncatedName by remember(visionName) { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    val accent = ActivityAmber
    val overlayBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    val onSurface = MaterialTheme.colorScheme.onSurface
    val lightOverlay = surface.luminance() >= 0.5f
    val supportingText = lerp(onSurface, surface, if (lightOverlay) 0.10f else 0.22f)
    val density = LocalDensity.current
    val markerShape = remember(density) {
        with(density) { visionMarkerShape(10.dp.toPx(), 9.dp.toPx(), 8.dp.toPx()) }
    }
    val blue = MaterialTheme.colorScheme.primary
    val markerInk = if (lightOverlay) lerp(blue, onSurface, 0.25f) else blue
    val overlayTint = if (lightOverlay) {
        Brush.verticalGradient(listOf(blue.copy(alpha = 0.16f), blue.copy(alpha = 0.07f)))
    } else {
        Brush.linearGradient(listOf(blue.copy(alpha = 0.18f), blue.copy(alpha = 0f)))
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val showCamera = liveView && cameraGranted && !cameraFailed
        DisposableEffect(dialogView, showCamera) {
            val previous = dialogView.keepScreenOn
            if (showCamera) dialogView.keepScreenOn = true
            onDispose { dialogView.keepScreenOn = previous }
        }
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = lightOverlay
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(surface).clipToBounds()) {
            if (liveView && cameraGranted) {
                VisionCamera(Modifier.matchParentSize()) { result, failed ->
                    optics = result
                    cameraFailed = failed
                }
            }
            val paneWidth = maxWidth
            val previewCardHeight = maxHeight * 0.52f
            val projection = optics?.takeIf { it.width == constraints.maxWidth && it.height == constraints.maxHeight }
            val tangent = projection?.halfWidthTangent
            val bearing = geometry?.initialBearingDegrees
            val offset = if (!near && bearing != null && trueHeading != null && tangent != null && projection != null) {
                FieldGeometry.cameraHorizontalOffset(bearing, trueHeading, tangent)?.plus(projection.centerX * 2 - 1)
            } else null
            val markerWidth = minOf(visionMarkerWidth(visionMarkerTier(geometry?.distanceMeters ?: 0.0)).dp,
                (paneWidth - 32.dp).coerceAtLeast(0.dp))
            val pixel = (offset ?: 0.0) * paneWidth.value / 2
            val limit = (paneWidth.value / 2 - markerWidth.value / 2 - 8).coerceAtLeast(0f)
            val offscreen = abs(pixel) > (limit + if (edge) -6 else 6).coerceAtLeast(0f)
            val showSettings = !cameraGranted || !locationGranted
            val status = when {
                !cameraGranted -> R.string.nodevision_camera_permission
                cameraFailed -> R.string.nodevision_unavailable
                !locationGranted -> R.string.nodevision_location_denied
                near -> R.string.nodevision_near
                fix == null -> R.string.nodevision_waiting_location
                heading == null && readings.stabilizingHeading -> R.string.nodevision_finding_heading
                heading == null -> R.string.nodevision_hold_upright
                else -> null
            }
            val verticalTangent = projection?.halfHeightTangent
            val targetElevation = antennaElevation
            val phoneAltitude = altitude?.takeIf { now - it.fixTime <= 3_000 }
            val nearDistance = maxOf(5.0, fix?.accuracy?.toDouble() ?: 0.0)
            val distances = nearby.map { it.geometry.distanceMeters } + listOfNotNull(geometry?.distanceMeters)
            val closestDistance = distances.filter { it > nearDistance }.minOrNull()
            val stableAltitude = stableVisionAltitude(displayedAltitude, phoneAltitude?.meters, closestDistance)
            SideEffect { displayedAltitude = stableAltitude }
            val mode = visionElevationMode(stableAltitude, phoneAltitude?.accuracy, distances, nearDistance)
            val markerOffset = if (geometry != null && heading != null && verticalTangent != null && verticalTangent > 0) {
                mode.offset(geometry.distanceMeters, targetElevation, heading.pitch, verticalTangent)
            } else null
            val verticalOffset = markerOffset.takeUnless { mode == VisionElevationMode.Guide }
            val approximate = markerOffset != null && geometry != null &&
                mode.isApproximate(geometry.distanceMeters, visionHeight)
            val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            val minimumY = safeTop + 166.dp
            val maximumY = maxHeight - safeBottom - 100.dp
            val projectedY = markerOffset?.let { maxHeight * ((projection?.centerY ?: 0.5) - it / 2).toFloat() }
            val markerY = if (projectedY != null && maximumY >= minimumY) {
                projectedY.coerceIn(minimumY, maximumY)
            } else maxHeight * 0.43f
            val buffer = if (verticalArrowShown) (-6).dp else 6.dp
            val verticalEdge = when {
                verticalOffset == null || projectedY == null || maximumY < minimumY -> null
                projectedY < minimumY - buffer -> R.string.nodevision_above
                projectedY > maximumY + buffer -> R.string.nodevision_below
                else -> null
            }
            val useVerticalArrow = verticalEdge != null && (!offscreen || abs(verticalOffset ?: 0.0) > abs(offset ?: 0.0))
            val markerVisible = markerOffset != null && showCamera && offset != null && !offscreen && verticalEdge == null
            val discoveryMarkers = if (discovery && showCamera && fix != null && projection != null &&
                trueHeading != null && heading != null && verticalTangent != null && maximumY >= minimumY) {
                discoveryMarkers(discoveryNodes, inspectedKey, focusedKey, fix.accuracy.toDouble(),
                    trueHeading, projection, paneWidth.value.toDouble(), maxHeight.value.toDouble(),
                    heading.pitch, mode,
                    antenna = { node ->
                        val height = visionAntennaHeight(node.role,
                            repeaterMetadata[node.publicKey.lowercase()]?.applicableHeightMeters())
                        node.terrainPoint()?.let(ground::get)?.plus(height.meters) to height
                    },
                    if (markerVisible) paneWidth.value / 2 + pixel else null, markerY.value.toDouble(),
                    markerWidth.value.toDouble(), minimumY.value.toDouble(), maximumY.value.toDouble())
            } else emptyList()
            val retainedMarkers = remember(mode == VisionElevationMode.Guide) { mutableStateMapOf<String, DiscoveryMarker>() }
            val visibleMarkers = discoveryMarkers.associateBy { it.node.publicKey }
            SideEffect { retainedMarkers.putAll(visibleMarkers) }
            (retainedMarkers.keys + visibleMarkers.keys).forEach { markerKey ->
                key(markerKey, mode == VisionElevationMode.Guide) {
                    val marker = visibleMarkers[markerKey] ?: retainedMarkers.getValue(markerKey)
                    val visible = markerKey in visibleMarkers
                    val visibility = remember { MutableTransitionState(false) }
                    visibility.targetState = visible
                    if (!visible && visibility.isIdle && !visibility.currentState) {
                        SideEffect { retainedMarkers.remove(markerKey) }
                    }
                    val selected = marker.node.publicKey == inspectedKey
                    AnimatedVisibility(
                        visibleState = visibility,
                        modifier = Modifier.offset(x = (marker.x - marker.width / 2).dp, y = (marker.y - 80).dp),
                        enter = fadeIn(tween(240)),
                        exit = fadeOut(tween(240)),
                    ) {
                        Box(Modifier.width(marker.width.dp).height(80.dp), contentAlignment = Alignment.BottomCenter) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = visible,
                                onClick = {
                                    inspectedKey = marker.node.publicKey
                                    expandsInspection = false
                                },
                                shape = markerShape,
                                color = surface.copy(alpha = 0.98f),
                                shadowElevation = 4.dp,
                            ) {
                                Column(Modifier.background(overlayTint)
                                    .visionMarkerBorder(markerShape, markerInk, surface, marker.approximate, selected)
                                    .padding(bottom = 9.dp).padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(marker.node.displayName, fontFamily = MonaspaceNeon,
                                        style = if (marker.tier == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                                        color = blue, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    Text(stringResource(R.string.nodevision_distance_away, formatDistance(marker.distance / 1000, distanceUnit)),
                                        style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon,
                                        textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
            val showsTargetCard = visionTarget != null && inspectedNode == null &&
                (!markerVisible || truncatedName || status != null || showSettings || reportedHeight != null || ownerStatus != null)
            var targetCardTop by remember { mutableStateOf<Dp?>(null) }
            var discoveryControlsTop by remember { mutableStateOf<Dp?>(null) }
            SideEffect { verticalArrowShown = showCamera && offset != null && verticalEdge != null }
            if (showCamera && offset != null) {
                SideEffect { edge = offscreen }
                if (offscreen || verticalEdge != null) {
                    val above = verticalEdge == R.string.nodevision_above
                    val icon = when {
                        useVerticalArrow && above -> Icons.Default.KeyboardArrowUp
                        useVerticalArrow -> Icons.Default.KeyboardArrowDown
                        offset < 0 -> Icons.Default.ChevronLeft
                        else -> Icons.Default.ChevronRight
                    }
                    val label = if (useVerticalArrow) requireNotNull(verticalEdge)
                        else if (offset < 0) R.string.nodevision_left else R.string.nodevision_right
                    val x = if (useVerticalArrow) {
                        (maxWidth / 2 + pixel.dp - 24.dp).coerceIn(8.dp, (maxWidth - 56.dp).coerceAtLeast(8.dp))
                    } else if (offset < 0) 12.dp else maxWidth - 60.dp
                    val availableBottom = minOf(
                        if (showsTargetCard) targetCardTop ?: maxHeight else maxHeight,
                        if (discovery) discoveryControlsTop ?: maxHeight else maxHeight,
                    )
                    val preferredY = when {
                        useVerticalArrow && above -> markerY - 58.dp
                        useVerticalArrow -> markerY - 42.dp
                        verticalOffset != null -> markerY - 24.dp
                        else -> maxHeight * 0.43f - 24.dp
                    }
                    val y = if (useVerticalArrow && above) preferredY else
                        minOf(preferredY, availableBottom - 56.dp).coerceAtLeast(safeTop + 92.dp)
                    Surface(
                        modifier = Modifier.offset(x = x, y = y),
                        color = surface, shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(icon, stringResource(label), Modifier.size(48.dp).padding(8.dp), tint = accent)
                    }
                } else if (markerVisible) {
                    // The bottom of the tip stays on the bearing band when the label wraps.
                    Box(Modifier.offset(x = maxWidth / 2 + pixel.dp - markerWidth / 2, y = markerY - 88.dp)
                        .width(markerWidth).height(88.dp), contentAlignment = Alignment.BottomCenter) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = surface.copy(alpha = 0.98f), shape = markerShape,
                            shadowElevation = 4.dp,
                        ) {
                            Column(Modifier.background(overlayTint)
                                .visionMarkerBorder(markerShape, accent, surface, approximate, emphasized = true)
                                .padding(bottom = 9.dp).padding(horizontal = 9.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(visionName, style = MaterialTheme.typography.titleSmall, fontFamily = MonaspaceNeon,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    onTextLayout = { truncatedName = it.hasVisualOverflow })
                                geometry?.let { Text(stringResource(R.string.nodevision_distance_away, formatDistance(it.distanceMeters / 1000, distanceUnit)), style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon) }
                                if (approximate) Text(stringResource(R.string.nodevision_approx_elevation),
                                    style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Default, color = supportingText)
                            }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f))
                        .background(overlayTint, alpha = 0.65f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nodevision_close),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(stringResource(R.string.nodevision_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showsLocationInfo = true }) {
                            Icon(Icons.Outlined.Info, stringResource(R.string.nodevision_location_info),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showCamera && trueHeading != null && tangent != null && projection != null) {
                        Box(Modifier.fillMaxWidth().height(34.dp)) {
                            repeat(80) { tick ->
                                val degrees = tick * 4.5
                                val major = tick % 5 == 0
                                val x = FieldGeometry.cameraHorizontalOffset(degrees, trueHeading, tangent)?.plus(projection.centerX * 2 - 1)
                                if (x != null && abs(x) < 0.95) {
                                    val center = paneWidth * ((1 + x) / 2).toFloat()
                                    val height = if (major) 12.dp else 7.dp
                                    val width = if (major) 2.dp else 1.5.dp
                                    Box(Modifier.offset(x = center - width / 2, y = 31.dp - height)
                                        .size(width, height).background(
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = if (major) 0.85f else 0.52f),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                        ))
                                    if (major && abs(x) < 0.87) {
                                        Text(FieldGeometry.cardinalDirection(degrees).label,
                                            Modifier.offset(x = center - 17.dp, y = 3.dp).width(34.dp),
                                            style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon,
                                            textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp, 15.dp).background(accent, shape = androidx.compose.foundation.shape.CircleShape))
                        }
                    }
                    if (showCamera && trueHeading != null && projection != null && fix != null &&
                        mode == VisionElevationMode.Guide && (visionTarget != null || discoveryMarkers.isNotEmpty())) {
                        Text(stringResource(R.string.nodevision_phone_altitude_uncertain),
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = supportingText)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    if (BuildConfig.DEBUG && showsDiagnostics) {
                        val decision = if (mode == VisionElevationMode.Guide) "All markers: pitch guide"
                            else if (targetElevation == null) "All markers: elevation · spotlight unplaced"
                            else "All markers: elevation"
                        val readout = "Phone ${debugMeters(phoneAltitude?.meters)} ±${debugMeters(phoneAltitude?.accuracy)} · " +
                            "Antenna ${debugMeters(targetElevation)}\n$decision"
                        Text(readout, Modifier.padding(5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (discovery && visionTarget == null && inspectedNode == null && showCamera &&
                    fix != null && trueHeading != null && projection != null && verticalTangent != null &&
                    !showsCatalogLoading && discoveryMarkers.isEmpty()) {
                    Surface(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = surface,
                        border = overlayBorder,
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            stringResource(if (discoveryNodes.isEmpty()) R.string.nodevision_no_located_nodes
                                else R.string.nodevision_no_nodes_in_view),
                            modifier = Modifier.background(overlayTint).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (showsTargetCard) {
                Surface(
                    Modifier.align(Alignment.BottomCenter)
                        .onGloballyPositioned { coordinates ->
                            targetCardTop = with(density) { coordinates.positionInParent().y.toDp() }
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp).padding(bottom = if (discovery) 66.dp else 8.dp).widthIn(max = 340.dp),
                    color = surface.copy(alpha = 0.97f),
                    shape = MaterialTheme.shapes.medium,
                    border = overlayBorder, shadowElevation = 4.dp,
                ) {
                    Column(Modifier.background(overlayTint).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!markerVisible || truncatedName) {
                            Text(visionName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                        }
                        if (!markerVisible) geometry?.let {
                            Text(stringResource(R.string.nodevision_distance_away, formatDistance(it.distanceMeters / 1000, distanceUnit)),
                                style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon)
                        }
                        if (reportedHeight != null) {
                            val height = if (distanceUnit.usesFeet) reportedHeight * 3.28084 else reportedHeight
                            val formattedHeight = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(height)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.nodevision_install_height), Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.bodySmall, color = supportingText)
                                Text(stringResource(if (distanceUnit.usesFeet) R.string.nodevision_install_height_ft
                                    else R.string.nodevision_install_height_m, formattedHeight),
                                    style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon)
                            }
                        }
                        ownerStatus?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                        if (!markerVisible && approximate) Text(stringResource(R.string.nodevision_approx_elevation),
                            style = MaterialTheme.typography.bodySmall)
                        status?.let { Text(stringResource(it)) }
                        if (showSettings) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text(stringResource(R.string.nodevision_settings)) }
                    }
                }
            }
            if (discovery) {
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .onGloballyPositioned { coordinates ->
                            discoveryControlsTop = with(density) { coordinates.positionInParent().y.toDp() }
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 340.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (inspectedNode != null) {
                        val metadata = repeaterMetadata[inspectedNode.publicKey.lowercase()]
                        val inspectedDistance = reference?.let { from ->
                            inspectedNode.visionCoordinate()?.let { FieldGeometry.geometry(from, it).distanceMeters }
                        }
                        val distance = if ((markerVisible && focusedKey == inspectedKey) ||
                            discoveryMarkers.any { it.node.publicKey == inspectedKey }) null else inspectedDistance
                        Surface(shape = MaterialTheme.shapes.medium, border = overlayBorder, shadowElevation = 4.dp) {
                            Column(Modifier.background(overlayTint).heightIn(max = previewCardHeight)
                                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(inspectedNode.displayName, Modifier.weight(1f).clickable { expandsInspection = !expandsInspection },
                                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    IconButton(onClick = { expandsInspection = !expandsInspection }) {
                                        Icon(if (expandsInspection) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                            stringResource(if (expandsInspection) R.string.nodevision_collapse_details else R.string.nodevision_expand_details))
                                    }
                                    IconButton(onClick = { inspectedKey = null }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.nodevision_close_preview))
                                    }
                                }
                                if (distance != null) {
                                    Text(stringResource(R.string.nodevision_distance_away, formatDistance(distance / 1000, distanceUnit)),
                                        style = MaterialTheme.typography.labelSmall, fontFamily = MonaspaceNeon)
                                }
                                if (metadata?.status == "maintenance") {
                                    Text(stringResource(R.string.nodevision_owner_maintenance), style = MaterialTheme.typography.bodySmall)
                                }
                                if (focusedKey != inspectedKey) Button(onClick = {
                                    focusedKey = inspectedKey
                                    expandsInspection = true
                                }) { Text(stringResource(R.string.nodevision_focus)) }
                                if (expandsInspection) {
                                    Text(nodeRoleLabel(inspectedNode.role), style = MaterialTheme.typography.bodySmall)
                                    parseInstant(inspectedNode.lastSeen)?.let {
                                        Text(stringResource(R.string.nodevision_last_heard, relativeTime(it)),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (metadata?.hasDetails == true) {
                                        Text(stringResource(R.string.nodevision_owner_reported), style = MaterialTheme.typography.titleSmall)
                                        metadata.heightMeters?.let {
                                            val height = if (distanceUnit.usesFeet) it * 3.28084 else it
                                            val formatted = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(height)
                                            val value = stringResource(if (distanceUnit.usesFeet) R.string.nodevision_install_height_ft else R.string.nodevision_install_height_m, formatted)
                                            Text(stringResource(R.string.nodevision_install_height_value, value), style = MaterialTheme.typography.bodySmall)
                                        }
                                        metadata.applicableGainDbi?.let {
                                            Text(stringResource(R.string.nodevision_antenna_gain, java.text.NumberFormat.getNumberInstance().format(it)),
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        val power = when (metadata.power) {
                                            "mains" -> R.string.nodevision_power_mains
                                            "battery" -> R.string.nodevision_power_battery
                                            "solar" -> R.string.nodevision_power_solar
                                            else -> null
                                        }
                                        power?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                                    }
                                    if (reference != null && fix != null && inspectedDistance != null &&
                                        inspectedDistance > maxOf(5.0, fix.accuracy.toDouble()) && openLinkProfile != null) {
                                        Button(onClick = { openLinkProfile(reference, inspectedNode) }) {
                                            Text(stringResource(R.string.link_profile_link_profile))
                                        }
                                    }
                                    if (nodeDetails != null) {
                                        OutlinedButton(onClick = { detailedKey = inspectedNode.publicKey }) {
                                            Text(stringResource(R.string.nodevision_open_details))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (showsCatalogLoading) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = surface, border = overlayBorder) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.nodevision_loading_nodes), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (status != null && visionTarget == null) {
                        Text(stringResource(status), Modifier.background(surface, MaterialTheme.shapes.small).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showsNearby = true }) { Text(stringResource(R.string.nodevision_nearby)) }
                        if (focusedKey != null) {
                            FilledTonalButton(onClick = { focusedKey = null }) { Text(stringResource(R.string.nodevision_clear_focus)) }
                        }
                        if (showSettings && inspectedNode == null && visionTarget == null) {
                            FilledTonalButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }) { Text(stringResource(R.string.nodevision_settings)) }
                        }
                    }
                }
            }
            if (detailedNode != null && nodeDetails != null) {
                val dismissDetails = { detailedKey = null }
                MeshModalBottomSheet(onDismissRequest = dismissDetails,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.node_details), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = dismissDetails) { Text(stringResource(android.R.string.ok)) }
                    }
                    nodeDetails(detailedNode, dismissDetails)
                }
            }
            if (showsNearby && discovery) {
                ModalBottomSheet(onDismissRequest = { showsNearby = false }) {
                    Text(stringResource(R.string.nodevision_nearby), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (distanceUnit.usesMiles) R.string.nodevision_nearby_range_miles
                        else R.string.nodevision_nearby_range_km), Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    if (nearby.isEmpty()) {
                        Text(stringResource(if (showsCatalogLoading) R.string.nodevision_loading_nodes
                            else if (!locationGranted) R.string.nodevision_location_denied
                            else if (reference == null) R.string.nodevision_waiting_location
                            else R.string.nodevision_no_nearby),
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
                            items(nearby, key = { it.node.publicKey }) { (node, field) ->
                                val distance = field.distanceMeters
                                ListItem(headlineContent = { Text(node.displayName, fontFamily = MonaspaceNeon) },
                                    trailingContent = { Text(formatDistance(distance / 1000, distanceUnit), fontFamily = MonaspaceNeon) },
                                    modifier = Modifier.clickable {
                                        focusedKey = node.publicKey
                                        inspectedKey = node.publicKey
                                        expandsInspection = true
                                        showsNearby = false
                                    })
                            }
                        }
                    }
                }
            }
            if (showsLocationInfo) {
                AlertDialog(
                    onDismissRequest = { showsLocationInfo = false },
                    title = { Text(stringResource(R.string.nodevision_location_info)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.nodevision_help_markers))
                            Text(stringResource(R.string.nodevision_help_focus))
                            Text(stringResource(R.string.nodevision_help_phone_altitude))
                            Text(stringResource(R.string.nodevision_help_elevation))
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Info, contentDescription = null)
                                    Text(stringResource(R.string.nodevision_location_privacy_title),
                                        modifier = Modifier.semantics { heading() },
                                        style = MaterialTheme.typography.titleSmall)
                                }
                                Text(stringResource(R.string.nodevision_location_privacy))
                            }
                        }
                    },
                    dismissButton = if (BuildConfig.DEBUG) {
                        {
                            TextButton(onClick = {
                                showsDiagnostics = !showsDiagnostics
                                showsLocationInfo = false
                            }) {
                                Text(stringResource(if (showsDiagnostics) R.string.nodevision_hide_diagnostics
                                    else R.string.nodevision_show_diagnostics))
                            }
                        }
                    } else null,
                    confirmButton = {
                        TextButton(onClick = { showsLocationInfo = false }) { Text(stringResource(android.R.string.ok)) }
                    },
                )
            }
        }
    }
}

private data class NearbyVisionNode(val node: MeshNode, val geometry: FieldGeometryResult)
private fun visionMarkerTier(distance: Double): Int = when {
    distance < 500 -> 0
    distance < 2_000 -> 1
    else -> 2
}

private fun visionMarkerWidth(tier: Int): Double = when (tier) {
    0 -> 132.0
    1 -> 112.0
    else -> 96.0
}

private data class DiscoveryMarker(
    val node: MeshNode, val distance: Double, val x: Double, val y: Double, val width: Double, val tier: Int, val approximate: Boolean,
)

private fun discoveryMarkers(
    nearby: List<NearbyVisionNode>, inspectedKey: String?, focusedKey: String?, horizontalAccuracy: Double,
    heading: Double, projection: VisionOptics, paneWidth: Double, paneHeight: Double,
    pitch: Double, mode: VisionElevationMode, antenna: (MeshNode) -> Pair<Double?, VisionAntennaHeight>,
    focusedX: Double?, focusedY: Double, focusedWidth: Double, minimumY: Double, maximumY: Double,
): List<DiscoveryMarker> {
    val available = if (focusedX == null) 5 else 4
    data class Bounds(val left: Double, val right: Double, val top: Double, val bottom: Double) {
        fun overlaps(other: Bounds): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    val occupied = mutableListOf<Bounds>()
    if (focusedX != null) {
        occupied.add(Bounds(focusedX - focusedWidth / 2, focusedX + focusedWidth / 2, focusedY - 88, focusedY))
    }
    val markers = mutableListOf<DiscoveryMarker>()
    fun place(candidate: NearbyVisionNode) {
        if (markers.size == available || candidate.node.publicKey == focusedKey ||
            candidate.geometry.distanceMeters <= maxOf(5.0, horizontalAccuracy)) return
        val bearing = candidate.geometry.initialBearingDegrees ?: return
        val offset = FieldGeometry.cameraHorizontalOffset(bearing, heading, projection.halfWidthTangent) ?: return
        val x = paneWidth * (projection.centerX + offset / 2)
        val tier = visionMarkerTier(candidate.geometry.distanceMeters)
        val width = visionMarkerWidth(tier)
        val left = x - width / 2 - 5
        val right = x + width / 2 + 5
        if (left < 0 || right > paneWidth) return
        val (elevation, height) = antenna(candidate.node)
        val distance = candidate.geometry.distanceMeters
        val vertical = mode.offset(distance, elevation, pitch, projection.halfHeightTangent) ?: return
        val projectedY = paneHeight * (projection.centerY - vertical / 2)
        val y = discoveryMarkerY(projectedY, paneHeight, minimumY, maximumY) ?: return
        val approximate = mode.isApproximate(distance, height)
        val nudges = if (mode == VisionElevationMode.Guide) listOf(0.0, if (y + 92 <= maximumY) 92.0 else -92.0)
            else listOf(0.0, 24.0, -24.0, 48.0, -48.0, 72.0, -72.0, 92.0, -92.0)
        for (nudge in nudges) {
            val rowY = (y + nudge).coerceIn(minimumY, maximumY)
            val bounds = Bounds(left, right, rowY - 84, rowY + 4)
            if (occupied.any { it.overlaps(bounds) }) continue
            occupied.add(bounds)
            markers.add(DiscoveryMarker(candidate.node, candidate.geometry.distanceMeters, x, rowY, width, tier, approximate))
            break
        }
    }
    nearby.firstOrNull { it.node.publicKey == inspectedKey }?.let(::place)
    for (candidate in nearby) {
        if (markers.size == available) break
        if (candidate.node.publicKey != inspectedKey) place(candidate)
    }
    return markers
}

private fun MeshNode.visionCoordinate(): FieldCoordinate? =
    coordinate?.let { FieldCoordinate.create(it.latitude, it.longitude) }

private fun visionMarkerShape(radius: Float, tipHeight: Float, tipHalfWidth: Float) = GenericShape { size, _ ->
    val bottom = size.height - tipHeight
    val corner = minOf(radius, size.width / 4, bottom / 2).coerceAtLeast(0f)
    val tip = minOf(tipHalfWidth, size.width / 4)
    val mid = size.width / 2
    moveTo(corner, 0f)
    lineTo(size.width - corner, 0f)
    quadraticTo(size.width, 0f, size.width, corner)
    lineTo(size.width, bottom - corner)
    quadraticTo(size.width, bottom, size.width - corner, bottom)
    lineTo(mid + tip, bottom)
    quadraticTo(mid + tip * 0.56f, bottom + tipHeight * 0.65f, mid + tip * 0.12f, size.height - tipHeight * 0.11f)
    quadraticTo(mid, size.height + tipHeight * 0.11f, mid - tip * 0.12f, size.height - tipHeight * 0.11f)
    quadraticTo(mid - tip * 0.56f, bottom + tipHeight * 0.65f, mid - tip, bottom)
    lineTo(corner, bottom)
    quadraticTo(0f, bottom, 0f, bottom - corner)
    lineTo(0f, corner)
    quadraticTo(0f, 0f, corner, 0f)
    close()
}

private fun debugMeters(value: Double?): String = value?.let { "%.1f m".format(it) } ?: "—"

private data class VisionHeading(val degrees: Double, val pitch: Double, val receivedAt: Long)
private data class VisionAltitude(val meters: Double, val accuracy: Double, val fixTime: Long)

@androidx.annotation.RequiresApi(34)
private fun meanSeaLevelAltitude(context: Context, fix: Location): VisionAltitude? {
    val location = Location(fix)
    if (!location.hasMslAltitude() || !location.hasMslAltitudeAccuracy()) {
        if (!location.hasAltitude() || !location.hasVerticalAccuracy()) return null
        AltitudeConverter().addMslAltitudeToLocation(context, location)
    }
    if (!location.hasMslAltitude() || !location.hasMslAltitudeAccuracy()) return null
    return VisionAltitude(location.mslAltitudeMeters, location.mslAltitudeAccuracyMeters.toDouble(), fix.elapsedRealtimeNanos / 1_000_000)
}
private class VisionReadings {
    var fix by mutableStateOf<Location?>(null)
    var heading by mutableStateOf<VisionHeading?>(null)
    var stabilizingHeading by mutableStateOf(false)
}

@SuppressLint("MissingPermission")
@Composable
private fun rememberVisionReadings(needsLocation: Boolean, needsHeading: Boolean): VisionReadings {
    val context = LocalContext.current
    val readings = remember { VisionReadings() }
    DisposableEffect(context, needsLocation) {
        readings.fix = null
        if (!needsLocation) return@DisposableEffect onDispose {}
        val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { location ->
            if (location.hasAccuracy() && location.accuracy >= 0 &&
                location.elapsedRealtimeNanos >= (readings.fix?.elapsedRealtimeNanos ?: 0L)) {
                readings.fix = location
            }
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                if (locations.isProviderEnabled(provider)) locations.requestLocationUpdates(provider, 1_000L, 0f, listener)
            } catch (_: IllegalArgumentException) {
                // This device has no such provider.
            } catch (_: SecurityException) {
                // A coarse grant may not allow updates from GPS.
            }
        }
        onDispose { locations.removeUpdates(listener) }
    }
    DisposableEffect(context, needsHeading) {
        readings.heading = null
        readings.stabilizingHeading = false
        if (!needsHeading) return@DisposableEffect onDispose {}
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val orientation = object : SensorEventListener {
            private val matrix = FloatArray(9)
            private var lastUpdate = 0L
            private val warmup = VisionHeadingWarmup()

            private fun resetHeading() {
                warmup.reset()
                readings.heading = null
                readings.stabilizingHeading = false
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) resetHeading()
            }
            override fun onSensorChanged(event: SensorEvent) {
                val uptime = SystemClock.elapsedRealtime()
                if (uptime - lastUpdate < 65) return
                lastUpdate = uptime
                val error = event.values.getOrNull(4)
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                    (error != null && error >= 0 && Math.toDegrees(error.toDouble()) > 25)) {
                    resetHeading()
                    return
                }
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val east = -matrix[2].toDouble()
                val north = -matrix[5].toDouble()
                val horizontal = hypot(east, north)
                if (horizontal < 0.35) {
                    resetHeading()
                    return
                }
                val degrees = Math.toDegrees(atan2(east, north))
                val pitch = Math.toDegrees(atan2(-matrix[8].toDouble(), horizontal))
                val settled = warmup.settledHeading(degrees, uptime)
                readings.stabilizingHeading = settled == null
                readings.heading = settled?.let { VisionHeading(it, pitch, uptime) }
            }
        }
        rotation?.let { sensors.registerListener(orientation, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensors.unregisterListener(orientation) }
    }
    return readings
}

/** Require a short run of consistent samples after opening or losing the compass. */
internal class VisionHeadingWarmup {
    private var reference = 0.0
    private var reachedAt = 0L
    private var lastAt: Long? = null
    private var deltaSum = 0.0
    private var count = 0
    private var ready = false

    fun reset() {
        ready = false
        lastAt = null
    }

    fun settledHeading(degrees: Double, time: Long): Double? {
        val previousTime = lastAt
        lastAt = time
        if (previousTime == null || time - previousTime > 250) {
            ready = false
            count = 0
        }
        if (ready) return degrees
        val delta = ((degrees - reference + 540) % 360) - 180
        if (count == 0 || abs(delta) > 4) {
            reference = degrees
            reachedAt = time
            deltaSum = 0.0
            count = 1
            return null
        }
        deltaSum += delta
        count++
        if (count < 4 || time - reachedAt < 240) return null
        ready = true
        return reference + deltaSum / count
    }
}

private data class VisionOptics(
    val width: Int,
    val height: Int,
    val centerX: Double,
    val centerY: Double,
    val halfWidthTangent: Double,
    val halfHeightTangent: Double,
)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun VisionCamera(modifier: Modifier, update: (VisionOptics?, Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val updateNow by rememberUpdatedState(update)
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(view, lifecycle) {
        val provider = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var bound: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        var lastProjection: VisionOptics? = null
        var lastTransform: android.graphics.Matrix? = null
        var sensorAxes: FloatArray? = null
        fun publish(projection: VisionOptics?) {
            if (projection != lastProjection) {
                lastProjection = projection
                updateNow(projection, false)
            }
        }
        fun refreshOptics() {
            val rotation = view.display?.rotation ?: return
            if (preview.targetRotation != rotation) {
                preview.targetRotation = rotation
                lastTransform = null
                publish(null)
                return
            }
            val axes = sensorAxes ?: return
            val transform = view.sensorToViewTransform
            if (transform == null || view.width <= 0 || view.height <= 0) {
                publish(null)
                return
            }
            if (lastTransform == transform && lastProjection?.width == view.width && lastProjection?.height == view.height) return
            lastTransform = transform
            val mapped = axes.copyOf()
            transform.mapPoints(mapped)
            // Each endpoint is one focal length from the optical center along a sensor axis.
            val focalX = hypot((mapped[2] - mapped[0]).toDouble(), (mapped[4] - mapped[0]).toDouble())
            val focalY = hypot((mapped[3] - mapped[1]).toDouble(), (mapped[5] - mapped[1]).toDouble())
            if (focalX <= 0 || focalY <= 0 || mapped.any { !it.isFinite() }) {
                publish(null)
                return
            }
            publish(VisionOptics(
                width = view.width, height = view.height,
                centerX = mapped[0].toDouble() / view.width,
                centerY = mapped[1].toDouble() / view.height,
                halfWidthTangent = view.width / (2 * focalX),
                halfHeightTangent = view.height / (2 * focalY),
            ))
        }
        // PreviewView may update its transform after layout or without a size change.
        val drawListener = ViewTreeObserver.OnPreDrawListener {
            refreshOptics()
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(drawListener)
        val streamObserver = androidx.lifecycle.Observer<PreviewView.StreamState> { refreshOptics() }
        view.previewStreamState.observe(lifecycle, streamObserver)
        provider.addListener({
            if (!disposed) {
                try {
                    val available = provider.get()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val camera = available.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    bound = available
                    val info = Camera2CameraInfo.from(camera.cameraInfo)
                    val activeArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val pixelArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (activeArray != null && pixelArray != null && physical != null && focal != null &&
                        focal > 0 && physical.width > 0 && physical.height > 0) {
                        val x = activeArray.exactCenterX()
                        val y = activeArray.exactCenterY()
                        val fx = focal * pixelArray.width / physical.width
                        val fy = focal * pixelArray.height / physical.height
                        sensorAxes = floatArrayOf(x, y, x + fx, y, x, y + fy)
                    }
                    refreshOptics()
                } catch (_: Exception) {
                    updateNow(null, true)
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            view.viewTreeObserver.removeOnPreDrawListener(drawListener)
            view.previewStreamState.removeObserver(streamObserver)
            bound?.unbind(preview)
            updateNow(null, false)
        }
    }
}


internal data class VisionAntennaHeight(val meters: Double, val approximate: Boolean)

internal fun visionAntennaHeight(role: String?, reported: Double?): VisionAntennaHeight {
    val normalized = role?.lowercase()
    val height = reported.takeIf { normalized == "repeater" }
    val preset = when (normalized) {
        "repeater", "room", "room server", "room_server", "roomserver", "room-server" -> CoverageHeightPreset.ROOFTOP
        else -> CoverageHeightPreset.HANDHELD
    }
    return VisionAntennaHeight(height ?: preset.meters, height == null)
}

internal sealed interface VisionElevationMode {
    data object Guide : VisionElevationMode
    data class Elevation(val altitude: Double, val accuracy: Double) : VisionElevationMode

    fun offset(distance: Double, antenna: Double?, pitch: Double, tangent: Double): Double? = when (this) {
        Guide -> FieldGeometry.cameraBearingGuideOffset(pitch, tangent)
        is Elevation -> antenna?.let {
            FieldGeometry.cameraVerticalOffset(distance, it, altitude, accuracy, pitch, tangent, clampAngle = true)
        }
    }

    fun isApproximate(distance: Double, height: VisionAntennaHeight): Boolean =
        this is Elevation && (height.approximate || FieldGeometry.cameraElevationUncertainty(distance, accuracy) > 8)
}

internal fun visionElevationMode(
    altitude: Double?, accuracy: Double?, distances: List<Double>, nearDistance: Double,
): VisionElevationMode {
    if (altitude == null || !altitude.isFinite() || accuracy == null || !accuracy.isFinite() || accuracy <= 0 ||
        distances.any { it > nearDistance && FieldGeometry.cameraElevationUncertainty(it, accuracy) > 30 }) {
        return VisionElevationMode.Guide
    }
    return VisionElevationMode.Elevation(altitude, accuracy)
}

internal data class VisionTerrainPoint(val key: String, val coordinate: ProfileCoordinate) {
    constructor(key: String, coordinate: FieldCoordinate) :
        this(key, ProfileCoordinate(coordinate.latitude, coordinate.longitude))
}

private fun MeshNode.terrainPoint(): VisionTerrainPoint? =
    visionCoordinate()?.let { VisionTerrainPoint(publicKey.lowercase(), it) }

internal class VisionTerrainCache(private val provider: LinkTerrainProviding) {
    private val elevations = mutableMapOf<VisionTerrainPoint, Double>()
    private val attempted = mutableSetOf<VisionTerrainPoint>()

    suspend fun resolve(points: Set<VisionTerrainPoint>): Map<VisionTerrainPoint, Double> {
        val missing = points.filterNot(attempted::contains)
            .sortedWith(compareBy({ it.key }, { it.coordinate.latitude }, { it.coordinate.longitude }))
        for (batch in missing.chunked(100)) {
            val coordinates = batch.map { it.coordinate }.distinct()
            val request = if (coordinates.size == 1) coordinates + coordinates else coordinates
            val profile = try {
                provider.profile(request)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            attempted.addAll(batch)
            if (profile == null) continue
            val ground = request.zip(profile.points).associate { (coordinate, point) -> coordinate to point.elevationMeters }
            for (point in batch) {
                ground[point.coordinate]?.takeIf(Double::isFinite)?.let { elevations[point] = it }
            }
        }
        return elevations.toMap()
    }
}

/** Ignore altitude changes that move even the closest eligible marker less than 0.15°. */
internal fun stableVisionAltitude(previous: Double?, current: Double?, distance: Double?): Double? {
    if (current == null || !current.isFinite()) return null
    if (previous == null || distance == null) return current
    return if (kotlin.math.atan2(abs(current - previous), distance) < Math.toRadians(0.15)) previous else current
}

/** Clamp to the label-safe area while the node is in or just outside the viewfinder. */
internal fun discoveryMarkerY(projectedY: Double, paneHeight: Double, minimumY: Double, maximumY: Double): Double? {
    if (projectedY !in -24.0..(paneHeight + 24)) return null
    return projectedY.coerceIn(minimumY, maximumY)
}

private fun Modifier.visionMarkerBorder(
    shape: Shape, ink: Color, backing: Color, approximate: Boolean, emphasized: Boolean,
): Modifier = drawWithCache {
    // The marker shape is a GenericShape. Draw inside the clipped Surface so the full width stays visible.
    val path = (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path
    val width = if (emphasized) 3.dp.toPx() else 2.dp.toPx()
    val baseStroke = Stroke(width = 2 * (width + 1.dp.toPx()), join = StrokeJoin.Round)
    val pattern = if (approximate) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx())) else null
    val stroke = Stroke(width = 2 * width, cap = StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = pattern)
    onDrawWithContent {
        drawContent()
        drawPath(path, backing, style = baseStroke)
        drawPath(path, ink, style = stroke)
    }
}
