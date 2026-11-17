package org.nodescope.android.feature.explore

import org.nodescope.android.core.design.UiText
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
import org.nodescope.android.core.design.MonaspaceNeon
import org.nodescope.android.core.design.meshCardColors
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelChildren
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nodescope.android.core.model.CoreScopeAccountSettings
import org.nodescope.android.core.model.CoreScopeAccountSelectionState
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.NodeLibrary
import org.nodescope.android.core.storage.AccountSelectionStore

internal class CoreScopeAccountModel(private val context: Context, private val host: String) {
    var user by mutableStateOf<CoreScopeAccountUser?>(null)
        private set
    var supported by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiText?>(null)
        private set
    var selection by mutableStateOf<CoreScopeAccountSelectionState?>(null)
        private set
    var resetDocument by mutableStateOf<CoreScopeAccountSettings?>(null)
        private set
    val claimed: Map<String, String> get() = selection?.let { CoreScopeFavoriteMerge.selection(it.baseline).claimed }.orEmpty()
    private var client: CoreScopeAccountClient? = null
    private var epoch: String? = epochs["https://${normalizeHost(host)}/"]
    private val json = Json { ignoreUnknownKeys = true }
    private val store = AccountSelectionStore.shared(context)
    private val origin get() = client?.origin?.toString() ?: "https://${normalizeHost(host)}/"

    suspend fun open(library: NodeLibrary) = action {
        val client = CoreScopeAccountClient.forSource(context, host)
        this.client = client
        supported = client.supportsAccounts()
        if (!supported) throw AccountException(AccountException.Kind.UNSUPPORTED)
        // Like iOS, only a restored session is validated; a first visit goes straight to sign-in.
        if (client.hasSession) {
            user = client.me()
            reconcile(false)
        } else store.deactivate(origin)
    }

    suspend fun login(email: String, password: String, library: NodeLibrary) = action {
        val client = client ?: return@action
        check(supported)
        user = client.login(email.trim(), password)
        epoch = java.util.UUID.randomUUID().toString()
        epochs[origin] = checkNotNull(epoch)
        selection = null
        resetDocument = null
        reconcile(false)
    }

    suspend fun sync(upload: Boolean) = action {
        val previous = user ?: throw AccountException(AccountException.Kind.SIGNED_OUT)
        val authenticated = client?.me() ?: throw AccountException(AccountException.Kind.SIGNED_OUT)
        if (previous.id != authenticated.id) throw AccountException(AccountException.Kind.SIGNED_OUT)
        user = authenticated
        reconcile(upload)
    }

    private fun load(): CoreScopeAccountSelectionState? {
        val id = user?.id ?: throw AccountException(AccountException.Kind.SIGNED_OUT)
        return store.load(origin, id)
    }

    private fun persist(state: CoreScopeAccountSelectionState) {
        val id = user?.id ?: throw AccountException(AccountException.Kind.SIGNED_OUT)
        store.save(origin, id, state)
        selection = state
    }

    private suspend fun reconcile(upload: Boolean) {
        val client = checkNotNull(client)
        val user = checkNotNull(user)
        store.activate(origin, user.id)
        selection = load()
        val remote = client.settings()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val saved = load()
        if (saved != null && saved.baseline.generation != remote.generation) {
            resetDocument = remote
            throw AccountException(AccountException.Kind.RESET)
        }
        resetDocument = null
        val next = (saved ?: CoreScopeAccountSelectionState.from(remote)).refresh(remote)
        persist(next)
        if (upload && next.hasPendingEdits) {
            val (document, _) = CoreScopeFavoriteMerge.document(next.baseline, next.favorites, remote)
            if (document.keys != remote.doc?.keys.orEmpty()) {
                val accepted = client.put(document, remote, user.csrfToken)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                persist((load() ?: next).acknowledge(accepted, next.favorites))
            }
        }
        message = if (upload && selection?.hasPendingEdits == true)
            UiText.Resource(R.string.explore_earlier_changes_synced_newer_edits_are_saved_on)
        else if (upload) UiText.Resource(R.string.explore_account_favorites_synced) else UiText.Resource(R.string.explore_account_favorites_refreshed_device_favorites_are_unchanged)
    }

    suspend fun importDeviceFavorites(library: NodeLibrary) = action {
        if (resetDocument != null) throw AccountException(AccountException.Kind.RESET)
        val state = load() ?: throw AccountException(AccountException.Kind.RESPONSE)
        val keys = library.nodeKeys()
        if (!keys.all(CoreScopeFavoriteMerge::validKey)) throw AccountException(AccountException.Kind.RESPONSE)
        persist(state.copy(favorites = state.favorites + keys))
        message = UiText.Resource(R.string.explore_account_edits_saved_on_this_device_choose_sync)
    }

    suspend fun removeFavorite(key: String) = action {
        if (resetDocument != null) throw AccountException(AccountException.Kind.RESET)
        val state = load() ?: throw AccountException(AccountException.Kind.RESPONSE)
        persist(state.copy(favorites = state.favorites - key))
        message = UiText.Resource(R.string.explore_account_edits_saved_on_this_device_choose_sync)
    }

