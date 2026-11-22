package org.nodescope.android.feature.settings

import org.nodescope.android.core.model.ContentTranslation
import org.nodescope.android.core.model.nonBlankTranslation
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.nodescope.android.core.design.MonaspaceNeon

/** A tool a What's New item can open directly. */
enum class AppFeature { nodePlanner, estimatedCoverage, linkProfile, savedPlans, nodeCheck }

@Serializable data class WhatsNewItem(val id: String, val title: String, val detail: String, val icon: String,
    val destination: AppFeature? = null,
    /** Where to find it again, such as "Explore → Node Planner → Link Profile". */
    val location: String? = null, val platforms: List<String>? = null)
@Serializable data class WhatsNewRelease(val version: String, val items: List<WhatsNewItem>)
@Serializable private data class WhatsNewDocument(val version: Int, val releases: List<WhatsNewRelease>)

@Serializable
internal data class WhatsNewTranslation(val items: Map<String, Item>? = null) {
    @Serializable
    data class Item(val title: String? = null, val detail: String? = null, val location: String? = null)

    fun applyTo(english: WhatsNewItem): WhatsNewItem {
        val item = items?.get(english.id) ?: return english
        return english.copy(
            title = item.title.nonBlankTranslation() ?: english.title,
            detail = item.detail.nonBlankTranslation() ?: english.detail,
            location = item.location.nonBlankTranslation() ?: english.location,
        )
    }
}

/**
 * A release's highlights, shown once after an update so people with automatic updates hear about
 * new features. The text is shared with iOS (Resources/whats-new.json); a test keeps the copies
 * identical. `platforms` limits an item to "android", or to iOS devices ("ios", "ipad", "mac");
 * an item without it shows everywhere.
 */
object WhatsNew {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<WhatsNewRelease> = json.decodeFromString<WhatsNewDocument>(text).releases
        .map { release -> release.copy(items = release.items.filter { it.platforms?.contains("android") ?: true }) }
        .filter { it.items.isNotEmpty() }

    fun load(context: Context, language: String = ContentTranslation.language(context)): List<WhatsNewRelease> {
        val english = runCatching {
            parse(context.assets.open("whats-new.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
        val translation = ContentTranslation.load<WhatsNewTranslation>(context, "whats-new", language) ?: return english
        return english.map { release -> release.copy(items = release.items.map(translation::applyTo)) }
    }

    /** All shipped highlights available in Settings, newest first. */
    fun history(releases: List<WhatsNewRelease>, appVersion: String): List<WhatsNewRelease> =
        releases.filter { compare(it.version, appVersion) <= 0 }.sortedWith { a, b -> compare(b.version, a.version) }

    /** The newest highlights [appVersion] has, for Settings. */
    fun latest(releases: List<WhatsNewRelease>, appVersion: String): WhatsNewRelease? =
        history(releases, appVersion).firstOrNull()

    /**
     * Highlights to show on launch: releases newer than the last one seen, up to [appVersion], newest
     * first. Someone updating from before this feature existed has no record, so sees only this build's.
     */
    fun pending(releases: List<WhatsNewRelease>, lastSeen: String?, appVersion: String): List<WhatsNewRelease> {
        val shipped = history(releases, appVersion)
        if (lastSeen.isNullOrEmpty()) return shipped.take(1)
        return shipped.filter { compare(it.version, lastSeen) > 0 }
    }

    /** Dotted numeric versions ("0.10.6"); missing parts count as zero. */
    fun compare(left: String, right: String): Int {
        val a = left.split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}

/** The What's New dialog. Tapping an item closes it and opens that feature. */
@Composable
fun WhatsNewDialog(releases: List<WhatsNewRelease>, onDismiss: () -> Unit, onOpen: (AppFeature) -> Unit,
    /** Off when opened from Settings, which is where the hint points. */
    showsSettingsHint: Boolean = true) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_what_s_new_in_nodescope)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            releases.forEach { release ->
                if (releases.size > 1) Text(stringResource(R.string.settings_version_2, release.version), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                release.items.forEach { item -> WhatsNewRow(item) { item.destination?.let { onDismiss(); onOpen(it) } } }
            }
            if (showsSettingsHint) Text(stringResource(R.string.settings_you_can_review_these_anytime_in_settings_help),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } })
}

@Composable private fun WhatsNewRow(item: WhatsNewItem, open: () -> Unit) {
    val opens = item.destination != null
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .then(if (opens) Modifier.clickable(onClickLabel = stringResource(R.string.settings_open, item.title), role = Role.Button, onClick = open) else Modifier)
        .padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(item.icon.vector(), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, fontFamily = MonaspaceNeon)
            Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.location?.let { location ->
                Text(location, Modifier.padding(top = 2.dp).withDescription(stringResource(R.string.settings_found_in, location)) { description -> contentDescription = description },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (opens) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun String.vector(): ImageVector = when (this) {
    "coverage" -> Icons.Outlined.Sensors
    "savedPlans" -> Icons.Outlined.FolderOpen
    "linkProfile" -> Icons.Outlined.Route
    "keyboard" -> Icons.Outlined.Keyboard
    "nodeCheck" -> Icons.Outlined.Verified
    "share" -> Icons.Outlined.Share
    "drafts" -> Icons.Outlined.EditNote
    "notifications" -> Icons.Outlined.NotificationsNone
    "nodeVision" -> Icons.Outlined.CameraAlt
    else -> Icons.Outlined.AutoAwesome
}
