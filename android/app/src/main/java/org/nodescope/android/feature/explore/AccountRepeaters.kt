package org.nodescope.android.feature.explore

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.nodescope.android.R
import org.nodescope.android.core.design.MeshModalBottomSheet
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.parseDecimalInput
import org.nodescope.android.core.design.relativeTime
import org.nodescope.android.core.model.parseInstant
import org.nodescope.android.core.network.AccountException
import org.nodescope.android.core.network.AccountRepeater
import org.nodescope.android.core.network.AccountRepeaterDetails
import org.nodescope.android.core.network.AccountRepeaterEdit
import org.nodescope.android.core.network.CoreScopeAccountClient

@Composable
internal fun rememberRepeaterManagementClient(
    host: String, enabled: Boolean, accountId: Long? = null, retry: Int = 0,
    onFailure: ((String, Exception) -> Unit)? = null,
): CoreScopeAccountClient? {
    val context = LocalContext.current
    var client by remember(host, enabled, accountId, retry) { mutableStateOf<CoreScopeAccountClient?>(null) }
    LaunchedEffect(host, enabled, accountId, retry) {
        if (!enabled) return@LaunchedEffect
        var step = "restore account session"
        try {
            val restored = CoreScopeAccountClient.forSource(context.applicationContext, host)
            if (!restored.hasSession) return@LaunchedEffect
            step = "check repeater capability"
            if (restored.supportsRepeaterManagement()) client = restored
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure?.invoke(step, error)
        }
    }
    return client
}

