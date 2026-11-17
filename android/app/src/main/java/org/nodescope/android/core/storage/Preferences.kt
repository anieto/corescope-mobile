package org.nodescope.android.core.storage

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.nodescope.android.core.model.DEFAULT_HOST

enum class Appearance { SYSTEM, LIGHT, DARK }
/** Until the user chooses a unit, distances follow the device region. */
enum class DistanceUnit {
    IMPERIAL, UK, METRIC;

    val usesMiles: Boolean get() = this != METRIC
    val usesFeet: Boolean get() = this == IMPERIAL

    companion object {
        fun defaultUnit(locale: Locale = Locale.getDefault()): DistanceUnit {
            if (Build.VERSION.SDK_INT >= 24) {
                return when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                    LocaleData.MeasurementSystem.US -> IMPERIAL
                    LocaleData.MeasurementSystem.UK -> UK
                    else -> METRIC
                }
            }
            // The host JVM has no Android ICU. Keep region mapping available to local unit tests.
            return when (locale.country) {
                "US", "LR" -> IMPERIAL
                "GB", "MM" -> UK
                else -> METRIC
            }
        }
    }
}
data class AppPreferences(
    val host: String = DEFAULT_HOST,
    val onboarded: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
    val region: String? = null,
    val destination: String = "MAP",
    val registry: String? = null,
    val storedDistanceUnit: DistanceUnit? = null,
    val backgroundTexture: Boolean = true,
) {
    val distanceUnit: DistanceUnit get() = storedDistanceUnit ?: DistanceUnit.defaultUnit()
}
interface PreferencesStore {
    val values: Flow<AppPreferences>
    suspend fun selectSource(host: String)
    suspend fun setRegion(region: String?)
    suspend fun setAppearance(appearance: Appearance)
    suspend fun setDestination(destination: String)
    suspend fun cacheRegistry(document: String)
    suspend fun setDistanceUnit(unit: DistanceUnit) {}
    suspend fun setBackgroundTexture(enabled: Boolean) {}
    suspend fun setOnboarded(value: Boolean) {}
    /** Records [current] as the last What's New seen and returns the previous one (null if never recorded). */
    suspend fun takeWhatsNewLastSeen(current: String): String? = current
}
private val Context.preferences by preferencesDataStore("nodescope")
class AndroidPreferences(context: Context, private val appVersion: String = "") : PreferencesStore {
    private val store = context.preferences
    private val whatsNewLastSeen = stringPreferencesKey("whats_new_last_seen")
    private val host = stringPreferencesKey("host")
    private val onboarded = booleanPreferencesKey("onboarded")
    private val appearance = stringPreferencesKey("appearance")
    private val region = stringPreferencesKey("region")
    private val destination = stringPreferencesKey("destination")
    /** Typed v2 registry; the old untyped document under "registry" is ignored (iOS does the same). */
    private val registry = stringPreferencesKey("registry_typed_v2")
    private val distanceUnit = stringPreferencesKey("distance_unit")
    private val backgroundTexture = booleanPreferencesKey("background_texture")
    override val values = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map {
        AppPreferences(it[host] ?: DEFAULT_HOST, it[onboarded] ?: false,
            Appearance.entries.firstOrNull { mode -> mode.name == it[appearance] } ?: Appearance.SYSTEM,
            it[region], it[destination] ?: "MAP", it[registry],
            DistanceUnit.entries.firstOrNull { unit -> unit.name == it[distanceUnit] },
            it[backgroundTexture] ?: true)
    }
    override suspend fun selectSource(host: String) {
        store.edit {
            // Finishing onboarding counts as having seen this build's highlights: new installs aren't shown them.
            if (it[onboarded] != true && appVersion.isNotEmpty()) it[whatsNewLastSeen] = appVersion
            it[this.host] = host; it[onboarded] = true; it.remove(region)
        }
    }
    override suspend fun setRegion(region: String?) {
        store.edit { if (region == null) it.remove(this.region) else it[this.region] = region }
    }
    override suspend fun setAppearance(appearance: Appearance) { store.edit { it[this.appearance] = appearance.name } }
    override suspend fun setDestination(destination: String) { store.edit { it[this.destination] = destination } }
    override suspend fun cacheRegistry(document: String) { store.edit { it[registry] = document } }
    override suspend fun setDistanceUnit(unit: DistanceUnit) { store.edit { it[distanceUnit] = unit.name } }
    override suspend fun setBackgroundTexture(enabled: Boolean) { store.edit { it[backgroundTexture] = enabled } }
    override suspend fun setOnboarded(value: Boolean) { store.edit { it[onboarded] = value } }
    override suspend fun takeWhatsNewLastSeen(current: String): String? {
        var previous: String? = null
        store.edit { previous = it[whatsNewLastSeen]; it[whatsNewLastSeen] = current }
        return previous
    }
}
