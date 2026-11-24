package org.nodescope.android.core.network

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val pushID = Regex("[A-Za-z0-9_-]{1,128}")
private val nodeKey = Regex("[a-f0-9]{64}")

@Serializable
data class StagingPushCapability(
    val relayOrigin: String, val analyzerId: String, val contractVersion: Int, val platforms: List<String>,
) {
    fun supported() = relayOrigin.removeSuffix("/") == StagingPush.ORIGIN &&
        contractVersion == 1 && pushID.matches(analyzerId) && "android" in platforms
}

@Serializable
data class StagingPushLink(@SerialName("subscription_id") val subscription: String, val status: String,
                           @SerialName("expires_at") val expiresAt: String)

data class StagingPushRoute(val analyzer: String, val subscription: String, val node: String) {
    companion object {
        fun parse(value: (String) -> String?): StagingPushRoute? {
            if (value("version") != "1") return null
            val analyzer = value("analyzer_id")?.takeIf(pushID::matches) ?: return null
            val sub = value("subscription_id")?.takeIf(pushID::matches) ?: return null
            val node = value("node_pubkey")?.takeIf(nodeKey::matches) ?: return null
            return StagingPushRoute(analyzer, sub, node)
        }
    }
}

@Serializable
internal data class StagingPushBinding(val analyzer: String, val account: Long, val subscription: String, val expiresAt: Long? = null) {
    fun matches(route: StagingPushRoute) = analyzer == route.analyzer && subscription == route.subscription
    fun isStale(capability: StagingPushCapability?) = capability?.analyzerId != analyzer
}

internal fun matchingPushOrigin(route: StagingPushRoute, selected: String?, bindings: Map<String, StagingPushBinding>): String? =
    selected?.takeIf { bindings[it]?.matches(route) == true }
        ?: bindings.entries.singleOrNull { it.value.matches(route) }?.key

internal fun inactivePushAnalyzer(status: Int, code: String?, linkProof: Boolean) =
    status == 410 && code == "analyzer_inactive" || linkProof && status == 404 && code == "unknown_analyzer"

internal fun unknownPushInstallation(status: Int, code: String?) =
    status == 404 && code == "unknown_installation"

private val pushJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RelayError(val error: Detail) {
    @Serializable data class Detail(val code: String)
}

internal fun pushRelayErrorCode(body: String): String? =
    runCatching { pushJson.decodeFromString<RelayError>(body).error.code }.getOrNull()

class StagingPush private constructor(context: Context) {
    @Serializable private data class Installation(
        @SerialName("installation_id") val id: String, @SerialName("installation_credential") val credential: String,
    )
    @Serializable private data class Saved(
        val installation: Installation? = null, val bindings: Map<String, StagingPushBinding> = emptyMap(),
        val revocations: Set<String> = emptySet(),
    )
    @Serializable private data class Proof(val proof: String)
    private class InactiveAnalyzer : IllegalStateException("The push analyzer is inactive.")

    private class UnknownInstallation : IllegalStateException("Push relay rejected the request.")

    private val secrets = AccountSessionStore(context)
    private val lock = Mutex()
    private val version = MutableStateFlow(0)
    val changes = version.asStateFlow()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(12, TimeUnit.SECONDS).build()

    private fun load(): Saved = secrets.load(STORAGE_KEY)?.let { pushJson.decodeFromString<Saved>(it) } ?: Saved()

    private fun save(state: Saved) {
        secrets.save(STORAGE_KEY, pushJson.encodeToString(Saved.serializer(), state))
        version.value += 1
    }

    fun enabled(origin: String, account: Long): Boolean = runCatching { load().bindings[origin]?.account == account }.getOrDefault(false)

    suspend fun renew(context: Context, client: CoreScopeAccountClient, user: CoreScopeAccountUser,
                      capability: StagingPushCapability) {
        val binding = load().bindings[client.origin.toString()] ?: return
        if (!capability.supported() || binding.account != user.id || binding.analyzer != capability.analyzerId ||
            (binding.expiresAt ?: 0) - System.currentTimeMillis() >= TimeUnit.DAYS.toMillis(7) ||
            !PushPlatform.canNotify(context)) return
        enable(context, client, user, capability)
    }

