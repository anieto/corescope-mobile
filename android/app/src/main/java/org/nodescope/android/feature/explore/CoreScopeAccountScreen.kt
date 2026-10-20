package org.nodescope.android.feature.explore

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
    var message by mutableStateOf<String?>(null)
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
            "Earlier changes synced. Newer edits are saved on this device; sync again after one minute."
        else if (upload) "Account favorites synced." else "Account favorites refreshed. Device favorites are unchanged."
    }

    suspend fun importDeviceFavorites(library: NodeLibrary) = action {
        if (resetDocument != null) throw AccountException(AccountException.Kind.RESET)
        val state = load() ?: throw AccountException(AccountException.Kind.RESPONSE)
        val keys = library.nodeKeys()
        if (!keys.all(CoreScopeFavoriteMerge::validKey)) throw AccountException(AccountException.Kind.RESPONSE)
        persist(state.copy(favorites = state.favorites + keys))
        message = "Account edits saved on this device. Choose Sync now to upload them."
    }

    suspend fun removeFavorite(key: String) = action {
        if (resetDocument != null) throw AccountException(AccountException.Kind.RESET)
        val state = load() ?: throw AccountException(AccountException.Kind.RESPONSE)
        persist(state.copy(favorites = state.favorites - key))
        message = "Account edits saved on this device. Choose Sync now to upload them."
    }

    suspend fun resolveReset(restore: Boolean) = action {
        val remote = resetDocument ?: return@action
        val state = load() ?: return@action
        persist(if (restore) state.restore(remote) else CoreScopeAccountSelectionState.from(remote))
        resetDocument = null
        message = if (restore) "Selections restored locally. Choose Sync now to upload them." else "Using the server’s selections."
    }

    private fun copy(library: NodeLibrary) {
        val state = load() ?: return
        val claimed = CoreScopeFavoriteMerge.selection(state.baseline).claimed
        library.applyAccountNodes(library.nodeKeys() + state.favorites + claimed.keys, claimed)
        message = "Account selections copied to device favorites."
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
        message = if (failed) "Signed out on this device. Server revocation was not confirmed; the session may remain active until revoked on CoreScope or expired."
            else "Signed out. Device favorites are unchanged."
    }

    private suspend fun action(block: suspend () -> Unit) {
        if (busy) return
        val activeOrigin = origin
        if (epoch != epochs[origin]) {
            user = null; selection = null; resetDocument = null; supported = false
            message = "The account session changed in another window. Reopen this page to continue."
            return
        }
        if (!activeOrigins.add(activeOrigin)) { message = "An account operation is already running. Try again shortly."; return }
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
                is AccountException -> error.message
                is BeaconApiException.RateLimited -> "This server is limiting requests. Try again later; your edits are saved."
                else -> "The account request could not be completed. Try again."
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
        title = { Text("Import device favorites?") },
        text = { Text("Add device node favorites to this account’s pending selections. Choose Sync now to upload them. Observer and channel favorites stay local.") },
        confirmButton = { TextButton(onClick = { showsImport = false; scope.launch { model.importDeviceFavorites(library) } }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { showsImport = false }) { Text("Cancel") } })
    if (showsSignOut) AlertDialog(onDismissRequest = { showsSignOut = false },
        title = { Text("Sign out of CoreScope?") },
        text = { Text("Downloaded account selections and pending account edits will be removed from this device. Existing device favorites are preserved.") },
        confirmButton = { Column {
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout() } }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout(library) } }) { Text("Copy selections and sign out") }
        } },
        dismissButton = { TextButton(onClick = { showsSignOut = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AccountCard("Account connection", Icons.Outlined.AccountCircle) {
                FavoriteSourceIdentity(host, sourceName)
                Text("Manage account node selections separately from favorites saved on this device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (model.busy || model.message != null) item {
            AccountCard(null, null) {
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                model.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        val user = model.user
        val paused = model.resetDocument != null
        if (user != null) {
            item {
                AccountCard("Signed in", Icons.Outlined.CheckCircle) {
                    Text(user.displayName.ifBlank { user.email }, style = MaterialTheme.typography.titleMedium)
                    if (user.displayName.isNotBlank() && user.displayName != user.email) Text(user.email,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (paused) item {
                AccountCard("Account settings were reset", Icons.Outlined.WarningAmber) {
                    Text("Sync is paused. Use the server’s selections or restore your saved account favorites. Device favorites are unaffected.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { scope.launch { model.resolveReset(false) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Use server selections") }
                    OutlinedButton(onClick = { scope.launch { model.resolveReset(true) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Restore saved account favorites") }
                }
            }
            item {
                AccountCard("Sync & device favorites", Icons.Outlined.Sync) {
                    Text("Sync uploads only account edits. Device favorites stay local unless you import them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { scope.launch { model.sync(true) } }, enabled = !model.busy && !paused, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                    OutlinedButton(onClick = { scope.launch { model.sync(false) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh from account") }
                    TextButton(onClick = { showsImport = true }, enabled = !model.busy && !paused) { Text("Import device node favorites") }
                    TextButton(onClick = { scope.launch { model.copyToDevice(library) } }, enabled = !model.busy) { Text("Copy account selections to device") }
                }
            }
            item {
                AccountCard("Account nodes", Icons.Outlined.StarBorder) {
                    Text("${model.selection?.favorites?.size ?: 0} favorites · ${model.claimed.size} claimed nodes",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    model.selection?.favorites?.sorted()?.forEach { key ->
                        val name = resolvedNames[key] ?: library.node(key)?.name?.takeIf { it.isNotBlank() }
                        AccountNodeRow(name, key) {
                            TextButton(onClick = { scope.launch { model.removeFavorite(key) } }, enabled = !model.busy && !paused,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.semantics { contentDescription = "Remove account favorite ${name ?: key}" }) { Text("Remove") }
                        }
                    }
                    if (model.claimed.isNotEmpty()) {
                        Text("Claimed nodes are managed on CoreScope. Removing an account favorite does not unclaim a node.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        model.claimed.toSortedMap().forEach { (key, name) ->
                            AccountNodeRow(name.ifBlank { resolvedNames[key] ?: library.node(key)?.name ?: "" }.takeIf { it.isNotBlank() }, key) {}
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showsSignOut = true }, enabled = !model.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sign out") }
            }
        } else if (model.supported) item {
            AccountCard("Sign in", Icons.AutoMirrored.Outlined.Login) {
                OutlinedTextField(email, { email = it }, label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    enabled = !model.busy, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    enabled = !model.busy, singleLine = true)
                Button(onClick = {
                    val secret = password
                    password = ""
                    scope.launch { model.login(email, secret, library) }
                }, enabled = !model.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                Text("Use an existing account on this source. Your password is not saved.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uri.openUri("https://${normalizeHost(host)}") }) {
                    Text("Open CoreScope")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Opens in your browser", Modifier.size(18.dp))
                }
            }
        } else if (!model.busy) item {
            OutlinedButton(onClick = { scope.launch { model.open(library) } }) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Try again")
            }
        }
    }
}

/** Settings-style panel: small primary icon and label heading a surface card. */
@Composable
private fun AccountCard(title: String?, icon: ImageVector?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            Text(name ?: "Name unavailable", style = MaterialTheme.typography.titleSmall,
                color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text("${key.take(12)}…${key.takeLast(6)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = "Public key $key" })
        }
        trailing()
    }
}
