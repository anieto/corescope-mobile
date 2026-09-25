package org.nodescope.android.core.design

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler

/**
 * Web links open in a Custom Tab over the app, as iOS opens them in Safari's in-app view.
 * Anything else (mailto:, tel:, nodescope:) goes to the system as before, and a device with no
 * Custom Tabs browser falls back to a normal browser.
 */
class InAppUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = uri.toUri()
        if (parsed.scheme.equals("https", true) || parsed.scheme.equals("http", true)) {
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, parsed)
                return
            } catch (_: ActivityNotFoundException) {
                // No Custom Tabs browser; use whatever handles the link.
            }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
