package org.nodescope.android.feature.nodecheck

import org.nodescope.android.core.design.nodeRoleLabel
import org.nodescope.android.core.design.UiText
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.nodescope.android.core.design.LocalBeaconSource
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.meshCardColors
import org.nodescope.android.core.design.statusAccent
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.BackendCapability
import org.nodescope.android.core.network.BrowseRepository
import org.nodescope.android.core.network.LiveFeedState
import org.nodescope.android.core.network.beaconUiExplanation
import org.nodescope.android.core.storage.SavedItem
import org.nodescope.android.feature.nodes.roleColor
import org.nodescope.android.feature.nodes.roleIcon

sealed interface NodeCheckState {
    data object Idle : NodeCheckState
    data object Loading : NodeCheckState
    data class Choose(val query: String, val matches: List<MeshNode>) : NodeCheckState
    data class Report(val report: NodeCheckReport) : NodeCheckState
    /** The analyzer has never heard this node. A full key can still be watched for. */
    data class NotFound(val query: String, val fullKey: String?) : NodeCheckState
    data class Failed(val message: UiText) : NodeCheckState
}

class NodeCheckViewModel(private val repository: BrowseRepository) : ViewModel() {
    private val _state = MutableStateFlow<NodeCheckState>(NodeCheckState.Idle)
    val state: StateFlow<NodeCheckState> = _state
    private val _observers = MutableStateFlow<Map<String, MeshObserver>>(emptyMap())
    /** Observer id → observer, for regions. */
    val observers: StateFlow<Map<String, MeshObserver>> = _observers
    private var job: Job? = null
    private var observersHost: String? = null

    fun loadObservers(host: String) {
        if (observersHost == host) return
        observersHost = host
        viewModelScope.launch {
            runCatching { repository.observers(host) }.getOrNull()?.let { list -> _observers.value = list.associateBy { it.id } }
        }
    }

    fun check(host: String, rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        val key = nodeCheckKey(query)
        if (key != null) checkKey(host, key) else search(host, query.take(64))
    }

    fun checkKey(host: String, key: String) = launchCheck {
        _state.value = NodeCheckState.Loading
        try {
            val input = repository.nodeCheckInput(host, key.lowercase())
            _state.value = if (input != null) NodeCheckState.Report(NodeCheckRules.report(input))
                else NodeCheckState.NotFound(key, key.takeIf { it.length == 64 }?.lowercase())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.value = NodeCheckState.Failed(e.message?.let(UiText::Raw) ?: UiText.Resource(R.string.node_check_couldn_t_reach_the_analyzer))
        }
    }

