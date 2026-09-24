package org.nodescope.android.feature.channels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.nodescope.android.core.design.*
import org.nodescope.android.core.model.AnalyzerSelection
import org.nodescope.android.core.network.LiveFeedState

@Composable
fun ChannelDetailScreen(
    model: ChannelsViewModel, channelId: String, channelName: String, selection: AnalyzerSelection,
    feed: LiveFeedState, onRemoved: () -> Unit, onPacket: (hash: String, sender: String, text: String) -> Unit = { _, _, _ -> },
    favorite: Boolean = false, onFavorite: () -> Unit = {},
) {
    val monitoredChannels by model.monitor.channels.collectAsStateWithLifecycle()
    val monitored = remember(monitoredChannels, channelId, channelName) { model.monitor.matching(channelId, channelName) }
    val messageState by model.messages.state.collectAsStateWithLifecycle()
    val packetState by model.packets.state.collectAsStateWithLifecycle()
    val messageKey = selection.host to channelId
    val now = rememberNow()

    fun refresh(force: Boolean) {
        if (monitored != null) model.packets.load(selection, force) else model.messages.load(messageKey, force)
    }
    LaunchedEffect(selection, channelId, monitored != null) { refresh(false) }
    LiveChannelRefresh(feed) { refresh(true) }

    val loadState = if (monitored != null) packetState else messageState
    val raw = remember(monitored, packetState, messageState) {
        if (monitored != null) monitoredConversation(packetState.value.takeIf { packetState.key == selection }.orEmpty(), monitored)
        else messageState.value.takeIf { messageState.key == messageKey }.orEmpty().map { it.toConversation() }
    }
    // Monitored packets are already region-scoped by the server; server channel messages are not.
    val regional = if (monitored != null) raw else regionFiltered(raw, selection.region, feed.observers)
    val conversation = remember(regional ?: raw) { chronological(regional ?: raw).asReversed() }
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove && monitored != null) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("Stop monitoring ${monitored.title}?") },
        text = { Text("This removes the channel and its key from this device. The analyzer is not affected.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; runCatching { model.monitor.remove(monitored.channelName) }.onSuccess { onRemoved() } }) { Text("Stop monitoring") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${conversation.size} message${if (conversation.size == 1) "" else "s"} · ${selection.region ?: "Entire network"}",
                        style = MaterialTheme.typography.labelMedium)
                    Text(if (monitored != null) "Decrypted on this device from recent traffic" else "Monitored by the analyzer",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (favorite) "Remove channel from favorites" else "Add channel to favorites",
                        tint = if (favorite) Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChannelActions(channelName, channelId, monitored != null, onRefresh = { refresh(true) }, onRemove = { confirmRemove = true })
            }
            LoadStatus(loadState, raw.isNotEmpty(), now) { refresh(true) }
            if (regional == null) Text("Observer regions are unavailable, so messages from every region are shown.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (conversation.isEmpty() && !loadState.loading) EmptyState(Icons.Outlined.ChatBubbleOutline,
            if (selection.region == null) "No messages yet" else "No messages from this region",
            if (monitored != null) "Only recent traffic can be decrypted. New messages appear as the analyzer hears them." else "New messages appear as the analyzer hears them.")
        // Newest first with a reversed layout keeps the latest message at the bottom, like a chat.
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(conversation, key = { it.first.id }) { (message, trailing) ->
                ChatBubble(message, trailing, onPacket = message.packetHash?.let { hash -> { onPacket(hash, message.sender, message.text) } })
            }
        }
    }
}

@Composable
private fun ChannelActions(name: String, channelId: String, monitored: Boolean, onRefresh: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val copy = rememberCopyAction()
    val context = androidx.compose.ui.platform.LocalContext.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "Channel actions") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { open = false; onRefresh() })
            DropdownMenuItem(text = { Text("Copy channel name") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { open = false; copy("Channel name", name, false) })
            DropdownMenuItem(text = { Text("Share channel link") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                open = false
                // Same link format as iOS (`nodescope://channel/<identifier>`).
                val link = org.nodescope.android.core.model.NodeScopeLink.channel(channelId).url
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link), "Share channel link"))
            })
            if (monitored) DropdownMenuItem(text = { Text("Stop monitoring") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { open = false; onRemove() })
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage, trailing: Boolean, onPacket: (() -> Unit)?) {
    val tone = senderColor(message.sender)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (trailing) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = if (trailing) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(message.sender, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tone)
            Surface(color = tone.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, tone.copy(alpha = 0.25f))) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer { Text(linkified(message.text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge) }
                    Text(shortDateTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (message.repeats > 1) MetricChip("Heard ${message.repeats}×", Icons.Outlined.Hearing, MaterialTheme.colorScheme.primary)
                        message.hops?.let { MetricChip("$it hop${if (it == 1) "" else "s"}", Icons.Outlined.Route) }
                        message.snr?.let { MetricChip("%.1f dB".format(it), Icons.Outlined.GraphicEq) }
                    }
                    onPacket?.let { open ->
                        TextButton(onClick = open, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.Outlined.Route, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                            Text("View packet", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private val urlPattern = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

/** Only web links are made tappable; they open in the system browser. */
private fun linkified(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    urlPattern.findAll(text).forEach { match ->
        val url = match.value.trimEnd('.', ',', ')', '!', '?', ';', ':')
        append(text.substring(index, match.range.first))
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(url) }
        index = match.range.first + url.length
    }
    append(text.substring(index))
}

private val lightSenderTones = listOf(0xFF0063A6, 0xFF8A4B00, 0xFF1B7A43, 0xFF9C2F6B, 0xFF5A4AB5, 0xFF00707A, 0xFFA23B2A, 0xFF52667A)
private val darkSenderTones = listOf(0xFF81C3FF, 0xFFFFB866, 0xFF6FD69B, 0xFFF08BC0, 0xFFB3A6FF, 0xFF5FD3DC, 0xFFFF9A85, 0xFFA9BCCF)

/** Stable per-sender tone, readable on the theme's surface. */
@Composable
private fun senderColor(sender: String): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.3f
    val tones = if (dark) darkSenderTones else lightSenderTones
    return Color(tones[Math.floorMod(sender.hashCode(), tones.size)])
}
