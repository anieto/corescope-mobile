package org.nodescope.android.feature.map

import android.content.Context
import androidx.core.content.edit
import org.nodescope.android.BuildConfig

/** Who draws the map: CARTO (the default) or, when the build has a key, Google Maps (beta). */
enum class MapProvider { CARTO, GOOGLE }

/** The chosen provider and Google map type, remembered across launches. */
data class MapProviderChoice(val provider: MapProvider = MapProvider.CARTO, val googleType: GoogleMapType = GoogleMapType.MAP)

/** A saved Google choice falls back to CARTO in a build without a Google key. */
internal fun MapProviderChoice.available(googleConfigured: Boolean) = if (googleConfigured) this else copy(provider = MapProvider.CARTO)

fun loadMapProvider(context: Context): MapProviderChoice {
    val prefs = context.getSharedPreferences("map-provider", Context.MODE_PRIVATE)
    val provider = MapProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: MapProvider.CARTO
    val type = GoogleMapType.entries.firstOrNull { it.name == prefs.getString("googleType", null) } ?: GoogleMapType.MAP
    return MapProviderChoice(provider, type).available(BuildConfig.GOOGLE_MAPS_CONFIGURED)
}

fun saveMapProvider(context: Context, choice: MapProviderChoice) {
    context.getSharedPreferences("map-provider", Context.MODE_PRIVATE).edit {
        putString("provider", choice.provider.name)
        putString("googleType", choice.googleType.name)
    }
}
