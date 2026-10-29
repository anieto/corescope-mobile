package org.nodescope.android.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.nodescope.android.core.model.DEFAULT_HOST

enum class Appearance { SYSTEM, LIGHT, DARK }
/** How distances are shown. Imperial is the default (the app's communities are in the US). */
enum class DistanceUnit { IMPERIAL, METRIC }
data class AppPreferences(
    val host: String = DEFAULT_HOST,
    val onboarded: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
    val region: String? = null,
    val destination: String = "MAP",
    val registry: String? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.IMPERIAL,
    val backgroundTexture: Boolean = true,
)
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
}
private val Context.preferences by preferencesDataStore("nodescope")
class AndroidPreferences(context: Context) : PreferencesStore {
    private val store = context.preferences
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
            DistanceUnit.entries.firstOrNull { unit -> unit.name == it[distanceUnit] } ?: DistanceUnit.IMPERIAL,
            it[backgroundTexture] ?: true)
    }
    override suspend fun selectSource(host: String) {
        store.edit { it[this.host] = host; it[onboarded] = true; it.remove(region) }
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
}
