package org.nodescope.android.app

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.nodescope.android.BuildConfig
import org.nodescope.android.feature.map.CartoRequests
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.nodescope.android.core.model.SourceDocument
import org.nodescope.android.core.network.*
import org.nodescope.android.core.storage.AndroidPreferences
import org.nodescope.android.core.storage.MonitoredChannelStore

class NodeScopeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Do not log authenticated tile URLs. This client is separate from analyzer requests.
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .addNetworkInterceptor(CartoRequests(BuildConfig.CARTO_API_KEY, packageName, signingSha1()))
            .build())
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        val signature = requireNotNull(signatures?.firstOrNull()) { "App signing certificate unavailable" }
        return MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    val container: AppContainer by lazy { AppContainer(this) }
}
class AppContainer(application: Application) {
    val preferences = AndroidPreferences(application)
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "NodeScope-Android/${BuildConfig.VERSION_NAME}").build())
    }.connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    /** Public-domain OurAirports IATA positions, used where an analyzer lacks region centers. */
    private val airports by lazy {
        runCatching { parseAirportTable(application.assets.open("iata-airports.csv").bufferedReader().use { it.readText() }) }.getOrDefault(emptyMap())
    }
    /** Last successful analyzer responses, shown immediately on launch and when offline. */
    val responses = ResponseCache(java.io.File(application.cacheDir, "api-responses"))
    val repository = HttpAnalyzerRepository(client, responses) { airports }
    val packetSource = HttpPacketSource(client)
    val browse = HttpBrowseRepository(client, responses)
    val diagnostics = AnalyzerDiagnostics(client)
    val cacheStorage = org.nodescope.android.core.storage.CacheStorage(application)
    /** Opened on first use: reading sealed channel keys touches the Android Keystore. */
    val monitoredChannels by lazy { MonitoredChannelStore.forDevice(application) }
    val bundledSources: SourceDocument = application.assets.open("us-sources.json").bufferedReader().use {
        protocolJson.decodeFromString(it.readText())
    }
}
