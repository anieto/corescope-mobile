package org.nodescope.android.core.storage

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where registry icons are published: next to `us-sources.json` in the NodeScope repo. */
const val REGISTRY_BASE_URL = "https://raw.githubusercontent.com/anieto/corescope-mobile/main/CommunitySources/"

private val iconPath = Regex("icons/[a-z0-9][a-z0-9-]*\\.png")

/**
 * A registry icon path, or null. Only `icons/<name>.png` inside the registry folder is
 * accepted, so a registry entry can never make the app contact another server.
 */
fun sourceIconPath(icon: String?): String? = icon?.trim()?.takeIf(iconPath::matches)

/**
 * Community source logos. Icons that ship with the app (the bundled registry folder) load
 * offline; others are downloaded once from the registry and kept in the cache directory,
 * so Settings → Clear cache removes them. A changed icon should use a new file name.
 */
class SourceIcons(private val context: Context, private val client: OkHttpClient) {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val directory get() = File(context.cacheDir, "source-icons")

    suspend fun load(icon: String?): ImageBitmap? {
        val path = sourceIconPath(icon) ?: return null
        memory[path]?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = bundled(path) ?: cached(path) ?: download(path)
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()?.also { memory[path] = it }
        }
    }

    private fun bundled(path: String) = runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()

    private fun cached(path: String) = File(directory, path.substringAfter('/')).takeIf { it.isFile }?.readBytes()

    private fun download(path: String): ByteArray? = try {
        client.newCall(Request.Builder().url(REGISTRY_BASE_URL + path).build()).execute().use { response ->
            // Registry icons are small; refuse anything that isn't.
            response.body?.takeIf { response.isSuccessful && it.contentLength() in 1..MAX_BYTES }?.bytes()
        }?.takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }?.also { bytes ->
            directory.mkdirs()
            File(directory, path.substringAfter('/')).writeBytes(bytes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object { const val MAX_BYTES = 512L * 1024 }
}
