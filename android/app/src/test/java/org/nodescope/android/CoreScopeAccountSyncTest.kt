package org.nodescope.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.*
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.*

class CoreScopeAccountSyncTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)
    private fun settings(keys: List<String>, claimed: List<String> = emptyList()) = CoreScopeAccountSettings(
        1, "g", AccountSettingsDocument(1, mapOf(
            "meshcore-favorites" to Json.encodeToString(keys),
            "meshcore-my-nodes" to Json.encodeToString(claimed.map { mapOf("pubkey" to it, "name" to "Roof", "future" to "preserve") }),
            "meshcore-theme" to "dark"
        )), listOf(AccountAllowedKey("meshcore-favorites", "set"), AccountAllowedKey("meshcore-my-nodes", "set", "pubkey"))
    )

    @Test fun notificationsDecodePushRelayWithLegacyFallback() = runBlocking {
        val current = """{"relayOrigin":"https://push.nodescope.app","analyzerId":"current","contractVersion":1,"platforms":["android"]}"""
        val legacy = """{"relayOrigin":"https://push.nodescope.app","analyzerId":"legacy","contractVersion":1,"platforms":["android"]}"""
        val cases = listOf(
            ""","pushRelay":$current""" to "current",
            ""","nodeScopePush":$legacy""" to "legacy",
            ""","pushRelay":$current,"nodeScopePush":$legacy""" to "current",
            "" to null,
            ""","pushRelay":null,"nodeScopePush":$legacy""" to null,
        )
        for ((fields, expected) in cases) {
            val body = """{"enabled":true,"watches":[{"pubkey":"$a","name":"Roof"}]$fields}"""
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val client = CoreScopeAccountClient("https://notifications.fixture/".toHttpUrl(),
                cookie = "test-session", http = http)
            val notifications = client.notifications()
            assertTrue(notifications.enabled)
            assertEquals(listOf("Roof"), notifications.watches.map { it.name })
            assertEquals(fields, expected, notifications.pushRelay?.analyzerId)
            assertEquals(fields, expected, client.setWatch(a, watched = true, csrf = "test-csrf").pushRelay?.analyzerId)
            assertEquals(fields, expected, client.pushCapability()?.analyzerId)
        }
    }

    @Test fun repeaterManagementUsesAccountSessionAndConditionalWrites() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body) = when (request.url.encodedPath) {
                "/api/config/client" -> 200 to """{"userManagement":{"enabled":true,"repeaterTokens":true}}"""
                "/api/account/repeaters" -> 200 to """{"repeaters":[{"pubkey":"$a","name":"Roof","known":true,"inactive":true}]}"""
                "/api/auth/me" -> 200 to """{"id":7,"email":"fixture@example.test","displayName":"Test","csrfToken":"test-csrf"}"""
                "/api/repeaters/$a/claim" -> 200 to "{}"
                else -> 409 to "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CoreScopeAccountClient("https://repeaters.fixture/".toHttpUrl(), cookie = "test-session", http = http)
        assertTrue(client.supportsRepeaterManagement())
        assertNull(requests.first().header("Cookie"))
        assertTrue(client.repeaters().single().inactive)
        client.setRepeaterClaim(a, claimed = false)
        assertEquals("DELETE", requests.last().method)
        assertEquals("cs_session=test-session", requests.last().header("Cookie"))
        assertEquals("test-csrf", requests.last().header("X-CS-CSRF"))
        try {
            client.saveRepeater(a, AccountRepeaterEdit(3, null, 4.0, "", "", ""))
            fail("Expected a stale edit to be refused")
        } catch (error: AccountException) {
            assertEquals(AccountException.Kind.CONFLICT, error.kind)
        }
        val request = requests.last()
        assertEquals("PUT", request.method)
        val body = okio.Buffer()
        request.body!!.writeTo(body)
        assertTrue(body.readUtf8().contains("\"baseRevision\":3"))
    }

    @Test fun accountNamespacesSeparateUsersAndSources() {
        val one = CoreScopeAccountSelectionState.key("https://one/", 1)
        val two = CoreScopeAccountSelectionState.key("https://one/", 2)
        val otherSource = CoreScopeAccountSelectionState.key("https://two/", 1)
        val stored = mutableMapOf(one to CoreScopeAccountSelectionState.from(settings(listOf(a))),
            two to CoreScopeAccountSelectionState.from(settings(listOf(b))))
        assertNull(stored[otherSource])
        assertEquals(setOf(a), stored[one]?.favorites)
        stored.remove(one)
        assertNull(stored[one])
        assertEquals(setOf(b), stored[two]?.favorites)
    }

    @Test fun resetRequiresExplicitResolutionAndKeepsPendingEdits() {
        val state = CoreScopeAccountSelectionState.from(settings(listOf(a))).copy(favorites = setOf(a, b))
        val reset = settings(listOf(c)).copy(generation = "new-generation")
        try { state.refresh(reset); fail() } catch (error: AccountException) { assertEquals(AccountException.Kind.RESET, error.kind) }
        assertEquals(setOf(a, b), state.favorites)
        assertEquals(setOf(a, b, c), state.restore(reset).favorites)
        assertEquals("new-generation", state.restore(reset).baseline.generation)
        assertEquals(setOf(c), CoreScopeAccountSelectionState.from(reset).favorites)
    }

    @Test fun refreshKeepsPendingEditsWithoutResurrectingRemoteDeletions() {
        var state = CoreScopeAccountSelectionState.from(settings(listOf(a, b))).copy(favorites = setOf(b, d))
        state = state.refresh(settings(listOf(a, c)))
        assertEquals(setOf(c, d), state.favorites)
        assertEquals(setOf(c, d), state.refresh(settings(listOf(a, c))).favorites)
    }

    @Test fun removingAFavoriteFiltersBothKeysAndPreservesOtherMyNodeFields() {
        val remote = settings(listOf(a, c), listOf(a, b))
        val initial = CoreScopeAccountSelectionState.from(remote)
        assertEquals(setOf(a, b, c), initial.favoriteKeys)
        val state = initial.removeFavorite(a.uppercase()).refresh(remote)
        assertEquals(setOf(b, c), state.favoriteKeys)
        assertTrue(state.hasPendingEdits)
        val (document, selection) = CoreScopeFavoriteMerge.document(state.baseline, state.favorites, remote, state.removedMyNodes)
        assertEquals(listOf(c), selection.favorites)
        assertEquals(mapOf(b to "Roof"), selection.myNodes)
        val rows = Json.decodeFromString<List<Map<String, String>>>(document.keys.getValue("meshcore-my-nodes"))
        assertEquals(listOf(mapOf("pubkey" to b, "name" to "Roof", "future" to "preserve")), rows)
        assertEquals("dark", document.keys["meshcore-theme"])
        assertFalse(state.acknowledge(remote.copy(revision = 2, doc = document), state.favorites).hasPendingEdits)
    }

    @Test fun failedWriteAttemptsStillConsumeTheMinute() {
        val origin = "https://gate-${java.util.UUID.randomUUID()}/"
        CoreScopeAccountWriteGate.reserve(origin, 100_000)
        try { CoreScopeAccountWriteGate.reserve(origin, 159_999); fail() }
        catch (error: AccountException) { assertEquals(AccountException.Kind.THROTTLED, error.kind) }
        CoreScopeAccountWriteGate.reserve(origin, 160_000)
    }

    @Test fun accountFavoritesReplaceDeviceMembershipAndStayOnSignOut() {
        val storage = mutableMapOf<String, String>()
        val accounts = AccountSelectionStore({ storage[it] }, { key, value ->
            if (value == null) storage.remove(key) else storage[key] = value
        })
        var document: String? = null
        val library = NodeLibrary(accountStore = accounts, host = "one.fixture") { document = it }
        library.toggle(MeshNode(a, "Device node", "repeater", lastSeen = ""))
        val origin = "https://one.fixture/"
        accounts.save(origin, 7, CoreScopeAccountSelectionState.from(settings(listOf(b), listOf(b, c))))
        accounts.activate(origin, 7)
        assertEquals(setOf(b, c), library.favorites.map { it.entityId }.toSet())
        assertEquals("Roof", library.favorites.first { it.entityId == c }.title)
        library.removeFavorite(library.favorites.first { it.entityId == b })
        assertFalse(library.isFavorite(SavedKind.NODE, b))
        library.toggle(MeshNode(d, "Added in app", "repeater", lastSeen = ""))
        val account = checkNotNull(accounts.load(origin, 7))
        assertEquals(setOf(d), account.favorites)
        assertEquals(setOf(a), library.nodeKeys())
        library.keepAccountFavoritesOnDevice(account)
        accounts.remove(origin, 7)
        val restarted = NodeLibrary(document, accountStore = accounts, host = "one.fixture")
        assertEquals(setOf(c, d), restarted.nodeKeys())
        assertEquals("Roof", restarted.favorites.first { it.entityId == c }.title)
    }

    @Test fun signInOfferIsRememberedPerAccountAndSource() {
        val storage = mutableMapOf<String, String>()
        fun store() = AccountSelectionStore({ storage[it] }, { key, value ->
            if (value == null) storage.remove(key) else storage[key] = value
        })
        val accounts = store()
        val origin = "https://offer.fixture/"
        val initial = CoreScopeAccountSelectionState.from(settings(listOf(a), listOf(b)))
        val offer = accounts.offeredDeviceFavorites(setOf(a, b, c), initial, origin, 7)
        assertEquals(setOf(c), offer)
        val state = initial.copy(favorites = initial.favorites + offer)
        assertEquals(setOf(a, c), state.favorites)
        assertEquals(mapOf(b to "Roof"), state.myNodeNames)
        accounts.finishDeviceFavoritesOffer(origin, 7)
        accounts.finishDeviceFavoritesOffer(origin, 8)
        val restarted = store()
        assertTrue(restarted.offeredDeviceFavorites(setOf(d), state, origin, 7).isEmpty())
        assertTrue(restarted.offeredDeviceFavorites(setOf(d), state, origin, 8).isEmpty())
        assertEquals(setOf(d), restarted.offeredDeviceFavorites(setOf(d), state, origin, 9))
        assertEquals(setOf(d), restarted.offeredDeviceFavorites(setOf(d), state, "https://other.fixture/", 7))
    }

    @Test fun acknowledgmentKeepsEditsMadeDuringUpload() {
        val sent = setOf(a, b)
        val latest = CoreScopeAccountSelectionState.from(settings(listOf(a))).copy(favorites = setOf(b, c))
        val acknowledged = latest.acknowledge(settings(listOf(a, b)), sent)
        assertEquals(setOf(b, c), acknowledged.favorites)
        assertTrue(acknowledged.hasPendingEdits)
        assertEquals(setOf(b, c), acknowledged.refresh(settings(listOf(a, b))).favorites)
        assertFalse(CoreScopeAccountSelectionState.from(settings(listOf(a, b))).hasPendingEdits)
    }

    @Test fun failedServerLogoutStillForgetsLocalCredential() = runBlocking {
        var saved: String? = "synthetic"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("{}".toResponseBody()).build()
        }.build()
        val client = CoreScopeAccountClient("https://offline-logout.fixture/".toHttpUrl(), "synthetic", { saved = it }, http)
        try { client.logout(); fail() } catch (_: AccountException) { }
        assertNull(saved)
        try { client.me(); fail() } catch (error: AccountException) { assertEquals(AccountException.Kind.SIGNED_OUT, error.kind) }
    }

    @Test fun whitespaceInServerDocumentIsNotAPendingMembershipEdit() {
        val original = settings(listOf(a))
        val remote = original.copy(doc = checkNotNull(original.doc).copy(keys = original.doc.keys +
            ("meshcore-favorites" to """[ "$a" ]""")))
        assertFalse(CoreScopeAccountSelectionState.from(remote).hasPendingEdits)
    }

    @Test fun independentAdditionsAndDeletionsPreserveClaimsAndOtherSettings() {
        val base = settings(listOf(a, b), listOf(d))
        val remote = settings(listOf(a, c), listOf(d))
        val (document, selection) = CoreScopeFavoriteMerge.document(base, setOf(b), remote)
        assertEquals(setOf(c, d), selection.keys)
        assertEquals("dark", document.keys["meshcore-theme"])
        assertEquals(remote.doc?.keys?.get("meshcore-my-nodes"), document.keys["meshcore-my-nodes"])
        assertFalse(d in selection.favorites)
    }

    @Test fun firstSyncDeduplicatesCaseAndMalformedKeysFailClosed() {
        val (_, selection) = CoreScopeFavoriteMerge.document(null, setOf(a, c), settings(listOf(a.uppercase()), listOf(b)))
        assertEquals(setOf(a, b, c), selection.keys)
        assertEquals(listOf(a.uppercase(), c), selection.favorites)
        try { CoreScopeFavoriteMerge.selection(settings(listOf("invalid"))); fail() }
        catch (_: AccountException) { }
    }

    @Test fun localReconciliationKeepsOtherKindsAndKnownSnapshots() {
        val library = NodeLibrary()
        val node = MeshNode(a, "Known node", "repeater", lastSeen = "")
        library.toggle(node)
        library.applyAccountNodes(setOf(a, b), mapOf(b to "Roof"))
        assertEquals(2, library.state.favorites.size)
        assertEquals(node, library.node(a))
        assertEquals("Roof", library.node(b)?.name)
        library.applyAccountNodes(setOf(a, b), emptyMap())
        assertEquals(2, library.state.favorites.size)
    }

    @Test fun renewalOriginCsrfConflictAndSignout() = runBlocking {
        val requests = mutableListOf<Request>()
        var saved: String? = null
        var status = 200
        val user = """{"id":7,"email":"fixture@example.test","displayName":"Test","csrfToken":"csrf-fixture"}"""
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests += request }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            val body = when (request.url.encodedPath) {
                "/api/config/client" -> """{"userManagement":{"enabled":true}}"""
                "/api/auth/login" -> { builder.header("Set-Cookie", "cs_session=initial; Path=/; Secure; HttpOnly"); user }
                "/api/auth/me" -> { builder.header("Set-Cookie", "cs_session=renewed; Path=/; Secure; HttpOnly"); user }
                else -> Json.encodeToString(settings(listOf(a)))
            }
            builder.body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CoreScopeAccountClient("https://account.fixture/".toHttpUrl(), save = { saved = it }, http = http)
        assertTrue(client.supportsAccounts())
        val me = client.login("fixture@example.test", "synthetic-only")
        client.me()
        assertEquals("renewed", saved)
        val remote = client.settings()
        status = 409
        try { client.put(checkNotNull(remote.doc), remote, me.csrfToken); fail() }
        catch (error: AccountException) { assertEquals(AccountException.Kind.CONFLICT, error.kind) }
        val write = requests.last()
        assertEquals("cs_session=renewed", write.header("Cookie"))
        assertEquals("csrf-fixture", write.header("X-CS-CSRF"))
        assertEquals("https://account.fixture", write.header("Origin"))
        assertNull(requests.first().header("Cookie"))
        status = 200
        client.logout()
        assertNull(saved)
    }

    @Test fun unauthorizedSessionIsForgottenAndRedirectIsRejected() = runBlocking {
        var saved: String? = "old"
        var status = 302
        val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .header("Location", "https://other.invalid/").body("{}".toResponseBody()).build()
        }.build()
        val client = CoreScopeAccountClient("https://account.fixture/".toHttpUrl(), "old", { saved = it }, http)
        try { client.me(); fail() } catch (error: AccountException) { assertEquals(AccountException.Kind.REJECTED, error.kind) }
        status = 401
        try { client.me(); fail() } catch (error: AccountException) { assertEquals(AccountException.Kind.SIGNED_OUT, error.kind) }
        assertNull(saved)
    }
}
