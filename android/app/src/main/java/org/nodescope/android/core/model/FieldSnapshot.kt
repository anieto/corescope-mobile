package org.nodescope.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FieldSnapshot(
    @EncodeDefault val schema: String = SCHEMA,
    @EncodeDefault val version: Int = VERSION,
    val activity: Activity,
    val capturedAt: String,
    val appVersion: String,
    val source: Source,
    val target: Target? = null,
    /** Present only after an explicit per-export opt-in. */
    val reference: Reference? = null,
    val geometry: Geometry? = null,
    val packetIdentifiers: List<String> = emptyList(),
    val routeIdentifiers: List<String> = emptyList(),
    val state: State,
) {
    init {
        require(schema == SCHEMA && version == VERSION) { "Unsupported Field Snapshot schema or version" }
    }

    @Serializable enum class Activity { @SerialName("nearby") NEARBY, @SerialName("plan") PLAN }

    @Serializable
    data class Source(val backend: String, val origin: String, val displayName: String, val regionScope: String? = null)

    @Serializable data class Coordinate(val latitude: Double, val longitude: Double)

    @Serializable
    data class Target(
        val kind: Kind,
        val identifier: String,
        val displayName: String,
        val role: String? = null,
        val reportedCoordinate: Coordinate? = null,
        val coordinateAt: String? = null,
        val activityAt: String? = null,
        val catalogFetchedAt: String,
    ) {
        @Serializable enum class Kind { @SerialName("node") NODE, @SerialName("observer") OBSERVER }
    }

    @Serializable
    data class Reference(
        val category: Category,
        val coordinate: Coordinate,
        val measuredAt: String? = null,
        val horizontalAccuracyMeters: Double? = null,
    ) {
        @Serializable enum class Category { @SerialName("device") DEVICE, @SerialName("manual") MANUAL }
    }

    @Serializable data class Geometry(val distanceMeters: Double, val trueBearingDegrees: Double? = null)

    @Serializable
    data class State(
        val cached: Boolean,
        val partial: Boolean,
        val unsupported: List<String>,
        val limitations: List<String>,
    )

    companion object {
        const val SCHEMA = "org.nodescope.field.snapshot"
        const val VERSION = 1
    }
}
