package org.nodescope.android

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.feature.candidatesites.*

class DeploymentPlanTest {
    private fun fixtures() = Json.parseToJsonElement(
        File("../../shared/fixtures/candidate-sites/plan-v1-cases.json").readText()).jsonArray

    @Test fun sharedContractFixturesAndRoundTrips() {
        val cases = fixtures()
        assertEquals(30, cases.size)
        for (item in cases) {
            val fixture = item.jsonObject
            val name = fixture.getValue("name").jsonPrimitive.content
            val bytes = fixture.getValue("document").toString().toByteArray()
            if (fixture.getValue("valid").jsonPrimitive.boolean) {
                val decoded = DeploymentPlanCodec.decode(bytes)
                assertEquals(name, decoded, DeploymentPlanCodec.decode(DeploymentPlanCodec.encode(decoded)))
            } else {
                assertThrows(name, Exception::class.java) { DeploymentPlanCodec.decode(bytes) }
            }
        }
    }

    @Test fun rejectsNonFiniteAssumptionsAndOversizeInput() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val error = assertThrows(DeploymentPlanException::class.java) {
                CandidateSiteAssumptions(antennaHeightMeters = value).validate()
            }
            assertEquals(DeploymentPlanFailure.INVALID_DATA, error.reason)
        }
        val error = assertThrows(DeploymentPlanException::class.java) {
            DeploymentPlanCodec.decode(ByteArray(DeploymentPlanCodec.MAXIMUM_BYTES + 1))
        }
        assertEquals(DeploymentPlanFailure.TOO_LARGE, error.reason)
    }

    @Test fun missingAssumptionsStayUnknown() {
        val fixture = fixtures().first { it.jsonObject.getValue("name").jsonPrimitive.content == "unspecified_assumptions" }
        val document = DeploymentPlanCodec.decode(fixture.jsonObject.getValue("document").toString().toByteArray())
        assertEquals(CandidateSiteAssumptions(), document.plans.single().sites.single().assumptions)
    }

    @Test fun manualInputPreservesUnknownAndCanonicalizesDateLine() {
        val id = "22222222-2222-4222-8222-222222222222"
        val site = CandidateSiteInput.make(id, "Ridge", listOf("29,5", "180", "", "915", "0", "-3", "0", ""))
        assertEquals(29.5, site.latitude, 0.0)
        assertEquals(-180.0, site.longitude, 0.0)
        assertNull(site.assumptions.antennaHeightMeters)
        assertEquals(0.0, site.assumptions.transmitPowerDBm)
        assertEquals(-3.0, site.assumptions.antennaGainDBi)
        assertNull(site.assumptions.additionalLossDB)
        for (text in listOf("nan", "inf", "1e999", "0x1p2", "1.2.3", "1,2,3", "12 watts")) {
            assertThrows(DeploymentPlanException::class.java) {
                CandidateSiteInput.make(id, "Ridge", listOf("29", "-98", "", "", text, "", "", ""))
            }
        }
        assertThrows(DeploymentPlanException::class.java) {
            CandidateSiteInput.make(id, "Ridge", listOf("", "-98", "", "", "", "", "", ""))
        }
        assertThrows(DeploymentPlanException::class.java) { CandidateSiteInput.make(id, "Ridge", emptyList()) }
    }
}
