package org.nodescope.android.core.network

import org.nodescope.android.core.design.UiText
import org.nodescope.android.R

import android.content.Context
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.nodescope.android.core.model.*
import org.nodescope.android.core.storage.KeystoreSecretBox
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class CoreScopeAccountUser(val id: Long, val email: String, val displayName: String, val csrfToken: String)

@Serializable
data class CoreScopeAccountCapabilities(
    val enabled: Boolean, val notifications: Boolean? = null, val repeaterTokens: Boolean? = null,
)

@Serializable
data class CoreScopeWatchedNode(val pubkey: String, val name: String)

@Serializable
data class CoreScopeAccountNotifications(
    val enabled: Boolean, val watches: List<CoreScopeWatchedNode>, val pushRelay: StagingPushCapability? = null,
)

class AccountException(val kind: Kind) : Exception(when (kind) {
    Kind.RESET -> "Account settings were reset on the server. Choose how to continue before syncing."
    Kind.THROTTLED -> "Account writes are limited to once a minute. Your changes are saved; try Sync now later."
    Kind.UNSUPPORTED -> "This source does not support compatible CoreScope accounts."
    Kind.SIGNED_OUT -> "Sign in again to access this account."
    Kind.REJECTED, Kind.PUSH_LINK_GONE -> "The request was rejected. Check your credentials or try again later."
    Kind.WATCH_LIMIT -> "This account reached its watch limit. Stop watching a node before adding another."
    Kind.WATCH_UNKNOWN -> "This analyzer doesn’t know that node yet."
    Kind.CONFLICT -> "Account favorites changed during sync. Try Sync now again."
    Kind.STORAGE -> "The account session could not be saved securely."
    Kind.RESPONSE -> "The account response could not be read. No favorites were changed."
}) {
    val displayText: UiText get() = when (kind) {
        Kind.RESET -> UiText.Resource(R.string.account_error_reset)
        Kind.THROTTLED -> UiText.Resource(R.string.account_error_throttled)
        Kind.UNSUPPORTED -> UiText.Resource(R.string.account_error_unsupported)
        Kind.SIGNED_OUT -> UiText.Resource(R.string.account_error_signed_out)
        Kind.REJECTED, Kind.PUSH_LINK_GONE -> UiText.Resource(R.string.account_error_rejected)
        Kind.WATCH_LIMIT -> UiText.Resource(R.string.account_watch_limit)
        Kind.WATCH_UNKNOWN -> UiText.Resource(R.string.account_watch_unknown)
        Kind.CONFLICT -> UiText.Resource(R.string.account_error_conflict)
        Kind.STORAGE -> UiText.Resource(R.string.account_error_storage)
        Kind.RESPONSE -> UiText.Resource(R.string.account_error_response)
    }

    enum class Kind { UNSUPPORTED, SIGNED_OUT, REJECTED, CONFLICT, RESPONSE, STORAGE, RESET, THROTTLED, PUSH_LINK_GONE, WATCH_LIMIT, WATCH_UNKNOWN }
}

/** Only sealed session tokens go to preferences; passwords are never persisted. */
class AccountSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("corescope-sessions", Context.MODE_PRIVATE)
    private val box = KeystoreSecretBox("nodescope-corescope-sessions")
    fun load(origin: String): String? = preferences.getString(origin, null)?.let {
        String(box.open(Base64.getDecoder().decode(it)), Charsets.UTF_8)
    }
    fun save(origin: String, token: String?) {
        val edit = preferences.edit()
        if (token == null) edit.remove(origin)
        else edit.putString(origin, Base64.getEncoder().encodeToString(box.seal(token.toByteArray())))
        check(edit.commit()) { "Account session could not be saved securely." }
    }
}

/** No shared cookies/cache/interceptors or redirects: credentials never accompany public requests. */
@Serializable
data class AccountRepeater(
    val pubkey: String,
    val name: String,
    val known: Boolean,
    val inactive: Boolean = false,
    val lastHeard: String? = null,
    val status: String? = null,
    val comaintainers: List<String> = emptyList(),
) {
    val title get() = name.ifBlank { pubkey.take(12) }
}