    suspend fun resolveReset(restore: Boolean) = action {
        val remote = resetDocument ?: return@action
        val state = load() ?: return@action
        persist(if (restore) state.restore(remote) else CoreScopeAccountSelectionState.from(remote))
        resetDocument = null
        message = if (restore) UiText.Resource(R.string.explore_selections_restored_locally_choose_sync_now_to_upload) else UiText.Resource(R.string.explore_using_the_server_s_selections)
    }

    private fun copy(library: NodeLibrary) {
        val state = load() ?: return
        val claimed = CoreScopeFavoriteMerge.selection(state.baseline).claimed
        library.applyAccountNodes(library.nodeKeys() + state.favorites + claimed.keys, claimed)
        message = UiText.Resource(R.string.explore_account_selections_copied_to_device_favorites)
    }

    suspend fun copyToDevice(library: NodeLibrary) = action { copy(library) }

    suspend fun logout(copyTo: NodeLibrary? = null) = action {
        val id = user?.id ?: return@action
        if (copyTo != null) copy(copyTo)
        var failed = false
        try { client?.logout() }
        catch (error: Exception) {
            if (error is AccountException && error.kind == AccountException.Kind.STORAGE) throw error
            failed = true
        }
        store.remove(origin, id)
        epoch = java.util.UUID.randomUUID().toString()
        epochs[origin] = checkNotNull(epoch)
        user = null
        selection = null
        resetDocument = null
        message = if (failed) UiText.Resource(R.string.explore_signed_out_on_this_device_server_revocation_was)
            else UiText.Resource(R.string.explore_signed_out_device_favorites_are_unchanged)
    }

    private suspend fun action(block: suspend () -> Unit) {
        if (busy) return
        val activeOrigin = origin
        if (epoch != epochs[origin]) {
            user = null; selection = null; resetDocument = null; supported = false
            message = UiText.Resource(R.string.explore_the_account_session_changed_in_another_window_reopen)
            return
        }
        if (!activeOrigins.add(activeOrigin)) { message = UiText.Resource(R.string.explore_an_account_operation_is_already_running_try_again); return }
        busy = true
        message = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (error is AccountException && error.kind == AccountException.Kind.SIGNED_OUT) {
                store.deactivate(origin)
                user = null; selection = null; resetDocument = null
            }
            message = when (error) {
                is AccountException -> error.message?.let(UiText::Raw)
                is BeaconApiException.RateLimited -> UiText.Resource(R.string.explore_this_server_is_limiting_requests_try_again_later)
                else -> UiText.Resource(R.string.explore_the_account_request_could_not_be_completed_try)
            }
        } finally { busy = false; activeOrigins.remove(activeOrigin) }
    }

    companion object {
        private val epochs = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val activeOrigins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}

