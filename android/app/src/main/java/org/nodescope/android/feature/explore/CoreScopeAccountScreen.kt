package org.nodescope.android.feature.explore

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
        user = client.me()
        reconcile(false)
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
        text = { Text("Account selections and pending edits will be removed from this device. Existing device favorites are preserved.") },
        confirmButton = { Column {
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout() } }) { Text("Sign out") }
            TextButton(onClick = { showsSignOut = false; scope.launch { model.logout(library) } }) { Text("Copy selections and sign out") }
        } },
        dismissButton = { TextButton(onClick = { showsSignOut = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoriteSourceIdentity(host, sourceName)
                    Text("Manage account node selections separately from favorites saved on this device.")
                }
            }
        }
        if (model.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        model.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
        val user = model.user
        if (user != null) item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(user.displayName.ifBlank { user.email }, style = MaterialTheme.typography.titleMedium)
                    Text(user.email)
                    if (model.resetDocument != null) {
                        Text("Account settings were reset", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        Text("Sync is paused. Use the server’s selections or restore your saved account favorites.")
                        Button(onClick = { scope.launch { model.resolveReset(false) } }, enabled = !model.busy) { Text("Use server selections") }
                        OutlinedButton(onClick = { scope.launch { model.resolveReset(true) } }, enabled = !model.busy) { Text("Restore saved account favorites") }
                    }
                    Button(onClick = { scope.launch { model.sync(true) } }, enabled = !model.busy && model.resetDocument == null) { Text("Sync now") }
                    OutlinedButton(onClick = { scope.launch { model.sync(false) } }, enabled = !model.busy) { Text("Refresh from account") }
                    Text("Sync uploads only account edits. Device favorites stay local unless you import them.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showsImport = true }, enabled = !model.busy && model.resetDocument == null) { Text("Import device node favorites") }
                    TextButton(onClick = { scope.launch { model.copyToDevice(library) } }, enabled = !model.busy) { Text("Copy account selections to device") }
                    HorizontalDivider()
                    Text("Account nodes", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text("${model.selection?.favorites?.size ?: 0} favorites · ${model.claimed.size} claimed nodes")
                    model.selection?.favorites?.sorted()?.forEach { key ->
                        Text(resolvedNames[key] ?: library.node(key)?.name?.takeIf { it.isNotBlank() } ?: "Name unavailable",
                            style = MaterialTheme.typography.titleSmall)
                        Text("${key.take(12)}…${key.takeLast(6)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { model.removeFavorite(key) } }, modifier = Modifier.semantics { contentDescription = "Remove account favorite ${resolvedNames[key] ?: library.node(key)?.name ?: key}" }, enabled = !model.busy && model.resetDocument == null) { Text("Remove account favorite") }
                    }
                    if (model.claimed.isNotEmpty()) {
                        Text("Claimed nodes are managed on CoreScope. Removing a favorite does not unclaim a node.", style = MaterialTheme.typography.bodySmall)
                        model.claimed.toSortedMap().forEach { (key, name) ->
                            Text(name.ifBlank { resolvedNames[key] ?: library.node(key)?.name ?: "Name unavailable" })
                            Text("${key.take(12)}…${key.takeLast(6)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { showsSignOut = true }, enabled = !model.busy) { Text("Sign out") }
                }
            }
        } else if (model.supported) item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), enabled = !model.busy, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !model.busy, singleLine = true)
                    Button(onClick = {
                        val secret = password
                        password = ""
                        scope.launch { model.login(email, secret, library) }
                    }, enabled = !model.busy && email.isNotBlank() && password.isNotEmpty()) { Text("Sign in") }
                    Text("Use an existing account on this source. Your password is not saved.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { uri.openUri("https://${normalizeHost(host)}") }) { Text("Open CoreScope") }
                }
            }
        }
    }
}
