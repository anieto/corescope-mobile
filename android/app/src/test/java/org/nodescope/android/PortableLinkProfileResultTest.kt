package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.SavedLinkProfile
import org.nodescope.android.feature.linkprofile.PortableLinkProfileResult

/** nodescope.link-profile-result v1 against the shared cases (shared/fixtures/saved-plans/results), as iOS. */
class PortableLinkProfileResultTest {
    private val cases = Json.parseToJsonElement(File("../../shared/fixtures/saved-plans/results/result-cases-v1.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }.filter { it.getValue("kind").jsonPrimitive.content == "linkProfile" }

    /** Numbers compared by value, so 1000 and 1000.0 or 1e-05 and 1.0E-5 match. */
    private fun normalized(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { normalized(it.value) })
        is JsonArray -> JsonArray(element.map(::normalized))
        is JsonPrimitive -> if (element.isString || element is JsonNull || element.booleanOrNull != null) element
            else JsonPrimitive(element.content.toDouble())
    }

    @Test fun everySharedLinkProfileCaseIsReadAsExpected() {
        assertEquals(30, cases.size)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val study = Json.decodeFromJsonElement(SavedLinkProfile.serializer(), case.getValue("study"))
            val payload = case.getValue("payload")
            val bytes = Json.encodeToString(JsonElement.serializer(), payload).toByteArray()
            val outcome = runCatching { PortableLinkProfileResult.decode(bytes, study) }
            assertEquals("$name: ${outcome.exceptionOrNull()}", case.getValue("valid").jsonPrimitive.boolean, outcome.isSuccess)
            val analysis = outcome.getOrNull() ?: continue
            // Writing it back gives the same payload, so a result survives any number of saves.
            val again = requireNotNull(PortableLinkProfileResult.encode(analysis, study)) { name }
            assertTrue(name, PortableLinkProfileResult.isPortable(again))
            assertEquals(name, normalized(payload), normalized(Json.parseToJsonElement(again.decodeToString())))
        }
    }
}
