package study
import kotlin.test.*
class WideTests {
    @Test fun preflightDoesNotAllocateOversizedJob(){
        val bad=WideScenarios.preflight(WideConfig(100000,200,30,-100000,100000,-100000,100000))
        assertFalse(bad.accepted);assertEquals("cell_budget",bad.reason);assertEquals(1002001,bad.cells)
        assertTrue(WideScenarios.preflight(WideScenarios.config(5)).accepted)
        val heavy=WideScenarios.preflight(WideConfig(100000,1000,10,-100000,100000,-100000,100000))
        assertFalse(heavy.accepted);assertEquals("sample_work_budget",heavy.reason)
    }
    @Test fun boundsAndProfileSpacingIndependent(){
        assertFalse(WideScenarios.preflight(WideConfig(10000,200,30,0,501,0,400)).accepted)
        assertFalse(WideScenarios.preflight(WideConfig(10000,200,30,-20000,0,0,400)).accepted)
        val coarse=WideScenarios.preflight(WideScenarios.config(2));val finer=WideScenarios.preflight(WideScenarios.config(4))
        assertEquals(coarse.maxSamples,finer.maxSamples);assertTrue(finer.cells>coarse.cells)
    }
    @Test fun sharedSamplerCrossesRasterSeamWithoutChangingSign(){
        val e=WideEngine(object:RasterReader{override fun value(index:Int)=listOf(-10,0,10,20,30,40)[index]},3,2,51.0,-115.0,3600,50.95,-115.15)
        assertEquals(5.0,e.elevationAt(51.0-.5/3600,-115.0)!!,1e-6)
        assertEquals(15.0,e.elevationAt(51.0-.5/3600,-115.0+1.0/3600)!!,1e-6)
        assertNull(e.elevationAt(52.0,-115.0))
    }
    @Test fun nodataAndCancelledStatesRemainTyped(){
        val e=WideEngine(object:RasterReader{override fun value(index:Int)=-32768},2,2,51.0,-115.0,3600,51.0,-115.0)
        assertNull(e.elevationAt(51.0,-115.0))
        val j=e.makeJob(WideScenarios.config(0)).job!!
        j.step(16,object:StopSignal{override fun isCancelled()=true})
        assertEquals(JobState.CANCELLED,j.state);assertEquals(0,j.step(16,NeverStop()))
    }
}