    private fun search(host: String, name: String) = launchCheck {
        _state.value = NodeCheckState.Loading
        try {
            val found = repository.searchNodes(host, name)
            val exact = found.filter { (it.name ?: "").equals(name, ignoreCase = true) }
            val matches = exact.ifEmpty { found }
            when (matches.size) {
                0 -> _state.value = NodeCheckState.NotFound(name, null)
                1 -> {
                    val input = repository.nodeCheckInput(host, matches[0].publicKey.lowercase())
                    _state.value = input?.let { NodeCheckState.Report(NodeCheckRules.report(it)) } ?: NodeCheckState.NotFound(name, null)
                }
                else -> _state.value = NodeCheckState.Choose(name, matches)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.value = NodeCheckState.Failed(e.message?.let(UiText::Raw) ?: UiText.Resource(R.string.node_check_couldn_t_reach_the_analyzer))
        }
    }

    private fun launchCheck(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch { block() }
    }
}

/** Traffic-light scale, matching iOS and meshtexas.org: green good, amber check, red problem. */
@Composable
fun NodeCheckLevel.color(): Color = when (this) {
    NodeCheckLevel.GOOD -> statusAccent(true)
    NodeCheckLevel.WARN -> statusAccent(false)
    NodeCheckLevel.BAD -> MaterialTheme.colorScheme.error
    NodeCheckLevel.INFO -> MaterialTheme.colorScheme.primary
}

private fun NodeCheckLevel.icon() = when (this) {
    NodeCheckLevel.GOOD -> Icons.Outlined.Check
    NodeCheckLevel.WARN -> Icons.Outlined.PriorityHigh
    NodeCheckLevel.BAD -> Icons.Outlined.Close
    NodeCheckLevel.INFO -> Icons.Outlined.Info
}

/**
 * Node Check: is a node getting out? Opened from Explore (any node, including one the analyzer
 * has never heard) or from a node's page ([initialKey]). Matches iOS `NodeCheckScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeCheckScreen(
    model: NodeCheckViewModel?, host: String, initialKey: String?, savedNodes: List<SavedItem>,
    feed: () -> LiveFeedState, onNodePage: ((String) -> Unit)?,
) {
    val beacon = LocalBeaconSource.current
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: NodeCheckState.Idle
    val observers = model?.observers?.collectAsStateWithLifecycle()?.value.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    LaunchedEffect(model, host, initialKey, beacon) {
        if (model == null || beacon) return@LaunchedEffect
        model.loadObservers(host)
        if (initialKey != null && model.state.value == NodeCheckState.Idle) model.checkKey(host, initialKey)
    }
    val shownKey = (state as? NodeCheckState.Report)?.report?.node?.publicKey
    fun search() { focus.clearFocus(); model?.check(host, query) }

    PullToRefreshBox(isRefreshing = false, onRefresh = { shownKey?.let { model?.checkKey(host, it) } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (beacon) {
                item { MessageCard(NodeCheckLevel.INFO, stringResource(R.string.node_check_not_available_on_this_source), BackendCapability.NODE_CHECK.beaconUiExplanation.asString()) }
                return@LazyColumn
            }
            if (initialKey == null) item {
                Card(colors = meshCardColors()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.node_check_is_your_node_getting_out_see_which_observers),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(stringResource(R.string.node_check_node_name_public_key_or_contact_link)) },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSearch = { search() }))
                        Button(::search, Modifier.fillMaxWidth(), enabled = query.isNotBlank()) {
                            Icon(Icons.Outlined.Verified, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_check_node))
                        }
                    }
                }
            }
            when (state) {
                NodeCheckState.Idle -> if (initialKey != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) } else {
                    if (savedNodes.isNotEmpty()) item { SavedNodesCard(savedNodes) { item -> query = item.title; model?.checkKey(host, item.entityId) } }
                    item { HelpCard() }
                }
                NodeCheckState.Loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.node_check_checking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is NodeCheckState.Choose -> item { ChooseCard(state) { model?.checkKey(host, it.publicKey) } }
                is NodeCheckState.Report -> reportItems(state.report, observers, feed, onNodePage.takeIf { initialKey == null })
                is NodeCheckState.NotFound -> {
                    item {
                        MessageCard(NodeCheckLevel.BAD, "We haven’t heard this node",
                            if (state.fullKey == null) "No observer on this analyzer has picked it up. To test a new node, enter its full public key; you can then run a live test and watch for its first advert."
                            else "No observer on this analyzer has picked it up yet. Run a live test, send a flood advert, and watch for its first one. If nothing arrives, check that the radio uses the same settings as other nodes in your area and that a repeater is in range.")
                    }
                    state.fullKey?.let { key ->
                        item(key = "live-$key") {
                            LiveTestCard(key, null, observers, feed, startsImmediately = false,
                                onHeard = { model?.checkKey(host, key) }, onClose = null)
                        }
                    }
                }
                is NodeCheckState.Failed -> item {
                    MessageCard(NodeCheckLevel.WARN, stringResource(R.string.node_check_couldn_t_check_this_node), state.message.asString()) {
                        OutlinedButton({ val key = initialKey; if (key != null) model?.checkKey(host, key) else search() }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_try_again))
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reportItems(
    report: NodeCheckReport, observers: Map<String, MeshObserver>, feed: () -> LiveFeedState, onNodePage: ((String) -> Unit)?,
) {
    item(key = "summary-${report.node.publicKey}") {
        var showsLiveTest by rememberSaveable(report.node.publicKey) { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(report, onLiveTest = { showsLiveTest = true }, onNodePage = onNodePage)
            AnimatedVisibility(showsLiveTest) {
                LiveTestCard(report.node.publicKey, report.node.name, observers, feed, startsImmediately = true,
                    onHeard = null, onClose = { showsLiveTest = false })
            }
        }
    }
    item { FindingsCard(report.findings) }
    report.latestAdvert?.takeIf { it.observers.isNotEmpty() }?.let { advert ->
        item {
            ObserversCard(
                if (advert.kind == "flood") stringResource(R.string.node_check_who_heard_its_last_flood_advert) else stringResource(R.string.node_check_who_heard_its_last_advert),
                (advert.sentAt?.let { "Sent ${NodeCheckRules.ago(it)}. " } ?: "") + "Closest first; Direct means no repeater in between.",
                advert.observers, showsPackets = false)
        }
    }
    if (report.direct.isNotEmpty()) item {
        ObserversCard(stringResource(R.string.node_check_observers_that_hear_it_directly), stringResource(R.string.node_check_average_signal_of_everything_they_ve_heard_straight),
            report.direct, showsPackets = true)
    }
}

@Composable
private fun SummaryCard(report: NodeCheckReport, onLiveTest: () -> Unit, onNodePage: ((String) -> Unit)?) {
    val node = report.node
    val advert = report.latestAdvert
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(roleColor(node.role), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(roleIcon(node.role), null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(node.name ?: stringResource(R.string.node_check_unnamed_node), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(nodeRoleLabel(node.role), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = roleColor(node.role))
                        Text(node.publicKey.take(16).uppercase() + "…", style = MaterialTheme.typography.labelMedium,
                            fontFamily = MonaspaceNeon, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            StatusBadge(report.level, report.headline)
            StatGrid(listOf(
                Stat(stringResource(R.string.node_check_last_heard), report.lastHeard?.let { NodeCheckRules.ago(it) } ?: "—",
                    report.lastHeard?.let { DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.node_check_not_heard)),
                Stat(stringResource(R.string.node_check_reached), advert?.observers?.size?.toString() ?: "—",
                    advert?.let { pluralStringResource(R.plurals.node_check_advert_observers, it.observers.size) } ?: stringResource(R.string.node_check_no_recent_advert)),
                Stat(stringResource(R.string.node_check_regions), advert?.regions?.size?.toString() ?: "—", advert?.regions?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.node_check_none_yet)),
                Stat(stringResource(R.string.node_check_nearest), NodeCheckRules.hopsLabel(advert?.observers?.firstOrNull()?.hops), when (report.direct.size) {
                    0 -> stringResource(R.string.node_check_no_direct_observers)
                    else -> pluralStringResource(R.plurals.node_check_direct_count, report.direct.size, report.direct.size)
                }),
            ))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onLiveTest, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Icon(Icons.Outlined.SettingsInputAntenna, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_run_live_test))
                }
                onNodePage?.let { open ->
                    Button({ open(node.publicKey) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_open_node_page))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(level: NodeCheckLevel, text: String) {
    val color = level.color()
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}

private data class Stat(val label: String, val value: String, val note: String)

@Composable
private fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stat ->
                    Column(Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(10.dp)
                        .semantics(mergeDescendants = true) {}) {
                        Text(stat.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stat.value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stat.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FindingsCard(findings: List<NodeCheckFinding>) {
    val groups = listOf(
        "Needs attention" to findings.filter { it.level == NodeCheckLevel.BAD || it.level == NodeCheckLevel.WARN },
        "Looking good" to findings.filter { it.level == NodeCheckLevel.GOOD },
        "Good to know" to findings.filter { it.level == NodeCheckLevel.INFO },
    ).filter { it.second.isNotEmpty() }
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEachIndexed { index, (title, list) ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.forEach { FindingRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: NodeCheckFinding) {
    val color = finding.level.color()
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(26.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(finding.level.icon(), null, Modifier.size(16.dp), tint = color)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(finding.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(finding.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            finding.fix?.let { fix ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("What to do: ") }
                    append(fix)
                }, Modifier.padding(top = 4.dp).fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Observers grouped by region, the region with the closest observer first, five rows each. */
@Composable
private fun ObserversCard(title: String?, subtitle: String?, observers: List<HeardObserver>, showsPackets: Boolean, embedded: Boolean = false) {
    val unknownRegion = stringResource(R.string.node_check_unknown)
    val groups = remember(observers, unknownRegion) {
        observers.groupBy { it.region ?: unknownRegion }.toList().sortedWith(
            compareBy<Pair<String, List<HeardObserver>>> { (_, list) -> list.minOf { it.hops ?: 99 } }
                .thenByDescending { it.second.size }.thenBy { it.first })
    }
    var expanded by remember(observers) { mutableStateOf(setOf<String>()) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            groups.forEach { (region, list) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(region, Modifier.clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            .padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, fontFamily = MonaspaceNeon,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (list.size == 1) stringResource(R.string.node_check_1_observer) else pluralStringResource(R.plurals.node_check_observers_2, list.size, list.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val showsAll = region in expanded
                    val rows = if (showsAll) list else list.take(ROWS_PER_REGION)
                    rows.forEachIndexed { index, observer ->
                        ObserverRow(observer, showsPackets)
                        if (index < rows.lastIndex) HorizontalDivider()
                    }
                    val hidden = list.size - ROWS_PER_REGION
                    if (hidden > 0 && !showsAll) TextButton({ expanded = expanded + region }) { Text(stringResource(R.string.node_check_show_more_in, hidden, region)) }
                }
            }
        }
    }
    if (embedded) content() else Card(colors = meshCardColors()) { Box(Modifier.padding(16.dp)) { content() } }
}

private const val ROWS_PER_REGION = 5

@Composable
private fun ObserverRow(observer: HeardObserver, showsPackets: Boolean) {
    val snr = observer.snr
    val tone = when { snr == null -> NodeCheckLevel.INFO; snr >= 5 -> NodeCheckLevel.GOOD; snr >= -5 -> NodeCheckLevel.WARN; else -> NodeCheckLevel.BAD }.color()
    val direct = !showsPackets && observer.hops == 0
    val good = statusAccent(true)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(observer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (showsPackets) observer.packets?.let { "$it pkts" } ?: "—" else NodeCheckRules.hopsLabel(observer.hops),
            Modifier.clip(CircleShape).background((if (direct) good else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = if (direct) good else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(snr?.let { NodeCheckRules.signedDecibels(it) } ?: "—", style = MaterialTheme.typography.labelMedium, fontFamily = MonaspaceNeon)
                observer.rssi?.let { Text(stringResource(R.string.node_check_dbm, it.toInt()), style = MaterialTheme.typography.labelMedium, fontFamily = MonaspaceNeon,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // SNR from −15 dB (barely decodable) to +12 dB (strong) fills the bar.
            val fill = snr?.let { ((it + 15) / 27).toFloat().coerceIn(0.04f, 1f) } ?: 0f
            Box(Modifier.width(96.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(tone, CircleShape))
            }
        }
    }
}

// ---- Live test ------------------------------------------------------------------------------

private const val LIVE_SECONDS = 120

/**
 * "Did anyone hear me?" Watches the shared live feed for adverts from [publicKey] after the test
 * starts and lists the observers that report them. CoreScope sends one frame per observer report.
 */
@Composable
private fun LiveTestCard(
    publicKey: String, nodeName: String?, observers: Map<String, MeshObserver>, feed: () -> LiveFeedState,
    startsImmediately: Boolean, onHeard: (() -> Unit)?, onClose: (() -> Unit)?,
) {
    var startedAt by remember { mutableStateOf<Long?>(if (startsImmediately) System.currentTimeMillis() else null) }
    var finished by remember { mutableStateOf<Boolean?>(null) } // null while listening; true when stopped by hand
    var remaining by remember { mutableIntStateOf(LIVE_SECONDS) }
    var heard by remember { mutableStateOf<List<HeardObserver>>(emptyList()) }
    var sawFlood by remember { mutableStateOf(false) }
    var sawZeroHop by remember { mutableStateOf(false) }
    val key = publicKey.lowercase()
    val start = startedAt

    if (start != null && finished == null) {
        LaunchedEffect(start) {
            while (true) {
                remaining = (LIVE_SECONDS - ((System.currentTimeMillis() - start) / 1000).toInt()).coerceAtLeast(0)
                if (remaining <= 0) { finished = false; break }
                delay(1_000)
            }
        }
        LaunchedEffect(start, observers) {
            snapshotFlow { feed().packets }.collect { packets ->
                val reports = packets.filter { it.isLive && it.receivedAt >= start && it.advertKey == key && it.observerId != null &&
                    (it.payloadType == 4 || it.typeName == "ADVERT") }
                if (reports.isEmpty()) return@collect
                val best = LinkedHashMap<String, HeardObserver>()
                heard.forEach { best[it.observerId] = it }
                reports.forEach { packet ->
                    packet.routeType?.let { if (it <= 1) sawFlood = true else sawZeroHop = true }
                    val id = packet.observerId!!
                    val entry = HeardObserver(id, packet.observerName ?: "Unnamed observer", observers[id]?.iata ?: packet.region,
                        packet.hops.size, packet.snr?.let { (it * 10).toInt() / 10.0 }, packet.rssi)
                    val previous = best[id]
                    if (previous == null || (previous.hops ?: 99) > (entry.hops ?: 99)) best[id] = entry
                }
                val wasEmpty = heard.isEmpty()
                heard = NodeCheckRules.sortClosest(best.values)
                if (wasEmpty && heard.isNotEmpty()) onHeard?.invoke()
            }
        }
    }

    fun begin() {
        heard = emptyList(); sawFlood = false; sawZeroHop = false; remaining = LIVE_SECONDS; finished = null
        startedAt = System.currentTimeMillis()
    }

    val listening = start != null && finished == null
    val regions = heard.mapNotNull { it.region }.distinct().sorted()
    val good = statusAccent(true)
    val amber = statusAccent(false)
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.node_check_live_test_did_anyone_hear_me), style = MaterialTheme.typography.titleMedium)
            if (start == null) {
                Text(stringResource(R.string.node_check_send_a_flood_advert_from_and_watch_which, nodeName ?: stringResource(R.string.node_check_your_node)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(stringResource(R.string.node_check_start_the_test_below),
                        stringResource(R.string.node_check_send_a_flood_advert_in_the_meshcore_app),
                        stringResource(R.string.node_check_watch_for_up_to_two_minutes_as_observers)).forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.node_check_value, index + 1), style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon, fontWeight = FontWeight.SemiBold)
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Button(::begin, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.SettingsInputAntenna, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_start_live_test))
                }
                return@Column
            }
            val count = heard.size
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val dot = when { count > 0 -> good; finished != null -> amber; else -> MaterialTheme.colorScheme.primary }
                Box(Modifier.size(10.dp).alpha(if (listening && count == 0 && remaining % 2 == 0) 0.35f else 1f).background(dot, CircleShape))
                Text(when {
                    listening && count == 0 -> stringResource(R.string.node_check_listening_for_your_advert)
                    listening -> pluralStringResource(R.plurals.node_check_heard_so_far, count, count)
                    count == 0 -> if (finished == true) stringResource(R.string.node_check_test_stopped) else stringResource(R.string.node_check_nothing_heard_in_two_minutes)
                    else -> stringResource(R.string.node_check_heard_in_regions,
                        pluralStringResource(R.plurals.node_check_observer_count, count, count),
                        pluralStringResource(R.plurals.node_check_region_count, regions.size, regions.size))
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (listening) {
                    Text(stringResource(R.string.node_check_d_02d).format(remaining / 60, remaining % 60), style = MaterialTheme.typography.labelMedium, fontFamily = MonaspaceNeon,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ finished = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.node_check_stop)) }
                }
            }
            if (listening) LinearProgressIndicator({ remaining / LIVE_SECONDS.toFloat() }, Modifier.fillMaxWidth())
            val note = when {
                listening && sawZeroHop && !sawFlood -> stringResource(R.string.node_check_that_was_a_zero_hop_advert_so_only)
                listening && count > 0 -> stringResource(R.string.node_check_more_observers_may_still_report_it_so_keep)
                listening -> stringResource(R.string.node_check_send_a_flood_advert_now_in_the_meshcore)
                count > 0 -> if (finished == true) stringResource(R.string.node_check_test_stopped_2) else stringResource(R.string.node_check_test_complete)
                finished == true -> null
                else -> stringResource(R.string.node_check_no_observer_reported_an_advert_from_this_node)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!listening) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(::begin, Modifier.weight(1f)) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.node_check_run_again)) }
                onClose?.let { close -> OutlinedButton(close, Modifier.weight(1f)) { Text(stringResource(R.string.common_done)) } }
            }
            if (count > 0) {
                val nearest = heard.first().hops
                StatGrid(listOf(
                    Stat(stringResource(R.string.node_check_observers), stringResource(R.string.node_check_value_2, count), stringResource(R.string.node_check_heard_it)),
                    Stat(stringResource(R.string.node_check_regions), stringResource(R.string.node_check_value_2, regions.size), regions.joinToString(", ").ifEmpty { "—" }),
                    Stat(stringResource(R.string.node_check_nearest), NodeCheckRules.hopsLabel(nearest), if (nearest == 0) stringResource(R.string.node_check_no_repeater_needed) else stringResource(R.string.node_check_through_repeaters)),
                ))
                NodeCheckMap(heard, observers)
                ObserversCard(null, null, heard, showsPackets = false, embedded = true)
            }
        }
    }
}

