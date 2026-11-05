package org.nodescope.android.feature.map

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
