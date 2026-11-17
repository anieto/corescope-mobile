package org.nodescope.android.feature.channels

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.network.BackendCapability

import org.nodescope.android.core.design.senderColor
import org.nodescope.android.core.design.senderBubbleFill
import org.nodescope.android.core.design.senderBubbleStroke
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
    val beacon = LocalBeaconSource.current
    // On Beacon a server channel is never a phone-monitored one, even when the names match.
    val monitored = remember(monitoredChannels, channelId, channelName, beacon) {
        if (beacon && !channelId.startsWith("user:")) null else model.monitor.matching(channelId, channelName)
    }
    val messageState by model.messages.state.collectAsStateWithLifecycle()
    val olderState by model.older.collectAsStateWithLifecycle()
    val packetState by model.packets.state.collectAsStateWithLifecycle()
    val messageKey = selection.host to channelId
    val now = rememberNow()

    fun refresh(force: Boolean) {
        // Beacon has no payloads to decrypt on the phone; its server-decrypted channels load normally.
        if (monitored != null) { if (!beacon) model.packets.load(selection, force) } else model.messages.load(messageKey, force)
    }
    LaunchedEffect(selection, channelId, monitored != null, beacon) { refresh(false) }
    // Beacon announces each decrypted message on its channel, so only that channel reloads;
    // other sources reload after channel-type packets.
    if (beacon && monitored == null) BeaconChannelRefresh(feed, channelId) { refresh(true) }
    else LiveChannelRefresh(feed) { refresh(true) }

    val loadState = if (monitored != null) packetState else messageState
    val raw = remember(monitored, packetState, messageState, olderState) {
        if (monitored != null) monitoredConversation(packetState.value.takeIf { packetState.key == selection }.orEmpty(), monitored)
        else (messageState.value?.takeIf { messageState.key == messageKey }?.messages.orEmpty() +
            olderState.takeIf { it.key == messageKey }?.messages.orEmpty())
            .map { it.toConversation() }.distinctBy { it.id }
    }
    // Server conversations page back on request, as on iOS; monitored ones only ever see recent traffic.
    val shownTotal = messageState.value?.takeIf { messageState.key == messageKey }?.let { newest ->
        val older = olderState.takeIf { it.key == messageKey }
        val loaded = newest.messages.size + older?.messages.orEmpty().size
        (older?.total ?: newest.total)?.takeIf { loaded < it }
    }
    val hasOlder = monitored == null && shownTotal != null
    // Monitored packets are already region-scoped by the server; CoreScope channel messages are not.
    // Beacon messages name no observers, so no region filter can apply.
    val regional = if (monitored != null || beacon) raw else regionFiltered(raw, selection.region, feed.observers)
    val conversation = remember(regional ?: raw) { chronological(regional ?: raw).asReversed() }
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove && monitored != null) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.channels_stop_monitoring, monitored.title)) },
        text = { Text(stringResource(R.string.channels_this_removes_the_channel_and_its_key_from)) },
        confirmButton = { TextButton(onClick = { confirmRemove = false; runCatching { model.monitor.remove(monitored.channelName) }.onSuccess { onRemoved() } }) { Text(stringResource(R.string.channels_stop_monitoring_2)) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) } })

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.channels_message, conversation.size, conversation.size, if (beacon) stringResource(R.string.channels_entire_network_2) else selection.region ?: stringResource(R.string.channels_entire_network)),
                        style = MaterialTheme.typography.labelMedium)
                    Text(if (monitored != null) stringResource(R.string.channels_decrypted_on_this_device_from_recent_traffic) else stringResource(R.string.channels_monitored_by_the_analyzer),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (favorite) stringResource(R.string.channels_remove_channel_from_favorites) else stringResource(R.string.channels_add_channel_to_favorites),
                        tint = if (favorite) Color(0xFFE0A100) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ChannelActions(channelName, channelId, monitored != null, onRefresh = { refresh(true) }, onRemove = { confirmRemove = true })
            }
            if (beacon && monitored != null) BeaconFeatureNotice(BackendCapability.CHANNEL_DECRYPT_ON_PHONE)
            else LoadStatus(loadState, raw.isNotEmpty(), now) { refresh(true) }
            if (regional == null) Text(stringResource(R.string.channels_observer_regions_are_unavailable_so_messages_from_every),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (conversation.isEmpty() && !loadState.loading && loadState.updatedAt != null) EmptyState(Icons.Outlined.ChatBubbleOutline,
            if (selection.region == null || beacon) stringResource(R.string.channels_no_messages_yet) else stringResource(R.string.channels_no_messages_from_this_region),
            if (monitored != null) stringResource(R.string.channels_only_recent_traffic_can_be_decrypted_new_messages) else stringResource(R.string.channels_new_messages_appear_as_the_analyzer_hears_them))
        // Newest first with a reversed layout keeps the latest message at the bottom, like a chat.
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(conversation, key = { it.first.id }) { (message, trailing) ->
                ChatBubble(message, trailing, onPacket = message.packetHash?.let { hash -> { onPacket(hash, message.sender, message.text) } })
            }
            // Last in a reversed list, so it sits above the oldest message.
            if (hasOlder || olderState.key == messageKey && olderState.error != null) item(key = "older") {
                val older = olderState.takeIf { it.key == messageKey }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    older?.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { model.loadOlder(messageKey) }, enabled = older?.loading != true) {
                        Text(if (older?.loading == true) stringResource(R.string.channels_loading_older_messages) else stringResource(R.string.channels_load_older_messages))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelActions(name: String, channelId: String, monitored: Boolean, onRefresh: () -> Unit, onRemove: () -> Unit) {
    val resources = LocalResources.current
    var open by remember { mutableStateOf(false) }
    val copy = rememberCopyAction()
    val context = androidx.compose.ui.platform.LocalContext.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.channels_channel_actions)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { open = false; onRefresh() })
            DropdownMenuItem(text = { Text(stringResource(R.string.channels_copy_channel_name)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { open = false; copy(resources.getString(R.string.channels_channel_name_2), name, false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.channels_share_channel_link)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                open = false
                // Same link format as iOS (`nodescope://channel/<identifier>`).
                val link = org.nodescope.android.core.model.NodeScopeLink.channel(channelId).url
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link), resources.getString(R.string.channels_share_channel_link_2)))
            })
            if (monitored) DropdownMenuItem(text = { Text(stringResource(R.string.channels_stop_monitoring_2)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { open = false; onRemove() })
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage, trailing: Boolean, onPacket: (() -> Unit)?) {
    val tone = senderColor(message.sender)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (trailing) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.86f), horizontalAlignment = if (trailing) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(message.sender, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontFamily = MonaspaceNeon, fontWeight = FontWeight.Bold, color = tone)
            Surface(color = senderBubbleFill(message.sender), shape = MaterialTheme.shapes.medium,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, senderBubbleStroke(message.sender))) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer { Text(linkified(message.text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge) }
                    Text(shortDateTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val metadata = listOfNotNull(
                        if (message.repeats > 1) stringResource(R.string.channels_heard, message.repeats) else null,
                        message.hops?.let { pluralStringResource(R.plurals.channels_hop_count, it, it) },
                        message.snr?.let { "%.1f dB".format(it) },
                    )
                    if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    onPacket?.let { open ->
                        TextButton(onClick = open, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.Outlined.Route, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.channels_view_packet), style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
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
