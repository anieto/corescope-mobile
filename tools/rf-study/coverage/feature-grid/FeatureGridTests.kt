package study
import kotlin.test.*

class FeatureGridTests {
    private fun engine()=WideEngine(object:RasterReader{override fun value(index:Int)=100},20000,5000,52.0,-117.0,3600,50.96,-115.16)
    private fun config(samples:Long=150000000,geometry:Long=150000000)=FeatureGridConfig(BoundedConfig(1000,500,500,1000,0,0,samples,250000),geometry,200000)
    private fun finish(job:FeatureGridJob) {while(job.state==BoundedState.RUNNING)job.step(1,NeverStop())}
    @Test fun preflightChecksBothBudgetsBeforeCreatingCells() {
        assertEquals("minimum_work_budget",FeatureGridPreflight.estimate(config(samples=1)).reason)
        assertEquals("minimum_geometry_budget",FeatureGridPreflight.estimate(config(geometry=1)).reason)
        assertFailsWith<IllegalArgumentException>{FeatureGridJob(engine(),config(geometry=1))}
        assertEquals("cell_budget",FeatureGridPreflight.estimate(FeatureGridConfig(BoundedConfig(100000,200,-100000,100000,-100000,100000,150000000,250000),150000000,200000)).reason)
    }
    @Test fun samplingBudgetIsEnforcedIncludingDiscovery() {
        val base=config();val minimum=FeatureGridPreflight.estimate(base).minimumSamples
        val job=FeatureGridJob(engine(),config(samples=minimum));finish(job)
        assertEquals(BoundedState.WORK_LIMIT,job.state);assertTrue(job.samplesUsed<=minimum)
        val before=job.csv();assertEquals(0,job.step(8,NeverStop()));assertEquals(before,job.csv())
        assertTrue(job.csv().contains("WORK_LIMIT,,"))
    }
    @Test fun geometryBudgetIsEnforcedIndependently() {
        val job=FeatureGridJob(engine(),config(geometry=8));finish(job)
        assertEquals(BoundedState.WORK_LIMIT,job.state);assertEquals(8L,job.geometryUsed)
        assertTrue(job.samplesUsed<job.config.grid.sampleBudget);assertTrue(job.csv().contains("NOT_COMPUTED,,"))
    }
    @Test fun cancellationDiscardsInFlightCellButRetainsChargedWork() {
        val job=FeatureGridJob(engine(),config());var checks=0
        job.step(8,object:StopSignal{override fun isCancelled()=++checks>100})
        assertEquals(BoundedState.CANCELLED,job.state);assertEquals(0,job.completed)
        assertTrue(job.geometryUsed>0);assertEquals(0,job.step(8,NeverStop()))
        assertFalse(job.csv().contains("QUALIFIED"))
    }
    @Test fun supersededAndCancelledJobsCannotPublish() {
        val gate=FeaturePublicationGate();val old=gate.begin();val job=FeatureGridJob(engine(),config());finish(job)
        assertTrue(gate.publish(old,job));assertNotNull(gate.published)
        val current=gate.begin();assertFalse(gate.publish(old,job));assertNull(gate.published)
        val cancelled=FeatureGridJob(engine(),config());cancelled.step(1,object:StopSignal{override fun isCancelled()=true})
        assertFalse(gate.publish(current,cancelled));assertNull(gate.published)
        assertTrue(gate.publish(current,job))
    }
    @Test fun allStudyJobsHaveExplicitBoundedPreflight() {
        for(i in 0 until FeatureGridSuite.count()) {
            val c=FeatureGridSuite.config(i);val e=FeatureGridPreflight.estimate(c)
            assertTrue(e.accepted);assertTrue(e.minimumSamples<=c.grid.sampleBudget)
            assertTrue(e.minimumGeometry<=c.geometryBudget);assertTrue(e.maximumSamples>=e.minimumSamples)
            assertTrue(e.maximumGeometry>=e.minimumGeometry)
        }
    }
}
