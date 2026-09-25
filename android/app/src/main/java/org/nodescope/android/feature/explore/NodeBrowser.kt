package org.nodescope.android.feature.explore

import org.nodescope.android.feature.map.roleColor
import org.nodescope.android.feature.map.MAP_ROLES
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.nodescope.android.R
import org.nodescope.android.core.model.MeshNode

@Composable
fun NodeBrowser(nodes: List<MeshNode>, total: Int, onNode: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // iOS map search: role chips, multi-select; none selected means every role.
    var roles by rememberSaveable { mutableStateOf(listOf<String>()) }
    val filtered = remember(nodes, query, roles) {
        nodes.filter {
            (roles.isEmpty() || it.role.lowercase() in roles) &&
                "${it.displayName} ${it.publicKey} ${it.role}".contains(query.trim(), ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextField(query, { query = it }, colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer), leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = MaterialTheme.shapes.large, placeholder = { Text(stringResource(R.string.search_nodes)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = roles.isEmpty(), onClick = { roles = emptyList() }, label = { Text("All") })
            MAP_ROLES.forEach { role ->
                FilterChip(selected = role in roles, onClick = { roles = if (role in roles) roles - role else roles + role },
                    label = { Text(role.replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(roleColor(role), CircleShape)) })
            }
        }
        Text(stringResource(R.string.nodes_count, nodes.size, total), style = MaterialTheme.typography.labelMedium)
        if (filtered.isEmpty()) Text(stringResource(R.string.no_nodes), modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(filtered, key = { it.publicKey }) { node ->
                Surface(onClick = { onNode(node.publicKey) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Icon(if (node.role == "repeater") Icons.Outlined.CellTower else Icons.Outlined.Router, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(node.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(node.role.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.last_seen, readableTime(node.lastSeen)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        }
    }
}

private fun readableTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
