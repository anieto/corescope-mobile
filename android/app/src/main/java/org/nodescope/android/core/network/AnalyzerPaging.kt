package org.nodescope.android.core.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import org.nodescope.android.core.model.LivePacket
import org.nodescope.android.core.model.parseLivePacket

data class AnalyzerCursor(val value: String)
data class AnalyzerPage<T>(val items: List<T>, val nextCursor: AnalyzerCursor?, val total: Long? = null)

suspend fun <T, K> collectAnalyzerPages(
    limit: Int,
    maximumPages: Int = 20,
    identity: (T) -> K,
    fetch: suspend (AnalyzerCursor?, Int) -> AnalyzerPage<T>,
): List<T> {
    if (limit <= 0) return emptyList()
    require(maximumPages > 0) { "The page budget must be positive." }
    val result = mutableListOf<T>()
    val seenItems = mutableSetOf<K>()
    val seenCursors = mutableSetOf<AnalyzerCursor>()
    var cursor: AnalyzerCursor? = null
    repeat(maximumPages) {
        currentCoroutineContext().ensureActive()
        val page = fetch(cursor, limit - result.size)
        currentCoroutineContext().ensureActive()
        for (item in page.items) {
            if (seenItems.add(identity(item))) result += item
            if (result.size == limit) return result
        }
        val next = page.nextCursor ?: return result
        check(seenCursors.add(next)) { "The server repeated a page cursor." }
        cursor = next
    }
    error("The requested history exceeds the page budget.")
}

internal suspend fun coreScopePacketPage(
    source: BodySource,
    endpoint: HttpUrl,
    size: Int,
    cursor: AnalyzerCursor?,
): AnalyzerPage<LivePacket> {
    require(size > 0)
    val scope = endpoint.toString()
    val offset = cursor?.let {
        val saved = protocolJson.parseToJsonElement(it.value).jsonObject
        require(saved["scope"]?.jsonPrimitive?.content == scope) { "This cursor belongs to another request." }
        requireNotNull(saved["offset"]?.jsonPrimitive?.longOrNull).also { value -> require(value >= 0) }
    } ?: 0L
    val url = endpoint.newBuilder().query(null).addQueryParameter("limit", size.toString())
        .apply {
            for (index in 0 until endpoint.querySize) {
                addQueryParameter(endpoint.queryParameterName(index), endpoint.queryParameterValue(index))
            }
        }
        .apply { if (offset > 0) addQueryParameter("offset", offset.toString()) }.build()
    val root = protocolJson.parseToJsonElement(source.read(url)).jsonObject
    val raw = (root["packets"] as? JsonArray).orEmpty()
    val items = raw.mapNotNull { runCatching { parseLivePacket(it.jsonObject, live = false) }.getOrNull() }
    val total = root["total"]?.jsonPrimitive?.longOrNull
    val nextOffset = offset + raw.size
    val next = if (raw.isNotEmpty() && total != null && nextOffset < total) AnalyzerCursor(
        buildJsonObject { put("scope", scope); put("offset", nextOffset) }.toString()
    ) else null
    return AnalyzerPage(items, next, total)
}
