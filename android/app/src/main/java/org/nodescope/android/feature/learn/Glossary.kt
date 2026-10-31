package org.nodescope.android.feature.learn

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class GlossaryEntry(val id: String, val term: String, val summary: String, val detail: String? = null)
@Serializable data class GlossarySection(val title: String, val entries: List<GlossaryEntry>)
@Serializable private data class GlossaryDocument(val version: Int, val sections: List<GlossarySection>)

/** A term NodeScope explains, named by its entry's id in glossary.json. */
enum class GlossaryTerm(val id: String) {
    NODE("node"), COMPANION("companion"), REPEATER("repeater"), ROOM_SERVER("room-server"), SENSOR("sensor"),
    OBSERVER("observer"), ADVERT("advert"), ANALYZER("analyzer"),
    HOP("hop"), ROUTE("route"), UNRESOLVED_HOP("unresolved-hop"), FLOOD("flood"), OBSERVATION("observation"),
    HEARD_BY("heard-by"), ALONG_THE_WAY("along-the-way"),
    SNR("snr"), RSSI("rssi"),
    CHANNEL("channel"), REGION("region"), LAST_HEARD("last-heard"),
    LINE_OF_SIGHT("line-of-sight"), FRESNEL_ZONE("fresnel-zone"), CLEARANCE("clearance"), LINK_MARGIN("link-margin");

    companion object {
        /** The entry for a node's role as the analyzer reports it ("repeater", "room", …). */
        fun forRole(role: String): GlossaryTerm? = when (role.lowercase()) {
            "companion" -> COMPANION
            "repeater" -> REPEATER
            "room", "room server", "room_server", "roomserver" -> ROOM_SERVER
            "sensor" -> SENSOR
            else -> null
        }
    }
}

/**
 * The plain-language explanations behind the app's info buttons and Glossary screen, from
 * assets/glossary.json. The text is shared with iOS; a test keeps the two copies identical.
 */
object Glossary {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cached: List<GlossarySection>? = null

    fun parse(text: String): List<GlossarySection> = json.decodeFromString<GlossaryDocument>(text).sections

    fun sections(context: Context): List<GlossarySection> = cached ?: runCatching {
        parse(context.assets.open("glossary.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList()).also { cached = it }

    fun entry(context: Context, term: GlossaryTerm): GlossaryEntry? =
        sections(context).asSequence().flatMap { it.entries }.firstOrNull { it.id == term.id }
}

/**
 * An ⓘ beside a term. It opens a short explanation from the glossary, with a way on to the full
 * Glossary, which opens in a sheet at that term.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnButton(term: GlossaryTerm, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entry = remember(term) { Glossary.entry(context, term) } ?: return
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    var showsGlossary by rememberSaveable { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                title = { Text(entry.term) },
                action = {
                    TextButton(onClick = {
                        tooltip.dismiss()
                        showsGlossary = true
                    }) { Text("More in Glossary") }
                },
            ) { Text(entry.summary) }
        },
        state = tooltip,
        modifier = modifier,
    ) {
        // A plain clickable icon rather than an IconButton, whose 48 dp minimum would make the rows
        // it sits in taller.
        Icon(
            Icons.Outlined.Info, contentDescription = null,
            Modifier.clip(CircleShape)
                .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                .semantics { contentDescription = "About ${entry.term}" }
                .padding(4.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showsGlossary) {
        ModalBottomSheet(onDismissRequest = { showsGlossary = false }) {
            GlossaryList(focus = term, modifier = Modifier.fillMaxHeight(0.9f))
        }
    }
}

/** Every term NodeScope explains, as the Settings → Glossary screen. */
@Composable
fun GlossaryScreen() {
    GlossaryList(focus = null, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GlossaryList(focus: GlossaryTerm?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sections = remember { Glossary.sections(context) }
    val state = rememberLazyListState()
    // Rows are the intro, then a header and its entries per section; open at the focused entry.
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        var index = 1
        for (section in sections) {
            index += 1
            val found = section.entries.indexOfFirst { it.id == target.id }
            if (found >= 0) { state.scrollToItem(index + found); return@LaunchedEffect }
            index += section.entries.size
        }
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Short explanations of the words NodeScope uses. Tap ⓘ beside a term anywhere in the app to see its entry.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sections.forEach { section ->
            item {
                Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp))
            }
            items(section.entries, key = { it.id }) { entry ->
                val focused = entry.id == focus?.id
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.term, style = MaterialTheme.typography.titleMedium)
                        Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                        entry.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** A label followed by its ⓘ. */
@Composable
fun LearnLabel(text: String, term: GlossaryTerm, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = style, color = color)
        LearnButton(term)
    }
}
