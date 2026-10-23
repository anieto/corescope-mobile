package org.nodescope.android.core.model

import java.net.URI
import java.net.URLDecoder

@ConsistentCopyVisibility
data class FieldSourceId private constructor(val backend: String, val origin: String) {
    companion object {
        fun create(backend: String, origin: String): FieldSourceId? {
            val normalizedBackend = backend.trim().lowercase()
            val canonicalOrigin = origin.trim()
            return if (normalizedBackend.isNotEmpty() && canonicalOrigin.isNotEmpty()) {
                FieldSourceId(normalizedBackend, canonicalOrigin)
            } else null
        }
    }
}

@ConsistentCopyVisibility
data class FieldEntityId private constructor(
    val source: FieldSourceId,
    val kind: Kind,
    val value: String,
) {
    enum class Kind(val wireValue: String) { NODE("node"), OBSERVER("observer") }

    val id: String get() = encode(listOf(source.backend, source.origin, kind.wireValue, value))

    companion object {
        fun create(source: FieldSourceId, kind: Kind, value: String): FieldEntityId? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > 512) return null
            return FieldEntityId(source, kind, if (kind == Kind.NODE) trimmed.lowercase() else trimmed)
        }

        private fun encode(values: List<String>): String = values.joinToString("") {
            "${it.toByteArray(Charsets.UTF_8).size}:$it"
        }
    }
}

sealed interface FieldQrCode {
    data class NodeScope(val kind: FieldEntityId.Kind, val value: String) : FieldQrCode
    data class MeshCoreContact(val contact: MeshCoreContactLink) : FieldQrCode
    data class PublicKey(val value: String) : FieldQrCode

    companion object {
        const val MAXIMUM_PAYLOAD_BYTES = 2_048

        fun parse(payload: String): FieldQrCode? {
            val trimmed = payload.trim()
            if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > MAXIMUM_PAYLOAD_BYTES) return null
            publicKey(trimmed)?.let { return PublicKey(it) }

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
            return when (uri.scheme?.lowercase()) {
                "nodescope" -> parseNodeScope(trimmed, uri)
                "meshcore" -> parseMeshCore(uri)
                else -> null
            }
        }

        private fun parseNodeScope(payload: String, uri: URI): FieldQrCode? {
            if (uri.rawQuery != null) return null
            val link = NodeScopeLink.parse(payload) ?: return null
            return when (link.kind) {
                NodeScopeLink.Kind.NODE -> publicKey(link.identifier)?.let { NodeScope(FieldEntityId.Kind.NODE, it) }
                NodeScopeLink.Kind.OBSERVER -> link.identifier.takeIf {
                    it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 512
                }?.let { NodeScope(FieldEntityId.Kind.OBSERVER, it) }
                NodeScopeLink.Kind.CHANNEL, NodeScopeLink.Kind.PACKET -> null
            }
        }

        private fun parseMeshCore(uri: URI): FieldQrCode? {
            if (!uri.host.equals("contact", ignoreCase = true) || uri.rawPath != "/add") return null
            val pairs = uri.rawQuery?.split('&')?.map { pair ->
                val pieces = pair.split('=', limit = 2)
                if (pieces.size != 2) return null
                (decode(pieces[0]) ?: return null) to (decode(pieces[1]) ?: return null)
            } ?: return null
            if (pairs.size != 3 || pairs.map { it.first }.toSet() != setOf("name", "public_key", "type")) return null
            val values = pairs.toMap()
            val type = values["type"]?.toIntOrNull() ?: return null
            val contact = MeshCoreContactLink.from(values["name"], values["public_key"] ?: return null, type) ?: return null
            return MeshCoreContact(contact)
        }

        private fun decode(value: String): String? = runCatching {
            URLDecoder.decode(value, Charsets.UTF_8)
        }.getOrNull()

        private fun publicKey(value: String): String? = value.lowercase().takeIf { key ->
            key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }
}
