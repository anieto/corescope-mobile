package org.nodescope.android.core.model

import kotlinx.serialization.Serializable

/**
 * A region selection is the server's `region=` value: one code ("SAT"), or a quick pick's codes
 * joined with commas ("AUS,SAT,ACT"), which CoreScope accepts on every endpoint. Compare it
 * with [regionIncludes], never `equals`.
 */
fun regionCodes(region: String?): Set<String>? =
    region?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

/** Whether [code] (an observer's or packet's region) is inside [region]; everything is in "all". */
fun regionIncludes(region: String?, code: String?): Boolean {
    val selected = regionCodes(region) ?: return true
    return code != null && code.uppercase() in selected
}

/** GET /api/config/region-quick-picks: a named group of regions (e.g. "Central Texas" = AUS, SAT, ACT). */
@Serializable
data class RegionQuickPick(val name: String, val description: String? = null, val regions: List<String> = emptyList()) {
    /** The value that selects this pick. */
    val selection: String get() = regions.joinToString(",")
}

@Serializable
data class RegionQuickPicksResponse(val quickPicks: List<RegionQuickPick> = emptyList())

/** Quick picks limited to the host's [regions], without any left empty. */
fun availablePicks(picks: List<RegionQuickPick>, regions: Map<String, String>): List<RegionQuickPick> {
    val known = regions.keys.map { it.uppercase() }.toSet()
    return picks.mapNotNull { pick ->
        pick.copy(regions = pick.regions.map { it.uppercase() }.filter { it in known }).takeIf { it.regions.isNotEmpty() }
    }
}

/** The pick whose codes are exactly [region], as on the website (several codes only). */
fun activePick(picks: List<RegionQuickPick>, region: String?): RegionQuickPick? {
    val selected = regionCodes(region)?.takeIf { it.size > 1 } ?: return null
    return picks.firstOrNull { it.regions.toSet() == selected }
}

/** Short text for the region button: the pick's name, else the code(s). */
fun regionTitle(picks: List<RegionQuickPick>, region: String?): String? =
    region?.let { activePick(picks, it)?.name ?: regionCodes(it)?.sorted()?.joinToString(", ") }