    suspend fun enable(context: Context, client: CoreScopeAccountClient, user: CoreScopeAccountUser,
                       capability: StagingPushCapability) = lock.withLock {
        check(capability.supported() && PushPlatform.canNotify(context))
        val token = PushPlatform.token(context)
        var installation = prepareInstallation(token)
        flushRevocations(installation)
        val origin = client.origin.toString()
        suspend fun linkProof(installation: Installation): Proof {
            val existing = load().bindings[origin]
            try {
                return pushJson.decodeFromString(request("v1/installations/" + installation.id + "/link-proofs", "POST",
                    installation.credential, buildJsonObject { put("analyzer_id", capability.analyzerId) }))
            } catch (inactive: InactiveAnalyzer) {
                if (existing != null && existing.analyzer == capability.analyzerId && existing.account == user.id) {
                    removeBinding(origin, existing)
                    attempt { flushRevocations(installation) }
                }
                throw inactive
            }
        }
        val proof = try {
            linkProof(installation)
        } catch (_: UnknownInstallation) {
            installation = replaceInstallation(token)
            flushRevocations(installation)
            linkProof(installation)
        }
        val link = try {
            client.linkPushDevice(installation.id, proof.proof, user.csrfToken)
        } catch (error: AccountException) {
            if (error.kind == AccountException.Kind.PUSH_LINK_GONE) {
                // CoreScope forwards 410 without the relay's error code. Check the analyzer directly.
                linkProof(installation)
            }
            throw error
        }
        check(pushID.matches(link.subscription) && link.status == "active")
        val current = load()
        save(current.copy(bindings = current.bindings + (client.origin.toString() to StagingPushBinding(capability.analyzerId, user.id, link.subscription, java.time.Instant.parse(link.expiresAt).toEpochMilli()))))
    }

    suspend fun disable(origin: String, account: Long, client: CoreScopeAccountClient? = null, csrf: String? = null) = lock.withLock {
        val saved = load()
        val binding = saved.bindings[origin]?.takeIf { it.account == account } ?: return@withLock
        save(saved.copy(bindings = saved.bindings - origin, revocations = saved.revocations + binding.subscription))
        saved.installation?.let {
            if (client != null && csrf != null) attempt { client.unlinkPushDevice(it.id, csrf) }
            flushRevocations(it)
        }
    }

    private fun removeBinding(origin: String, binding: StagingPushBinding) {
        val saved = load()
        if (saved.bindings[origin] != binding) return
        save(saved.copy(bindings = saved.bindings - origin, revocations = saved.revocations + binding.subscription))
    }

    private suspend fun validateBinding(origin: String, binding: StagingPushBinding) {
        val cookie = secrets.load(origin) ?: return
        val client = CoreScopeAccountClient(origin.toHttpUrl(), cookie)
        if (client.me().id != binding.account) return
        val capability = client.pushCapability()
        if (secrets.load(origin) == cookie && binding.isStale(capability)) removeBinding(origin, binding)
    }

    suspend fun refresh(context: Context, replacement: String? = null) = lock.withLock {
        val saved = load()
        val installation = saved.installation
        if (installation == null && saved.bindings.isEmpty()) return@withLock
        if (!PushPlatform.canNotify(context) && saved.bindings.isNotEmpty()) {
            save(saved.copy(bindings = emptyMap(), revocations = saved.revocations + saved.bindings.values.map { it.subscription }))
        } else {
            for ((origin, binding) in saved.bindings) attempt { validateBinding(origin, binding) }
        }
        if (installation != null) attempt { flushRevocations(installation) }
        if (load().bindings.isNotEmpty()) {
            val prepared = prepareInstallation(replacement ?: PushPlatform.token(context))
            if (prepared != installation) attempt { flushRevocations(prepared) }
        }
    }

