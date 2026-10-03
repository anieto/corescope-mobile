package org.nodescope.android.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * How this copy of the app identifies itself to CARTO: its package name and the SHA-1 of the
 * certificate Android reports it is signed with (sent as X-Android-Package / X-Android-Cert).
 * The certificate depends on how the copy was installed (Play, a test APK, a debug build), so it
 * is also shown in About and in the map's "refused" error, for adding to the key's allowed apps.
 */
object AppIdentity {
    /** Upper-case hex without separators, as sent to CARTO. */
    @Suppress("DEPRECATION")
    fun signingSha1(context: Context): String {
        val packageManager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        val signature = requireNotNull(signatures?.firstOrNull()) { "App signing certificate unavailable" }
        return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    /** "AB:CD:…", as key consoles show certificate fingerprints. */
    fun fingerprint(sha1: String) = sha1.chunked(2).joinToString(":")
}
