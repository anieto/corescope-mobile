package org.nodescope.android.feature.coverage

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class CoverageTerrainCatalogState { OVERSIZED, INVALID_DOCUMENT, UNSUPPORTED_VERSION, DUPLICATE_TILE }
class CoverageTerrainCatalogFailure(val state: CoverageTerrainCatalogState) : Exception(state.name)

/** A bounded pinned regional catalog; parsing does not establish provider trust or availability. */
class CoverageTerrainCatalog(data: ByteArray) {
    val descriptors: List<CoverageTerrainDescriptor>
    init {
        if (data.size > MAXIMUM_BYTES) throw CoverageTerrainCatalogFailure(CoverageTerrainCatalogState.OVERSIZED)
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            val document = Json.parseToJsonElement(text).jsonObject
            if (document.number("version") != 1L) throw CoverageTerrainCatalogFailure(CoverageTerrainCatalogState.UNSUPPORTED_VERSION)
            val tiles = document.getValue("tiles").jsonArray
            require(tiles.size in 1..4096)
            val side = document.number("side")
            require(side in 2..3601)
            val seen = mutableSetOf<CoverageTerrainTile>()
            descriptors = tiles.map { element ->
                val tile = element.jsonObject
                val url = tile.text("url")
                val descriptor = CoverageTerrainDescriptor(document.text("dataset"), document.text("revision"),
                    tile.text("tile"), tile.text("sha256"), tile.number("bytes"), side.toInt(), url,
                    document.text("vertical_datum"), document.text("attribution"), document.text("source_type"), url)
                if (!seen.add(descriptor.coordinate)) throw CoverageTerrainCatalogFailure(CoverageTerrainCatalogState.DUPLICATE_TILE)
                descriptor
            }
        } catch (failure: CoverageTerrainCatalogFailure) { throw failure }
        catch (_: Exception) { throw CoverageTerrainCatalogFailure(CoverageTerrainCatalogState.INVALID_DOCUMENT) }
    }

    fun resolve(footprint: CoverageTerrainFootprint): List<CoverageTerrainDescriptor> = footprint.resolve(descriptors)

    companion object { const val MAXIMUM_BYTES = 4 * 1024 * 1024 }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
}
private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString)
    it.content.toBigDecimal().longValueExact()
}
