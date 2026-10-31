package study
import kotlin.test.*
class AdaptiveTests {
    @Test fun neverAcceptsOneCoarseAgreement(){
        val r=converge(1000.0,200000,152.0,NeverStop()){s->Attempt(AccuracyState.QUALIFIED,if(s>=10)100.0 else 110.0)}
        assertEquals(AccuracyState.QUALIFIED,r.state);assertEquals(1,r.finalSpacingM);assertEquals(110.0,r.lossDb)
    }
    @Test fun boundedWorkNeverPublishesLastEstimate(){
        var calls=0
        val r=converge(100000.0,10000,152.0,NeverStop()){calls++;Attempt(AccuracyState.QUALIFIED,100.0)}
        assertEquals(AccuracyState.WORK_LIMIT,r.state);assertNull(r.lossDb);assertEquals(1,calls);assertTrue(r.samplesUsed<=10000)
    }
    @Test fun thresholdGuardRemainsExplicit(){
        val r=converge(1000.0,200000,152.0,NeverStop()){Attempt(AccuracyState.QUALIFIED,152.01)}
        assertEquals(AccuracyState.THRESHOLD_UNRESOLVED,r.state);assertNull(r.lossDb)
    }
    @Test fun nonconvergentResultIsNotCoverage(){
        val r=converge(1000.0,200000,152.0,NeverStop()){Attempt(AccuracyState.QUALIFIED,100.0+it)}
        assertEquals(AccuracyState.ACCURACY_UNRESOLVED,r.state);assertNull(r.lossDb)
    }
    @Test fun cancellationAndMissingTerrainAreTerminal(){
        var calls=0
        val r=converge(1000.0,200000,152.0,object:StopSignal{override fun isCancelled()=true}){calls++;Attempt(AccuracyState.QUALIFIED,100.0)}
        assertEquals(AccuracyState.CANCELLED,r.state);assertEquals(0,calls)
        val missing=converge(1000.0,200000,152.0,NeverStop()){Attempt(AccuracyState.MISSING_TERRAIN,null)}
        assertEquals(AccuracyState.MISSING_TERRAIN,missing.state);assertNull(missing.lossDb)
    }
    @Test fun invalidNumbersNeverEscape(){
        val r=converge(1000.0,200000,152.0,NeverStop()){Attempt(AccuracyState.QUALIFIED,Double.NaN)}
        assertEquals(AccuracyState.INVALID_MODEL,r.state);assertNull(r.lossDb)
    }
}