@Serializable
data class AccountRepeaterDetails(
    val pubkey: String,
    val revision: Long,
    val heightM: Double? = null,
    val gainDbi: Double,
    val status: String? = null,
    val power: String? = null,
    val notes: String,
    val claimedByMe: Boolean,
    val canEdit: Boolean,
    val claimers: List<JsonObject>,
)

@Serializable
data class AccountRepeaterEdit(
    val baseRevision: Long,
    val heightM: Double?,
    val gainDbi: Double,
    val status: String,
    val power: String,
    val notes: String,
)

@Serializable
private data class AccountRepeatersResponse(val repeaters: List<AccountRepeater>)

/** No shared cookies/cache/interceptors or redirects: credentials never accompany public requests. */
class CoreScopeAccountClient(
    val origin: HttpUrl,
    cookie: String? = null,
    private val save: (String?) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cookie = cookie
    private val lock = Mutex()
    private fun persist(token: String?) {
        try { save(token) } catch (_: Exception) { throw AccountException(AccountException.Kind.STORAGE) }
    }
    init { require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty()) }

    /** Whether a session was restored or created; without one there is nothing to validate. */
    val hasSession: Boolean get() = cookie != null

    suspend fun supportsRepeaterManagement(): Boolean {
        val capabilities = accountCapabilities()
        return capabilities?.enabled == true && capabilities.repeaterTokens == true
    }

    suspend fun repeaters(): List<AccountRepeater> =
        json.decodeFromString<AccountRepeatersResponse>(request("api/account/repeaters")).repeaters

    suspend fun repeaterDetails(key: String): AccountRepeaterDetails =
        decodeRepeater(request("${repeaterPath(key)}/info"), key)

    suspend fun setRepeaterClaim(key: String, claimed: Boolean) {
        val path = "${repeaterPath(key)}/claim"
        request(path, if (claimed) "POST" else "DELETE", csrf = me().csrfToken)
    }

    suspend fun saveRepeater(key: String, edit: AccountRepeaterEdit): AccountRepeaterDetails =
        decodeRepeater(request("${repeaterPath(key)}/info", "PUT", json.encodeToString(edit), me().csrfToken), key)

    private fun repeaterPath(key: String): String {
        if (!key.matches(Regex("[0-9a-fA-F]{64}"))) throw AccountException(AccountException.Kind.REJECTED)
        return "api/repeaters/${key.lowercase()}"
    }

    private fun decodeRepeater(body: String, key: String): AccountRepeaterDetails =
        json.decodeFromString<AccountRepeaterDetails>(body).also {
            if (!it.pubkey.equals(key, ignoreCase = true) || it.revision < 0) {
                throw AccountException(AccountException.Kind.RESPONSE)
            }
        }

    suspend fun supportsAccounts(): Boolean = accountCapabilities()?.enabled == true

    suspend fun accountCapabilities(): CoreScopeAccountCapabilities? {
        val body = json.parseToJsonElement(request("api/config/client", authenticated = false)).jsonObject
        return body["userManagement"]?.takeUnless { it == JsonNull }
            ?.let { json.decodeFromJsonElement<CoreScopeAccountCapabilities>(it) }
    }

    suspend fun notifications(): CoreScopeAccountNotifications =
        decodeNotifications(request("api/account/notifications"))

    suspend fun setWatch(pubkey: String, watched: Boolean, csrf: String): CoreScopeAccountNotifications {
        val key = pubkey.lowercase()
        require(Regex("[0-9a-f]{64}").matches(key))
        val method = if (watched) "PUT" else "DELETE"
        return decodeNotifications(request("api/account/notifications/watches/$key", method, csrf = csrf))
    }

    private fun decodeNotifications(body: String): CoreScopeAccountNotifications {
        val response = json.parseToJsonElement(body).jsonObject
        val relay = response.pushRelayValue()
        val normalized = if (relay != null && "pushRelay" !in response) {
            JsonObject(response + ("pushRelay" to relay))
        } else response
        return json.decodeFromJsonElement(normalized)
    }

    private fun JsonObject.pushRelayValue(): JsonElement? = this["pushRelay"] ?: this["nodeScopePush"]

    suspend fun login(email: String, password: String): CoreScopeAccountUser {
        val result = request("api/auth/login", "POST", buildJsonObject {
            put("email", email); put("password", password)
        }.toString(), authenticated = false)
        return json.decodeFromString<CoreScopeAccountUser>(result).also {
            if (cookie == null || it.csrfToken.isEmpty()) throw AccountException(AccountException.Kind.RESPONSE)
        }
    }

    suspend fun me(): CoreScopeAccountUser = json.decodeFromString(request("api/auth/me"))

    suspend fun settings(): CoreScopeAccountSettings =
        json.decodeFromString<CoreScopeAccountSettings>(request("api/account/settings")).also {
            CoreScopeFavoriteMerge.selection(it)
        }

    suspend fun put(document: AccountSettingsDocument, base: CoreScopeAccountSettings, csrf: String): CoreScopeAccountSettings {
        CoreScopeAccountWriteGate.reserve(origin.toString())
        val body = buildJsonObject {
            put("baseRevision", base.revision)
            put("baseGeneration", base.generation)
            put("doc", json.encodeToJsonElement(document))
        }
        val ack = json.parseToJsonElement(request("api/account/settings", "PUT", body.toString(), csrf)).jsonObject
        return CoreScopeAccountSettings(ack.getValue("revision").jsonPrimitive.long,
            ack.getValue("generation").jsonPrimitive.content, document, base.allowlist)
    }

    suspend fun pushCapability(): StagingPushCapability? =
        json.parseToJsonElement(request("api/account/notifications")).jsonObject.pushRelayValue()
            ?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<StagingPushCapability>(it) }

    suspend fun linkPushDevice(installation: String, proof: String, csrf: String): StagingPushLink =
        json.decodeFromString(request("api/account/notifications/devices/$installation", "PUT",
            buildJsonObject { put("proof", proof) }.toString(), csrf))

    suspend fun unlinkPushDevice(installation: String, csrf: String) {
        request("api/account/notifications/devices/$installation", "DELETE", csrf = csrf)
    }

    suspend fun logout() {
        try { request("api/auth/logout", "POST") }
        finally { cookie = null; persist(null) }
    }

    private suspend fun request(path: String, method: String = "GET", body: String? = null,
                                csrf: String? = null, authenticated: Boolean = true): String = lock.withLock {
        if (authenticated && cookie == null) throw AccountException(AccountException.Kind.SIGNED_OUT)
        myNodesCoreScopeCooldown.check(origin.toString(), System.currentTimeMillis())
        val url = origin.newBuilder().addPathSegments(path).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json").header("Origin", origin.toString().trimEnd('/'))
            .method(method, if (method == "GET") null else (body ?: "").toRequestBody("application/json".toMediaType()))
        if (authenticated) request.header("Cookie", "cs_session=$cookie")
        csrf?.let { request.header("X-CS-CSRF", it) }
        val result = execute(request.build())
        result.cookies.firstOrNull { it.name == "cs_session" }?.let {
            val value = it.value.takeIf { token -> token.isNotEmpty() && it.expiresAt > System.currentTimeMillis() }
            persist(value)
            cookie = value
        }
        if (result.code == 401) {
            if (authenticated) { cookie = null; persist(null) }
            throw AccountException(if (authenticated) AccountException.Kind.SIGNED_OUT else AccountException.Kind.REJECTED)
        }
        if (result.code == 429) {
            val until = myNodesCoreScopeCooldown.record(origin.toString(), result.retryAfter, System.currentTimeMillis())
            throw BeaconApiException.RateLimited(until)
        }
        if (result.code == 410 && method == "PUT" && path.startsWith("api/account/notifications/devices/")) {
            throw AccountException(AccountException.Kind.PUSH_LINK_GONE)
        }
        if (method == "PUT" && path.startsWith("api/account/notifications/watches/")) {
            if (result.code == 409) throw AccountException(AccountException.Kind.WATCH_LIMIT)
            if (result.code == 404) throw AccountException(AccountException.Kind.WATCH_UNKNOWN)
        }
        if (result.code == 409) throw AccountException(AccountException.Kind.CONFLICT)
        if (result.code !in 200..299) throw AccountException(AccountException.Kind.REJECTED)
        result.body
    }

    private data class Result(val code: Int, val body: String, val cookies: List<Cookie>, val retryAfter: String?)
    private suspend fun execute(request: Request): Result = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val source = response.body?.source() ?: throw AccountException(AccountException.Kind.RESPONSE)
                        source.request(512L * 1024 + 1)
                        if (source.buffer.size > 512L * 1024) throw AccountException(AccountException.Kind.RESPONSE)
                        val value = Result(response.code, source.readUtf8(), Cookie.parseAll(request.url, response.headers), response.header("Retry-After"))
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    companion object {
        fun forSource(context: Context, host: String): CoreScopeAccountClient {
            val origin = "https://${normalizeHost(host)}/".toHttpUrl()
            val storage = AccountSessionStore(context)
            return CoreScopeAccountClient(origin, storage.load(origin.toString()), { storage.save(origin.toString(), it) })
        }
    }
}

