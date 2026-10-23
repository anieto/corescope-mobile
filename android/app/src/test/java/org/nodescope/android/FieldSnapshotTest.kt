package org.nodescope.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.nodescope.android.core.model.FieldSnapshot

class FieldSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val source = FieldSnapshot.Source("corescope", "https://example.test", "Example", "SAT")
    private val state = FieldSnapshot.State(false, true, listOf("coordinateTimestamp"), listOf("Position age unknown"))

    @Test fun referenceCoordinateIsOmittedByDefault() {
        val snapshot = FieldSnapshot(
            activity = FieldSnapshot.Activity.NEARBY,
            capturedAt = "2026-10-22T20:00:00Z",
            appVersion = "0.10.0",
            source = source,
            state = state,
        )
        val objectValue = json.parseToJsonElement(json.encodeToString(snapshot)).jsonObject
        assertFalse(objectValue.containsKey("reference"))
        assertEquals(FieldSnapshot.SCHEMA, objectValue["schema"]?.toString()?.trim('"'))
    }

    @Test fun decodesAdditiveFieldsAndMissingOptionalArrays() {
        val encoded = """
            {"schema":"org.nodescope.field.snapshot","version":1,"activity":"nearby",
             "capturedAt":"2026-10-22T20:00:00Z","appVersion":"0.10.0",
             "source":{"backend":"corescope","origin":"https://example.test","displayName":"Example"},
             "state":{"cached":false,"partial":false,"unsupported":[],"limitations":[]},
             "futureEvidence":{"value":42}}
        """.trimIndent()
        val snapshot = json.decodeFromString<FieldSnapshot>(encoded)
        assertEquals(emptyList<String>(), snapshot.packetIdentifiers)
        assertEquals(emptyList<String>(), snapshot.routeIdentifiers)
        assertNull(snapshot.reference)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSchemaVersions() {
        val encoded = """
            {"schema":"org.nodescope.field.snapshot","version":2,"activity":"nearby",
             "capturedAt":"2026-10-22T20:00:00Z","appVersion":"0.10.0",
             "source":{"backend":"corescope","origin":"https://example.test","displayName":"Example"},
             "state":{"cached":false,"partial":false,"unsupported":[],"limitations":[]}}
        """.trimIndent()
        json.decodeFromString<FieldSnapshot>(encoded)
    }
}
