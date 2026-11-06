package org.nodescope.android.feature.map

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Settings → Live Map (iOS `LiveMapPreferences`): whether nearby nodes are grouped into numbered
 * bubbles, and whether live traffic waits for every observer before animating a packet's paths
 * together. Observable, so the map follows a change made in Settings.
 */
object LiveMapSettings {
    private const val FILE = "live-map"
    private const val GROUPS_NODES = "groups-nodes"
    private const val REALISTIC = "realistic-propagation"
    private var groups: MutableState<Boolean>? = null
    private var realistic: MutableState<Boolean>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun groupsState(context: Context) = groups ?: mutableStateOf(prefs(context).getBoolean(GROUPS_NODES, true)).also { groups = it }
    private fun realisticState(context: Context) = realistic ?: mutableStateOf(prefs(context).getBoolean(REALISTIC, false)).also { realistic = it }

    fun groupsNodes(context: Context): Boolean = groupsState(context).value
    fun setGroupsNodes(context: Context, value: Boolean) {
        groupsState(context).value = value
        prefs(context).edit { putBoolean(GROUPS_NODES, value) }
    }

    fun realisticPropagation(context: Context): Boolean = realisticState(context).value
    fun setRealisticPropagation(context: Context, value: Boolean) {
        realisticState(context).value = value
        prefs(context).edit { putBoolean(REALISTIC, value) }
    }
}

/**
 * Live traffic paused by hand from the live packets panel (iOS `LiveTrafficPause`). The panel's
 * list stops changing and the map stops drawing new traffic (anything already moving finishes),
 * so the two stay in step. Packets keep arriving meanwhile; resuming returns to live rather than
 * replaying what was missed. Kept for the app's lifetime only.
 */
object LiveTrafficPause {
    /** When the pause began, or null while live. */
    var pausedAt by androidx.compose.runtime.mutableStateOf<Long?>(null)
        private set
    /** The last pause, start to end, whose packets are never drawn even after resuming. */
    private var skipped: LongRange? = null

    val isPaused: Boolean get() = pausedAt != null

    fun pause() { if (pausedAt == null) pausedAt = System.currentTimeMillis() }

    fun resume() {
        val start = pausedAt ?: return
        skipped = start until System.currentTimeMillis()
        pausedAt = null
    }

    /** Whether a live packet arrived while paused (now, or during the last pause). */
    fun skips(packet: org.nodescope.android.core.model.LivePacket): Boolean {
        if (!packet.isLive) return false
        pausedAt?.let { if (packet.receivedAt >= it) return true }
        return skipped?.contains(packet.receivedAt) == true
    }
}