@Composable
fun CoreScopeAccountScreen(host: String, sourceName: String?, library: NodeLibrary, acquire: (() -> org.nodescope.android.core.storage.MyNodesRepositories)? = null) {
    val context = LocalContext.current
    val model = remember(host) { CoreScopeAccountModel(context.applicationContext, host) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var resolvedNames by remember(host) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val accountKeys = (model.selection?.favorites.orEmpty() + model.claimed.keys).sorted()
    val lifecycleForNames = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host, accountKeys, lifecycleForNames) {
        lifecycleForNames.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val missing = accountKeys.filter { key ->
                model.claimed[key].isNullOrBlank() && library.node(key)?.name.isNullOrBlank() && resolvedNames[key].isNullOrBlank()
            }
            if (missing.isNotEmpty()) try {
                val repository = acquire?.invoke() ?: return@repeatOnLifecycle
                val rows = repository.base.load(missing)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                repository.lifetime.check()
                val nodes = rows.mapNotNull { it.snapshot?.node }
                library.refreshNodes(nodes)
                resolvedNames = resolvedNames + nodes.mapNotNull { node -> node.name?.takeIf { it.isNotBlank() }?.let { node.publicKey.lowercase() to it } }.toMap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep stable public keys when metadata is unavailable. */ }
        }
    }
    var showsImport by remember { mutableStateOf(false) }
    var showsSignOut by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.open(library) }
    }
    DisposableEffect(lifecycle, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { scope.coroutineContext.cancelChildren(); password = "" }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); scope.coroutineContext.cancelChildren(); password = "" }
    }
    if (showsImport) AlertDialog(onDismissRequest = { showsImport = false },
        title = { Text(stringResource(R.string.explore_import_device_favorites)) },
        text = { Text(stringResource(R.string.explore_add_device_node_favorites_to_this_account_s)) },
        confirmButton = { TextButton(onClick = { showsImport = false; scope.launch { model.importDeviceFavorites(library) } }) { Text(stringResource(R.string.explore_import)) } },
        dismissButton = { TextButton(onClick = { showsImport = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (showsSignOut) AlertDialog(onDismissRequest = { showsSignOut = false },
        title = { Text(stringResource(R.string.explore_sign_out_of_corescope)) },
        text = { Text(stringResource(R.string.explore_downloaded_account_selections_and_pending_account_edits_will)) },
        confirmButton = { Column {
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout() } }) { Text(stringResource(R.string.explore_sign_out), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout(library) } }) { Text(stringResource(R.string.explore_copy_selections_and_sign_out)) }
        } },
        dismissButton = { TextButton(onClick = { showsSignOut = false }) { Text(stringResource(R.string.common_cancel)) } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AccountCard(stringResource(R.string.explore_account_connection), Icons.Outlined.AccountCircle) {
                FavoriteSourceIdentity(host, sourceName)
                Text(stringResource(R.string.explore_manage_account_node_selections_separately_from_favorites_saved),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (model.busy || model.message != null) item {
            AccountCard(null, null) {
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                model.message?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        val user = model.user
        val paused = model.resetDocument != null
        if (user != null) {
            item {
                AccountCard(stringResource(R.string.explore_signed_in), Icons.Outlined.CheckCircle) {
                    Text(user.displayName.ifBlank { user.email }, style = MaterialTheme.typography.titleMedium)
                    if (user.displayName.isNotBlank() && user.displayName != user.email) Text(user.email,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (paused) item {
                AccountCard(stringResource(R.string.explore_account_settings_were_reset), Icons.Outlined.WarningAmber) {
                    Text(stringResource(R.string.explore_sync_is_paused_use_the_server_s_selections),
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { scope.launch { model.resolveReset(false) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore_use_server_selections)) }
                    OutlinedButton(onClick = { scope.launch { model.resolveReset(true) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore_restore_saved_account_favorites)) }
                }
            }
            item {
                AccountCard(stringResource(R.string.explore_sync_device_favorites), Icons.Outlined.Sync) {
                    Text(stringResource(R.string.explore_sync_uploads_only_account_edits_device_favorites_stay),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { scope.launch { model.sync(true) } }, enabled = !model.busy && !paused, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore_sync_now)) }
                    OutlinedButton(onClick = { scope.launch { model.sync(false) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore_refresh_from_account)) }
                    TextButton(onClick = { showsImport = true }, enabled = !model.busy && !paused) { Text(stringResource(R.string.explore_import_device_node_favorites)) }
                    TextButton(onClick = { scope.launch { model.copyToDevice(library) } }, enabled = !model.busy) { Text(stringResource(R.string.explore_copy_account_selections_to_device)) }
                }
            }
            item {
                AccountCard(stringResource(R.string.explore_account_nodes), Icons.Outlined.StarBorder) {
                    Text(stringResource(R.string.explore_favorites_claimed_nodes, model.selection?.favorites?.size ?: 0, model.claimed.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    model.selection?.favorites?.sorted()?.forEach { key ->
                        val name = resolvedNames[key] ?: library.node(key)?.name?.takeIf { it.isNotBlank() }
                        AccountNodeRow(name, key) {
                            TextButton(onClick = { scope.launch { model.removeFavorite(key) } }, enabled = !model.busy && !paused,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.withDescription(stringResource(R.string.explore_remove_account_favorite, name ?: key)) { description -> contentDescription = description }) { Text(stringResource(R.string.explore_remove)) }
                        }
                    }
                    if (model.claimed.isNotEmpty()) {
                        Text(stringResource(R.string.explore_claimed_nodes_are_managed_on_corescope_removing_an),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        model.claimed.toSortedMap().forEach { (key, name) ->
                            AccountNodeRow(name.ifBlank { resolvedNames[key] ?: library.node(key)?.name ?: "" }.takeIf { it.isNotBlank() }, key) {}
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showsSignOut = true }, enabled = !model.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.explore_sign_out)) }
            }
        } else if (model.supported) item {
            AccountCard(stringResource(R.string.explore_sign_in), Icons.AutoMirrored.Outlined.Login) {
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.explore_email)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    enabled = !model.busy, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.explore_password)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    enabled = !model.busy, singleLine = true)
                Button(onClick = {
                    val secret = password
                    password = ""
                    scope.launch { model.login(email, secret, library) }
                }, enabled = !model.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore_sign_in)) }
                Text(stringResource(R.string.explore_use_an_existing_account_on_this_source_your),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uri.openUri("https://${normalizeHost(host)}") }) {
                    Text(stringResource(R.string.explore_open_corescope))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.explore_opens_in_your_browser), Modifier.size(18.dp))
                }
            }
        } else if (!model.busy) item {
            OutlinedButton(onClick = { scope.launch { model.open(library) } }) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.explore_try_again))
            }
        }
    }
}

/** Settings-style panel: small primary icon and label heading a surface card. */
@Composable
private fun AccountCard(title: String?, icon: ImageVector?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = meshCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Row(Modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let { Icon(it, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun AccountNodeRow(name: String?, key: String, trailing: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name ?: stringResource(R.string.explore_name_unavailable), style = MaterialTheme.typography.titleSmall,
                color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.explore_value, key.take(12), key.takeLast(6)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = MonaspaceNeon,
                modifier = Modifier.withDescription(stringResource(R.string.explore_public_key, key)) { description -> contentDescription = description })
        }
        trailing()
    }
}
