package org.nodescope.android.core.design

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/** Copies on an explicit user action. Sensitive text (channel keys) is hidden from clipboard previews. */
@Composable
fun rememberCopyAction(): (label: String, text: String, sensitive: Boolean) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { label, text, sensitive ->
        val clip = ClipData.newPlainText(label, text).apply {
            if (sensitive) description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
    }
}
