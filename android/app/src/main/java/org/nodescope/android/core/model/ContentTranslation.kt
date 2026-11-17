package org.nodescope.android.core.model

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import org.nodescope.android.R

/** Language and overlay lookup shared by bundled content and the source registry. */
internal object ContentTranslation {
    private val json = Json { ignoreUnknownKeys = true }

    fun languages(tag: String): List<String> {
        val normalized = normalize(tag)
        val base = normalized.split('-').firstOrNull { it.isNotEmpty() } ?: return emptyList()
        if (base.equals("en", ignoreCase = true)) return emptyList()
        return if (base == normalized) listOf(base) else listOf(normalized, base)
    }

    fun pick(values: Map<String, String>?, languages: List<String>): String? =
        languages.firstNotNullOfOrNull { language ->
            values?.entries?.firstOrNull { normalize(it.key).equals(normalize(language), ignoreCase = true) }
                ?.value.nonBlankTranslation()
        }

    fun language(context: Context): String = context.getString(R.string.content_language)

    inline fun <reified T> load(context: Context, name: String, language: String): T? {
        val candidates = languages(language)
        if (candidates.isEmpty()) return null
        val files = try {
            context.assets.list("").orEmpty()
        } catch (error: java.io.IOException) {
            Log.w("ContentTranslation", "Could not enumerate bundled content overlays", error)
            return null
        }
        for (tag in candidates) {
            val expected = "$name.$tag.json"
            val file = files.firstOrNull { normalize(it).equals(expected, ignoreCase = true) } ?: continue
            try {
                return context.assets.open(file).bufferedReader().use { json.decodeFromString<T>(it.readText()) }
            } catch (error: java.io.IOException) {
                Log.w("ContentTranslation", "Could not read $file", error)
            } catch (error: IllegalArgumentException) {
                Log.w("ContentTranslation", "Ignoring invalid content overlay $file", error)
            }
        }
        return null
    }

    private fun normalize(tag: String): String = tag.replace('_', '-')
}

internal fun String?.nonBlankTranslation(): String? = this?.takeUnless(String::isBlank)