object CoreScopeFavoriteMerge {
    private val json = Json { ignoreUnknownKeys = true }
    data class Selection(val favorites: List<String>, val myNodes: Map<String, String>) {
        val keys: Set<String> get() = favorites.map { it.lowercase() }.toSet() + myNodes.keys
    }
    @Serializable private data class MyNode(val pubkey: String, val name: String? = null)

    fun validKey(key: String): Boolean = key.length == 64 && key.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    fun selection(settings: CoreScopeAccountSettings): Selection {
        if (!settings.supportsNodeFavorites || !settings.supportsOwnNodes) throw AccountException(AccountException.Kind.UNSUPPORTED)
        val values = settings.doc?.keys.orEmpty()
        val favorites = json.decodeFromString<List<String>>(values["meshcore-favorites"] ?: "[]")
        val owned = json.decodeFromString<List<MyNode>>(values["meshcore-my-nodes"] ?: "[]")
        if (!(favorites + owned.map { it.pubkey }).all(::validKey)) throw AccountException(AccountException.Kind.RESPONSE)
        return Selection(favorites, owned.associate { it.pubkey.lowercase() to (it.name ?: "") })
    }

    fun merge(base: Set<String>?, local: Set<String>, remote: Set<String>): Set<String> =
        if (base == null) local + remote else (local - base) + (remote - base) + (base intersect local intersect remote)

