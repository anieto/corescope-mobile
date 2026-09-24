package org.nodescope.android.core.model

import java.net.URI
import java.net.URLDecoder

/**
 * `nodescope://<kind>/<identifier>` links, the same format iOS `NodeScopeDeepLink` builds and
 * parses. Links carry no analyzer, so they open against the one currently selected.
 */
data class NodeScopeLink(val kind: Kind, val identifier: String) {
    enum class Kind(val host: String) { NODE("node"), OBSERVER("observer"), CHANNEL("channel"), PACKET("packet") }

    /** Percent-encodes reserved characters (a channel such as `#test` becomes `%23test`). */
    val url: String get() = URI("nodescope", kind.host, "/$identifier", null).toASCIIString()

    companion object {
        fun node(publicKey: String) = NodeScopeLink(Kind.NODE, publicKey)
        fun observer(id: String) = NodeScopeLink(Kind.OBSERVER, id)
        fun channel(identifier: String) = NodeScopeLink(Kind.CHANNEL, identifier)
        fun packet(hash: String) = NodeScopeLink(Kind.PACKET, hash)

        /** Accepts any case for scheme and kind; the identifier is every path segment, decoded. */
        fun parse(link: String?): NodeScopeLink? {
            val uri = runCatching { URI(link?.trim() ?: return null) }.getOrNull() ?: return null
            if (!uri.scheme.equals("nodescope", ignoreCase = true)) return null
            val kind = Kind.entries.firstOrNull { it.host.equals(uri.host ?: uri.authority, ignoreCase = true) } ?: return null
            val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.joinToString("/")
                .let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() } ?: return null
            // An unencoded `#` (e.g. a hand-typed `nodescope://channel/#test`) arrives as the fragment.
            val identifier = (path + (uri.rawFragment?.let { "#" + (runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: it) } ?: "")).trim()
            return identifier.takeIf { it.isNotEmpty() }?.let { NodeScopeLink(kind, it) }
        }
    }
}
