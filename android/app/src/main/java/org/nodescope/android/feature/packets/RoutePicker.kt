package org.nodescope.android.feature.packets

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Chooses between a packet's routes by who heard them: each choice leads with the observer that
 * heard that path (+N when several did), then hops, region and signal. Used by packet details,
 * message packets and the map's replay controls, so a route is named the same everywhere.
 */
@Composable
fun RoutePicker(options: List<RouteOption>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val index = selected.coerceIn(options.indices)
    val current = options[index]
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, Modifier.semantics { contentDescription = "Route: ${current.spokenLabel(index)}. Change route" }) {
            RouteName(current, index, Modifier.weight(1f, fill = false))
            Text(" · ${current.hops} hops", maxLines = 1)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RouteName(option, i)
                            Text(option.summary(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onSelect(i); open = false },
                    trailingIcon = { if (i == index) Icon(Icons.Outlined.Check, "Selected", Modifier.size(18.dp)) },
                    modifier = Modifier.semantics { contentDescription = option.spokenLabel(i) },
                )
            }
        }
    }
}

/** The observer that heard a route, shortened to fit, with "+N" when others heard it too. */
@Composable
private fun RouteName(option: RouteOption, index: Int, modifier: Modifier = Modifier) {
    val first = option.heardBy.firstOrNull()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(first?.observer ?: "Route ${index + 1}", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (option.heardBy.size > 1) Text("  +${option.heardBy.size - 1}", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun RouteOption.spokenLabel(index: Int): String {
    val who = heardBy.firstOrNull()?.observer?.let { name -> if (heardBy.size > 1) "$name and ${heardBy.size - 1} more" else name } ?: "Route ${index + 1}"
    return "$who, ${summary()}"
}

/**
 * Who heard the chosen route: each observer that reported exactly this path, with signal, then
 * those that heard it earlier on its way (with how many hops they saw).
 */
@Composable
fun RouteHearers(option: RouteOption) {
    if (option.heardBy.isEmpty() && option.alongTheWay.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (option.heardBy.isNotEmpty()) {
            Text("Heard by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            option.heardBy.forEach { hearing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hearing.observer, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(hearing.region, hearing.rssi?.let { "%.0f dBm".format(it) } ?: hearing.snr?.let { "%.1f dB".format(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (option.alongTheWay.isNotEmpty()) Text(
            "Also heard along the way by " + option.alongTheWay.joinToString(", ") { "${it.observer} (${it.hops} hops)" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