    fun document(
        base: CoreScopeAccountSettings?, local: Set<String>, remote: CoreScopeAccountSettings,
        removingMyNodes: Set<String> = emptySet(),
    ): Pair<AccountSettingsDocument, Selection> {
        if (base != null && base.generation != remote.generation) throw AccountException(AccountException.Kind.RESET)
        val current = selection(remote)
        val previous = base?.let(::selection)
        val merged = merge(previous?.favorites?.map { it.lowercase() }?.toSet(), local.map { it.lowercase() }.toSet(), current.favorites.map { it.lowercase() }.toSet())
        val raw = current.favorites.filter { it.lowercase() in merged }
        val additions = (merged - raw.map { it.lowercase() }.toSet()).sorted()
        val values = remote.doc?.keys.orEmpty().toMutableMap()
        if (raw + additions != current.favorites) values["meshcore-favorites"] = json.encodeToString(raw + additions)
        val myNodes = current.myNodes - removingMyNodes
        if (myNodes.size != current.myNodes.size) {
            val entries = json.parseToJsonElement(values.getValue("meshcore-my-nodes")).jsonArray
            values["meshcore-my-nodes"] = JsonArray(entries.filterNot {
                it.jsonObject.getValue("pubkey").jsonPrimitive.content.lowercase() in removingMyNodes
            }).toString()
        }
        return AccountSettingsDocument(1, values) to Selection(raw + additions, myNodes)
    }
}

internal object CoreScopeAccountWriteGate {
    private val deadlines = mutableMapOf<String, Long>()
    @Synchronized fun reserve(origin: String, now: Long = System.currentTimeMillis()) {
        if ((deadlines[origin] ?: 0) > now) throw AccountException(AccountException.Kind.THROTTLED)
        deadlines[origin] = now + 60_000
    }
}
