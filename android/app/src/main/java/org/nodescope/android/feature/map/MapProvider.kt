package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.nodescope.android.BuildConfig

/** The supported Android map providers. */
enum class MapProvider { CARTO, GOOGLE }

/**
 * The chosen provider, Google map type and CARTO style (0 standard, 1 light, 2 dark; null follows
 * the app theme), remembered across launches and shared by every map screen.
 */
data class MapProviderChoice(val provider: MapProvider = MapProvider.CARTO, val googleType: GoogleMapType = GoogleMapType.MAP,
    val cartoStyle: Int? = null) {
    /** The CARTO style to draw: the picked one, else the theme's (standard or dark). */
    fun cartoStyle(darkTheme: Boolean) = cartoStyle ?: if (darkTheme) 2 else 0
}

internal fun googleMapsAvailable(context: Context): Boolean =
    BuildConfig.GOOGLE_MAPS_CONFIGURED &&
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

/** A fallback changes the active provider while leaving the saved preference intact. */
internal fun resolveMapProvider(saved: MapProvider?, googleAvailable: Boolean): MapProvider =
    if (googleAvailable) saved ?: MapProvider.GOOGLE else MapProvider.CARTO

fun loadMapProvider(context: Context): MapProviderChoice {
    val prefs = context.getSharedPreferences("map-provider", Context.MODE_PRIVATE)
    val savedProvider = prefs.getString("provider", null)
    val provider = resolveMapProvider(MapProvider.entries.firstOrNull { it.name == savedProvider },
        googleMapsAvailable(context))
    val type = GoogleMapType.entries.firstOrNull { it.name == prefs.getString("googleType", null) } ?: GoogleMapType.MAP
    val style = prefs.getInt("cartoStyle", -1).takeIf { it in 0..2 }
    return MapProviderChoice(provider, type, style)
}

fun saveMapProvider(context: Context, choice: MapProviderChoice) {
    context.getSharedPreferences("map-provider", Context.MODE_PRIVATE).edit {
        putString("provider", choice.provider.name)
        putString("googleType", choice.googleType.name)
        choice.cartoStyle?.let { putInt("cartoStyle", it) } ?: remove("cartoStyle")
    }
}
