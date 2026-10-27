package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.content.edit
import org.nodescope.android.BuildConfig

/** Who draws the map: CARTO (the default) or, when the build has a key, Google Maps (beta). */
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

/** A saved Google choice falls back to CARTO in a build without a Google key. */
internal fun MapProviderChoice.available(googleConfigured: Boolean) = if (googleConfigured) this else copy(provider = MapProvider.CARTO)

fun loadMapProvider(context: Context): MapProviderChoice {
    val prefs = context.getSharedPreferences("map-provider", Context.MODE_PRIVATE)
    val provider = MapProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: MapProvider.CARTO
    val type = GoogleMapType.entries.firstOrNull { it.name == prefs.getString("googleType", null) } ?: GoogleMapType.MAP
    val style = prefs.getInt("cartoStyle", -1).takeIf { it in 0..2 }
    return MapProviderChoice(provider, type, style).available(BuildConfig.GOOGLE_MAPS_CONFIGURED)
}

fun saveMapProvider(context: Context, choice: MapProviderChoice) {
    context.getSharedPreferences("map-provider", Context.MODE_PRIVATE).edit {
        putString("provider", choice.provider.name)
        putString("googleType", choice.googleType.name)
        choice.cartoStyle?.let { putInt("cartoStyle", it) } ?: remove("cartoStyle")
    }
}
