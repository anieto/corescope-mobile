package org.nodescope.android.core.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Authenticator
import okhttp3.OkHttpClient

enum class MyNodesBudgetFailure { EXHAUSTED, NODE_EXHAUSTED, BUSY, INVALIDATED }
class MyNodesBudgetException(val reason: MyNodesBudgetFailure) : Exception(reason.name)

/** One batch; each operation wraps one HTTP attempt. Cache hits must bypass this gate. */
class MyNodesRequestBudget(maximumRequests: Int = 20, maximumPerNode: Int = 4, maximumConcurrent: Int = 2) {
    private val maximumRequests = maximumRequests.coerceAtLeast(0)
    private val maximumPerNode = maximumPerNode.coerceAtLeast(0)
    private val maximumConcurrent = maximumConcurrent.coerceAtLeast(0)
    private val lock = Any()
    private var consumed = 0
    private var active = 0
    private var valid = true
    private val perNode = mutableMapOf<String, Int>()

    fun invalidate() = synchronized(lock) { valid = false }

    suspend fun <T> perform(node: String? = null, operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            fun fail(reason: MyNodesBudgetFailure): Nothing = throw MyNodesBudgetException(reason)
            if (!valid) fail(MyNodesBudgetFailure.INVALIDATED)
            if (consumed >= maximumRequests) fail(MyNodesBudgetFailure.EXHAUSTED)
            val key = node?.lowercase(java.util.Locale.ROOT)
            if (key != null && perNode.getOrDefault(key, 0) >= maximumPerNode) fail(MyNodesBudgetFailure.NODE_EXHAUSTED)
            if (active >= maximumConcurrent) fail(MyNodesBudgetFailure.BUSY)
            consumed++
            active++
            if (key != null) perNode[key] = perNode.getOrDefault(key, 0) + 1
        }
        try {
            val value = operation()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!valid) throw MyNodesBudgetException(MyNodesBudgetFailure.INVALIDATED)
            }
            return value
        } finally {
            synchronized(lock) { active-- }
        }
    }
}

/** One selected node's pages and explicit retries share the batch allowance. */
class MyNodesRequestAllowance(val budget: MyNodesRequestBudget, val node: String) {
    suspend fun <T> perform(operation: suspend () -> T): T = budget.perform(node, operation)
}

/** Repository cache hits bypass the gate; network clients cannot follow redirects or retry implicitly. */
internal fun OkHttpClient.forMyNodes(): OkHttpClient = newBuilder()
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .build()

internal val myNodesCoreScopeCooldown = BeaconRateLimit()

fun OkHttpClient.myNodesBodies(budget: MyNodesRequestBudget, node: String): BodySource {
    val transport = forMyNodes()
    val allowance = MyNodesRequestAllowance(budget, node)
    return BodySource { url ->
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        myNodesCoreScopeCooldown.check(origin, System.currentTimeMillis())
        try {
            allowance.perform { transport.read(url) }
        } catch (error: HttpFailure) {
            if (error.status != 429) throw error
            val until = myNodesCoreScopeCooldown.record(origin, error.retryAfter, System.currentTimeMillis())
            throw BeaconApiException.RateLimited(until)
        }
    }
}
