package org.nodescope.android.core.network

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** A value restored from disk, with when its response was downloaded. */
data class Cached<T>(val value: T, val savedAt: Long)

class NotCached : IOException("No saved response.")

/**
 * Last successful analyzer responses, as on iOS `APIResponseCache`. Bodies are stored verbatim
 * under the app cache directory, keyed by full URL (so analyzer, region and entity are
 * isolated), and "Clear cache" in Settings removes them with the rest of the cache directory.
 */
class ResponseCache(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private fun file(url: HttpUrl) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(url.toString().toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    /** The body saved at most [maxAgeMillis] ago, or null. */
    suspend fun read(url: HttpUrl, maxAgeMillis: Long): Cached<String>? = withContext(Dispatchers.IO) {
        runCatching {
            val text = file(url).takeIf { it.isFile }?.readText() ?: return@runCatching null
            val split = text.indexOf('\n')
            val savedAt = text.substring(0, split).toLong()
            if (clock() - savedAt > maxAgeMillis || savedAt > clock()) null else Cached(text.substring(split + 1), savedAt)
        }.getOrNull()
    }

    /** Written to a temporary file and renamed, so a crash never leaves a partial response. */
    suspend fun write(url: HttpUrl, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val target = file(url)
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText("${clock()}\n$body")
            if (!temporary.renameTo(target)) temporary.delete()
        }
        Unit
    }

    /** Removes responses no screen would still show. */
    suspend fun prune(maxAgeMillis: Long = STALE_LIFETIME) = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { file ->
            if (clock() - file.lastModified() > maxAgeMillis) file.delete()
        }
        Unit
    }

    companion object {
        /** Lists (nodes, observers, packets, map configuration), as on iOS. */
        const val STALE_LIFETIME = 7 * 24 * 60 * 60_000L
        /** Per-entity detail (node sections and analytics, observer analytics, packets), as on iOS. */
        const val DETAIL_LIFETIME = 24 * 60 * 60_000L
    }
}

/** How repositories obtain response bodies: from the network, or only from saved responses. */
fun interface BodySource {
    suspend fun read(url: HttpUrl): String
}

/** Network reads that save each successful body. */
fun OkHttpClient.saving(cache: ResponseCache?) = BodySource { url -> read(url).also { cache?.write(url, it) } }

/** Saved bodies only; records the oldest response served so the screen can say how old it is. */
class SavedBodies(private val cache: ResponseCache, private val maxAgeMillis: Long) : BodySource {
    var oldest: Long? = null
        private set
    override suspend fun read(url: HttpUrl): String {
        val saved = cache.read(url, maxAgeMillis) ?: throw NotCached()
        oldest = minOf(oldest ?: saved.savedAt, saved.savedAt)
        return saved.value
    }
}

/** Runs [block] against saved responses; null when anything it needs is missing, expired or unreadable. */
suspend fun <T> ResponseCache.restore(maxAgeMillis: Long, block: suspend (BodySource) -> T): Cached<T>? {
    val source = SavedBodies(this, maxAgeMillis)
    return try {
        val value = block(source)
        source.oldest?.let { Cached(value, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