// ---- Shared pieces ---------------------------------------------------------------------------

@Composable
private fun MessageCard(level: NodeCheckLevel, title: String, message: String, actions: @Composable ColumnScope.() -> Unit = {}) {
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = level.color())
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
private fun SavedNodesCard(items: List<SavedItem>, onPick: (SavedItem) -> Unit) {
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.node_check_your_nodes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            items.forEach { item ->
                val role = item.node?.role
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(item) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).background(roleColor(role), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(roleIcon(role), null, Modifier.size(16.dp), tint = Color.White)
                    }
                    Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontFamily = MonaspaceNeon,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChooseCard(state: NodeCheckState.Choose, onPick: (MeshNode) -> Unit) {
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.node_check_which_node_is_yours), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.node_check_more_than_one_node_matches_pick_yours_or, state.query),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.matches.forEach { node ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .clickable { onPick(node) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).background(roleColor(node.role), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(roleIcon(node.role), null, Modifier.size(16.dp), tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node.displayName, style = MaterialTheme.typography.bodyLarge, fontFamily = MonaspaceNeon, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val heard = runCatching { Instant.parse(node.lastSeen).toEpochMilli() }.getOrNull()
                        Text(listOfNotNull(nodeRoleLabel(node.role), node.publicKey.take(8).uppercase(),
                            heard?.let { "heard ${NodeCheckRules.ago(it)}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    var open by remember { mutableStateOf<Int?>(null) }
    val entries = listOf(
        stringResource(R.string.node_check_where_do_i_find_my_node_s_public) to stringResource(R.string.node_check_in_the_meshcore_app_a_companion_s_public),
        stringResource(R.string.node_check_how_do_i_check_a_brand_new_node) to stringResource(R.string.node_check_enter_its_full_public_key_if_the_analyzer),
    )
    Card(colors = meshCardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.node_check_help), style = MaterialTheme.typography.titleMedium)
            entries.forEachIndexed { index, (question, answer) ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = if (open == index) null else index }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(question, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Icon(if (open == index) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(open == index) {
                    Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- Entry points ----------------------------------------------------------------------------

/** The node page's way in: the verdict at a glance, tapping through to the full check. */
@Composable
fun NodeCheckLinkCard(report: NodeCheckReport?, loading: Boolean, onClick: () -> Unit) {
    val accent = report?.level?.color() ?: MaterialTheme.colorScheme.primary
    Card(onClick = onClick, colors = meshCardColors()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(accent.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Verified, null, tint = accent)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.node_check_node_check), style = MaterialTheme.typography.titleMedium)
                if (report != null) {
                    Text(report.headline, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                    val issues = report.findings.count { it.level == NodeCheckLevel.BAD || it.level == NodeCheckLevel.WARN }
                    Text(if (issues == 0) stringResource(R.string.node_check_findings_and_live_test) else pluralStringResource(R.plurals.node_check_issues_count, issues, issues),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (loading) stringResource(R.string.node_check_checking_whether_observers_hear_it) else stringResource(R.string.node_check_is_it_getting_out_findings_and_a_live),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