@Composable
internal fun AccountNodeSummary(favoriteCount: Int, repeaterCount: Int? = null) {
    val favorites = pluralStringResource(R.plurals.account_favorites_count, favoriteCount, favoriteCount)
    val summary = if (repeaterCount == null) favorites else {
        val claims = pluralStringResource(R.plurals.account_claimed_repeaters_count, repeaterCount, repeaterCount)
        stringResource(R.string.account_node_summary, claims, favorites)
    }
    Text(summary, style = MaterialTheme.typography.bodyMedium, fontFamily = MonaspaceNeon,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AccountRepeatersSection(
    host: String, accountId: Long, favoriteKeys: Set<String>, canEditFavorite: Boolean,
    onAddFavorite: (String) -> Unit,
    onRemoveFavorite: (String, String) -> Unit, favoriteRows: @Composable (Set<String>) -> Unit,
) {
    var retry by remember(host, accountId) { mutableIntStateOf(0) }
    var checkFailed by remember(host, accountId, retry) { mutableStateOf(false) }
    val client = rememberRepeaterManagementClient(host, enabled = true, accountId = accountId, retry = retry) { step, error ->
        Log.w("NodeScopeAccount", "My Repeaters: $step failed", error)
        checkFailed = true
    }
    var repeaters by remember(client) { mutableStateOf<List<AccountRepeater>?>(null) }
    var selected by remember(client) { mutableStateOf<AccountRepeater?>(null) }
    var reload by remember(client) { mutableIntStateOf(0) }
    var failed by remember(client) { mutableStateOf(false) }
    LaunchedEffect(client, reload) {
        if (client == null) return@LaunchedEffect
        failed = false
        try {
            repeaters = client.repeaters()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    val visibleRepeaters = if (failed) null else repeaters
    val claimedKeys = visibleRepeaters?.mapTo(mutableSetOf()) { it.pubkey.lowercase() }.orEmpty()
    val showFavoriteRows = favoriteKeys.isEmpty() || favoriteKeys.any { it !in claimedKeys }
    AccountNodeSummary(favoriteKeys.size, repeaters?.size)
    if (checkFailed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.repeater_check_failed), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
        }
        HorizontalDivider()
    } else if (client != null) {
        Text(stringResource(R.string.account_claimed_repeaters), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall)
        val rows = repeaters
        if (failed) {
            Text(stringResource(R.string.repeater_request_failed))
            TextButton(onClick = { reload++ }) { Text(stringResource(R.string.repeater_retry)) }
        } else if (rows == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (rows.isEmpty()) {
            Text(stringResource(R.string.repeater_no_claims))
        } else {
            rows.forEach { repeater ->
                Card(onClick = { selected = repeater }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(repeater.title, style = MaterialTheme.typography.titleMedium)
                            if (!repeater.known || repeater.inactive) Text(stringResource(R.string.repeater_no_longer_listed))
                            val lastHeard = parseInstant(repeater.lastHeard)
                            Text(if (lastHeard == null) stringResource(R.string.repeater_never_heard)
                                else stringResource(R.string.repeater_last_heard, relativeTime(lastHeard)))
                            repeater.status?.let { Text(stringResource(R.string.repeater_list_status, it)) }
                            if (repeater.comaintainers.isNotEmpty()) Text(
                                stringResource(R.string.repeater_comaintainers, repeater.comaintainers.joinToString()),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val color = MaterialTheme.colorScheme.primary
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                                Text(stringResource(R.string.planner_edit), style = MaterialTheme.typography.labelLarge, color = color)
                            }
                            val key = repeater.pubkey.lowercase()
                            val isFavorite = key in favoriteKeys
                            IconButton(
                                onClick = { if (isFavorite) onRemoveFavorite(key, repeater.title) else onAddFavorite(key) },
                                enabled = canEditFavorite,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = if (isFavorite) stringResource(R.string.explore_remove_favorite, repeater.title)
                                        else stringResource(R.string.explore_add_favorite_node),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showFavoriteRows) HorizontalDivider()
        selected?.let { repeater ->
            AccountRepeaterManagementDialog(client, repeater.pubkey, repeater.title) {
                selected = null
                reload++
            }
        }
    }
    if (showFavoriteRows) favoriteRows(claimedKeys)
}

@Composable
internal fun AccountRepeaterManagementDialog(
    client: CoreScopeAccountClient, key: String, name: String, dismiss: () -> Unit,
) {
    var details by remember(client, key) { mutableStateOf<AccountRepeaterDetails?>(null) }
    var height by remember(client, key) { mutableStateOf("") }
    var gain by remember(client, key) { mutableStateOf("") }
    var status by remember(client, key) { mutableStateOf("") }
    var power by remember(client, key) { mutableStateOf("") }
    var notes by remember(client, key) { mutableStateOf("") }
    var busy by remember(client, key) { mutableStateOf(false) }
    var error by remember(client, key) { mutableStateOf<Int?>(null) }
    var conflict by remember(client, key) { mutableStateOf(false) }
    var confirmsClaim by remember(client, key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun update(value: AccountRepeaterDetails) {
        details = value
        height = value.heightM?.toString().orEmpty()
        gain = value.gainDbi.toString()
        status = value.status.orEmpty()
        power = value.power.orEmpty()
        notes = value.notes
        conflict = false
    }
    suspend fun runRequest(operation: suspend () -> AccountRepeaterDetails) {
        if (busy) return
        busy = true
        error = null
        try {
            update(operation())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? AccountException)?.kind == AccountException.Kind.CONFLICT) {
                conflict = true
                error = R.string.repeater_edit_conflict
            } else {
                error = R.string.repeater_request_failed
            }
        } finally {
            busy = false
        }
    }
    fun reload() { scope.launch { runRequest { client.repeaterDetails(key) } } }
    LaunchedEffect(client, key) { runRequest { client.repeaterDetails(key) } }

    val current = details
    val parsedHeight = height.parseDecimalInput()
    val parsedGain = gain.parseDecimalInput()
    val canSave = (height.isBlank() || parsedHeight?.let { it in 0.0..300.0 } == true) &&
        parsedGain?.let { it in -10.0..30.0 } == true && notes.codePointCount(0, notes.length) <= 500

    if (confirmsClaim && current != null) {
        AlertDialog(
            onDismissRequest = { confirmsClaim = false },
            title = { Text(stringResource(if (current.claimedByMe) R.string.repeater_unclaim_confirmation else R.string.repeater_claim_confirmation)) },
            text = {
                if (!current.claimedByMe) Text(stringResource(R.string.repeater_native_claim_guidance))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClaim = false
                    scope.launch {
                        runRequest {
                            client.setRepeaterClaim(key, claimed = !current.claimedByMe)
                            client.repeaterDetails(key)
                        }
                    }
                }) {
                    Text(stringResource(if (current.claimedByMe) R.string.repeater_unclaim else R.string.repeater_claim))
                }
            },
            dismissButton = { TextButton(onClick = { confirmsClaim = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            title = { Text(name, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    if (conflict || (current == null && error != null)) {
                        OutlinedButton(onClick = ::reload, enabled = !busy) {
                            Text(stringResource(if (conflict) R.string.repeater_reload_details else R.string.repeater_retry))
                        }
                    }
                    if (current != null) {
                        if (current.claimedByMe || current.claimers.isEmpty()) {
                            OutlinedButton(onClick = { confirmsClaim = true }, enabled = !busy) {
                                Text(stringResource(if (current.claimedByMe) R.string.repeater_unclaim else R.string.repeater_claim))
                            }
                        }
                        if (current.canEdit) {
                            OutlinedTextField(height, { height = it }, enabled = !busy, singleLine = true,
                                label = { Text(stringResource(R.string.repeater_edit_height)) })
                            OutlinedTextField(gain, { gain = it }, enabled = !busy, singleLine = true,
                                label = { Text(stringResource(R.string.repeater_edit_gain)) })
                            RepeaterEditChoice(R.string.repeater_edit_status, status, !busy,
                                listOf("" to R.string.repeater_unspecified, "active" to R.string.repeater_reported_active,
                                    "maintenance" to R.string.repeater_reported_maintenance, "decommissioned" to R.string.repeater_reported_decommissioned),
                            ) { status = it }
                            RepeaterEditChoice(R.string.repeater_edit_power, power, !busy,
                                listOf("" to R.string.repeater_unspecified, "mains" to R.string.repeater_mains,
                                    "battery" to R.string.repeater_battery, "solar" to R.string.repeater_solar),
                            ) { power = it }
                            OutlinedTextField(notes, { notes = it }, enabled = !busy, maxLines = 4,
                                label = { Text(stringResource(R.string.repeater_notes)) })
                            if (!canSave) Text(stringResource(R.string.repeater_edit_limits))
                        } else {
                            Text(stringResource(R.string.repeater_edit_denied))
                        }
                        Text(
                            stringResource(R.string.repeater_details_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (current?.canEdit == true) Button(enabled = !busy && !conflict && canSave, onClick = {
                    val edit = AccountRepeaterEdit(current.revision, parsedHeight, parsedGain ?: return@Button, status, power, notes)
                    scope.launch { runRequest { client.saveRepeater(key, edit) } }
                }) { Text(stringResource(R.string.repeater_save_details)) }
            },
            dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.repeater_done)) } },
        )
    }
}

@Composable
private fun RepeaterEditChoice(label: Int, value: String, enabled: Boolean, choices: List<Pair<String, Int>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(label))
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(choices.firstOrNull { it.first == value }?.second ?: R.string.repeater_unspecified))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { select(key); expanded = false })
            }
        }
    }
}
