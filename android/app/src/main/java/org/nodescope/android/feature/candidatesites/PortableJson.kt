package org.nodescope.android.feature.candidatesites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Strict reading for portable result payloads (saved-plans-v3-result-fields.md): every accessor
 * throws on a missing field or the wrong type, and [exactly] refuses unknown fields.
 */
internal object PortableJson {
    fun parse(bytes: ByteArray, limit: Int): JsonObject {
        require(bytes.size <= limit)
        return Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).objectValue()
    }

    val sha = Regex("[0-9a-f]{64}")
    const val MAX_MS = 253_402_300_799_999
}

internal fun JsonElement.objectValue() = this as? JsonObject ?: throw IllegalArgumentException()
internal fun JsonObject.exactly(vararg names: String) = require(keys == names.toSet())
internal fun JsonObject.field(name: String) = this[name] ?: throw IllegalArgumentException()
internal fun JsonObject.nullable(name: String): JsonElement? = field(name).takeIf { it !is JsonNull }
internal fun JsonObject.obj(name: String) = field(name).objectValue()
internal fun JsonObject.array(name: String) = field(name) as? JsonArray ?: throw IllegalArgumentException()
internal fun JsonObject.string(name: String): String =
    (field(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
internal fun JsonObject.boolean(name: String): Boolean =
    (field(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException()
internal fun JsonObject.long(name: String) = field(name).asLong(Long.MIN_VALUE..Long.MAX_VALUE)
internal fun JsonObject.double(name: String) = field(name).asDouble()

/** Integers only: a number written with a fraction or exponent isn't one. */
internal fun JsonElement.asLong(range: LongRange): Long {
    val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException()
    require(!primitive.isString && primitive !is JsonNull)
    return primitive.longOrNull?.takeIf { it in range } ?: throw IllegalArgumentException()
}

internal fun JsonElement.asDouble(): Double {
    val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException()
    require(!primitive.isString && primitive !is JsonNull && primitive.booleanOrNull == null)
    return primitive.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException()
}
