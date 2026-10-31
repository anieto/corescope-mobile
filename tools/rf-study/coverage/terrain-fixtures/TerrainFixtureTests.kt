package study

import kotlin.math.abs
import kotlin.test.*

class TerrainFixtureTests {
    // This records a known policy defect, not a successful scientific acceptance gate.
    @Test fun knownRidgeBlindSpotMustRemainVisible() {
        val result=converge(1000.0,200000,152.0,NeverStop()) { step ->
            Attempt(AccuracyState.QUALIFIED,TerrainFixtures.loss("ridge",1000.0,.75,1.0,step.toDouble()))
        }
        val reference=TerrainFixtures.loss("ridge",1000.0,.75,1.0,.25)
        assertEquals(AccuracyState.QUALIFIED,result.state)
        assertEquals(5,result.finalSpacingM)
        assertTrue(abs(assertNotNull(result.lossDb)-reference)>40)
    }
    @Test fun flatProfileDoesNotChangeWithReferenceDensity() {
        val coarse=TerrainFixtures.loss("flat",1000.0,0.0,0.0,5.0)
        val dense=TerrainFixtures.loss("flat",1000.0,0.0,0.0,.125)
        assertTrue(abs(coarse-dense)<.01)
    }
}
