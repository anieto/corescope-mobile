package org.nodescope.android.core.network

import android.content.Context

object PushPlatform {
    const val relayOrigin = "https://push-staging.nodescope.app"
    const val environment = "staging"
    const val enabled = false
    fun initialize(context: Context) = Unit
    fun canNotify(context: Context) = false
    suspend fun token(context: Context): String = error("Staging push is disabled")
}
