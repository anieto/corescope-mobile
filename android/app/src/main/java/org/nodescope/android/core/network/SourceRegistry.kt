package org.nodescope.android.core.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nodescope.android.core.model.AnalyzerSource
import org.nodescope.android.core.model.AnalyzerSourceType
import org.nodescope.android.core.model.SourceDocument

/** Where the community registry lives; icons are published next to it. */
const val SOURCE_REGISTRY_URL = "${org.nodescope.android.core.storage.REGISTRY_BASE_URL}sources-v2.json"

/**
 * Decodes a source registry with the iOS `AnalyzerSourceRegistryDocument` rules: versions 1 and 2
 * only; an unknown `type` skips just that entry; a v2 entry without `type` is skipped; blank or
 * duplicate entries are dropped; a malformed entry fails the document, as does an empty result.
 */
fun decodeSourceRegistry(raw: String): SourceDocument {
    val root = protocolJson.parseToJsonElement(raw).jsonObject
    val version = root.getValue("version").jsonPrimitive.int
    require(version == 1 || version == 2) { "Unsupported source registry version." }
    val accepted = mutableListOf<AnalyzerSource>()
    val identifiers = mutableSetOf<String>()
    for (entry in root.getValue("sources").jsonArray) {
        val rawType = entry.jsonObject["type"]?.takeIf { it !is JsonNull }?.let {
            (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                ?: throw IllegalArgumentException("A source type must be a string.")
        }
        if (rawType != null) {
            if (AnalyzerSourceType.from(rawType) == null) continue
        } else if (version == 2) {
            continue
        }
        val source = protocolJson.decodeFromJsonElement(AnalyzerSource.serializer(), entry)
        if (source.host.isBlank() || source.name.isBlank() || source.id.isEmpty() || !identifiers.add(source.id)) continue
        accepted += source
    }
    require(accepted.isNotEmpty()) { "The source registry has no supported entries." }
    return SourceDocument(version, accepted)
}

/** The bundled, cached and downloaded registries must be version 2 (iOS `decodeV2`). */
fun decodeSourceRegistryV2(raw: String): SourceDocument =
    decodeSourceRegistry(raw).also { require(it.version == 2) { "A version 2 source registry is required." } }