    fun originForRoute(route: StagingPushRoute, selected: String?): String? {
        val origin = matchingPushOrigin(route, selected, load().bindings)
        if (origin == null) Log.w("NodeScopePush", "Notification route has no matching stored analyzer and subscription")
        return origin
    }

    suspend fun nodeForRoute(route: StagingPushRoute, origin: String): String? {
        val binding = load().bindings[origin]?.takeIf { it.matches(route) }
        if (binding == null) {
            Log.w("NodeScopePush", "Notification route binding changed before account verification")
            return null
        }
        val cookie = secrets.load(origin) ?: return null
        val account = CoreScopeAccountClient(origin.toHttpUrl(), cookie).me()
        return route.node.takeIf {
            binding.account == account.id && load().bindings[origin] == binding && secrets.load(origin) == cookie
        }
    }

    fun ownsRoute(route: StagingPushRoute): Boolean = runCatching {
        load().bindings.values.any { it.matches(route) }
    }.getOrDefault(false)

    private suspend fun prepareInstallation(token: String): Installation {
        val saved = load().installation ?: return createInstallation(token)
        return try {
            updateToken(saved, token)
            saved
        } catch (_: UnknownInstallation) {
            replaceInstallation(token)
        }
    }

    private suspend fun replaceInstallation(token: String): Installation {
        val saved = load()
        save(saved.copy(
            installation = null,
            bindings = saved.bindings.mapValues { (_, binding) -> binding.copy(expiresAt = null) },
            revocations = saved.revocations + saved.bindings.values.map { it.subscription },
        ))
        return createInstallation(token)
    }

    private suspend fun createInstallation(token: String): Installation {
        val created = pushJson.decodeFromString<Installation>(request("v1/installations", "POST", body = buildJsonObject {
            put("provider", "fcm"); put("environment", PushPlatform.environment); put("token", token)
        }))
        check(pushID.matches(created.id) && pushID.matches(created.credential))
        save(load().copy(installation = created))
        return created
    }

    private suspend fun updateToken(installation: Installation, token: String) {
        request("v1/installations/" + installation.id + "/token", "PUT", installation.credential,
            buildJsonObject { put("token", token) })
    }

    private suspend fun flushRevocations(installation: Installation) {
        for (id in load().revocations) {
            check(pushID.matches(id))
            request("v1/subscriptions/" + id, "DELETE", installation.credential, revoking = true)
            val state = load()
            save(state.copy(revocations = state.revocations - id))
        }
    }

    private suspend fun request(path: String, method: String, credential: String? = null, body: JsonObject? = null,
                                revoking: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$ORIGIN/$path")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        credential?.let { request.header("Authorization", "Bearer $it") }
        http.newCall(request.build()).execute().use { response ->
            val data = response.body?.source()?.let { source ->
                source.request(16_385)
                check(source.buffer.size <= 16_384)
                source.readUtf8()
            }.orEmpty()
            if (!response.isSuccessful && !(revoking && response.code in listOf(404, 410))) {
                val code = pushRelayErrorCode(data)
                if (inactivePushAnalyzer(response.code, code, path.endsWith("/link-proofs"))) throw InactiveAnalyzer()
                if (unknownPushInstallation(response.code, code)) throw UnknownInstallation()
                error("Push relay rejected the request.")
            }
            data
        }
    }

    companion object {
        const val ORIGIN = PushPlatform.relayOrigin
        private const val STORAGE_KEY = "nodescope-push-${PushPlatform.environment}-v1"
        @Volatile private var instance: StagingPush? = null
        fun get(context: Context): StagingPush = instance ?: synchronized(this) {
            instance ?: StagingPush(context.applicationContext).also { instance = it }
        }

        fun route(intent: Intent?): StagingPushRoute? = if (PushPlatform.enabled && intent != null) {
            StagingPushRoute.parse(intent::getStringExtra)
        } else null

        suspend fun attempt(work: suspend () -> Unit) {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Persisted revocations retry on the next foreground. */ }
        }
    }
}
