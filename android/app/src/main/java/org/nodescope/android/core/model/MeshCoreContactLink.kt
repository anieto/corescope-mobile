package org.nodescope.android.core.model

import java.net.URLEncoder

/**
 * The `meshcore://contact/add` link the MeshCore app reads from contact QR codes and links
 * (docs/qr_codes.md in meshcore-dev/MeshCore), matching iOS `MeshCoreContactLink`. A bare
 * public key isn't enough; the app also needs a name and a contact type.
 */
data class MeshCoreContactLink(val name: String, val publicKey: String, val type: Int) {

    /** Strict encoding: the app decodes queries form-style, so "+" must be escaped and spaces are %20. */
    val url: String get() =
        "meshcore://contact/add?name=${URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A")}" +
            "&public_key=$publicKey&type=$type"

    val typeLabel: String get() = when (type) {
        1 -> "Companion"
        2 -> "Repeater"
        3 -> "Room Server"
        else -> "Sensor"
    }

    companion object {
        fun from(node: MeshNode): MeshCoreContactLink? = from(node.name, node.publicKey, node.role)

        fun from(name: String?, publicKey: String, role: String?): MeshCoreContactLink? {
            val key = publicKey.trim().lowercase()
            if (key.length != 64 || !key.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val type = contactType(role) ?: return null
            val label = name?.trim().takeUnless { it.isNullOrEmpty() } ?: key.take(8).uppercase()
            return MeshCoreContactLink(label, key, type)
        }

        /** 1 companion, 2 repeater, 3 room server, 4 sensor. */
        fun contactType(role: String?): Int? = when (role?.lowercase()) {
            "companion", "chat" -> 1
            "repeater" -> 2
            "room", "room_server" -> 3
            "sensor" -> 4
            else -> null
        }
    }
}
