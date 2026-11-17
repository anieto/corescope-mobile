package org.nodescope.android.core.design

import androidx.compose.runtime.Composable
import org.nodescope.android.R

fun nodeRoleText(role: String): UiText = when (role.lowercase()) {
    "repeater" -> UiText.Resource(R.string.live_map_repeater)
    "room", "room_server", "roomserver" -> UiText.Resource(R.string.live_map_room)
    "companion" -> UiText.Resource(R.string.live_map_companion)
    "sensor" -> UiText.Resource(R.string.live_map_sensor)
    else -> UiText.Raw(role.replaceFirstChar(Char::uppercase))
}

@Composable
fun nodeRoleLabel(role: String): String = nodeRoleText(role).asString()
