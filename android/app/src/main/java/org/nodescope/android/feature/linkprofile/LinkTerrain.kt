package org.nodescope.android.feature.linkprofile

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.nodescope.android.core.network.read
import kotlin.math.*

@Serializable
data class ProfileCoordinate(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Enter valid latitude and longitude." } }
}
data class TerrainProfile(val points: List<ElevationPoint>, val fetchedAt: Long, val origin: String)
data class LinkAnalysis(val request: PathRequest, val terrain: TerrainProfile, val result: PathResult, val bearing: Double, val calculatedAt: Long)

object LinkGeometry {
    fun distance(a: ProfileCoordinate, b: ProfileCoordinate): Double {
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val h = sin((latB - latA) / 2).pow(2) + cos(latA) * cos(latB) * sin(Math.toRadians(b.longitude - a.longitude) / 2).pow(2)
        return 2 * LinkProfileEngine.EARTH_RADIUS_METERS * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceAtLeast(0.0)))
    }
    fun bearing(a: ProfileCoordinate, b: ProfileCoordinate): Double {
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val delta = Math.toRadians(b.longitude - a.longitude)
        return (Math.toDegrees(atan2(sin(delta) * cos(latB), cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(delta))) + 360) % 360
    }
    fun sampleCount(distance: Double) = when { distance < 1_000 -> 20; distance < 5_000 -> 50; distance < 20_000 -> 80; else -> 100 }
    fun samples(a: ProfileCoordinate, b: ProfileCoordinate, count: Int): List<ProfileCoordinate> {
        require(count in 2..100)
        var delta = b.longitude - a.longitude
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return List(count) { index ->
            val fraction = index.toDouble() / (count - 1)
            var longitude = a.longitude + fraction * delta
            if (longitude > 180) longitude -= 360
            if (longitude < -180) longitude += 360
            ProfileCoordinate(a.latitude + fraction * (b.latitude - a.latitude), longitude)
        }
    }
}

fun interface LinkTerrainProviding {
    suspend fun profile(coordinates: List<ProfileCoordinate>): TerrainProfile
}

/** Coordinates go only to Open-Meteo, never the selected analyzer. Cache is bounded and expires after 30 days. */
class LinkTerrainProvider(
    private val cacheFile: File,
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoint: HttpUrl = "https://api.open-meteo.com/v1/elevation".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
) : LinkTerrainProviding {
    @Serializable private data class Record(val coordinate: ProfileCoordinate, val elevation: Double, val fetchedAt: Long)
    @Serializable private data class Response(val elevation: List<Double>)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val lifetime = 30L * 24 * 60 * 60 * 1000

    override suspend fun profile(coordinates: List<ProfileCoordinate>): TerrainProfile = withContext(Dispatchers.IO) {
        require(coordinates.size in 2..100)
        mutex.withLock {
            val time = now()
            val records = try {
                json.decodeFromString<List<Record>>(cacheFile.readText()).filter { time - it.fetchedAt in 0..lifetime && it.elevation.isFinite() }
            } catch (_: IOException) { emptyList() } catch (_: IllegalArgumentException) { emptyList() }
            val resolved = records.associateBy { it.coordinate }.toMutableMap()
            val missing = coordinates.distinct().filter { it !in resolved }
            if (missing.isNotEmpty()) {
                val url = endpoint.newBuilder()
                    .addQueryParameter("latitude", missing.joinToString(",") { it.latitude.toString() })
                    .addQueryParameter("longitude", missing.joinToString(",") { it.longitude.toString() }).build()
                val elevations = json.decodeFromString<Response>(client.read(url)).elevation
                require(elevations.size == missing.size && elevations.all(Double::isFinite)) { "The elevation service returned invalid terrain data." }
                missing.zip(elevations).forEach { (coordinate, elevation) -> resolved[coordinate] = Record(coordinate, elevation, time) }
                coroutineContext.ensureActive()
                // Failed cache writes must not discard a successful network calculation.
                try {
                    cacheFile.parentFile?.mkdirs()
                    val temp = File(cacheFile.path + ".tmp")
                    temp.writeText(json.encodeToString(resolved.values.sortedByDescending { it.fetchedAt }.take(10_000)))
                    if (!temp.renameTo(cacheFile)) temp.delete()
                } catch (_: IOException) { }
            }
            var distance = 0.0
            val points = coordinates.mapIndexed { index, coordinate ->
                if (index > 0) distance += LinkGeometry.distance(coordinates[index - 1], coordinate)
                ElevationPoint(distance, resolved.getValue(coordinate).elevation)
            }
            TerrainProfile(points, coordinates.minOf { resolved.getValue(it).fetchedAt },
                when { missing.isEmpty() -> "cache"; missing.size == coordinates.distinct().size -> "network"; else -> "mixed" })
        }
    }
}
