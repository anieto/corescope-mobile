package study
import kotlin.test.*
import kotlin.math.abs

class FeatureTests {
    @Test fun ridgeVerticesSurviveEveryInterval() {
        for(step in listOf(30,10,5,2,1)) {
            val points=featurePositions(1000.0,step,doubleArrayOf(0.0,400.25,401.0,401.75,1000.0))
            assertTrue(points.toList().containsAll(listOf(400.25,401.0,401.75)))
            assertTrue(points.toList().zipWithNext().all{it.first<it.second})
        }
    }
    @Test fun formerWorstRidgeIsResolvedAccurately() {
        val result=FeatureSuite.analytic("ridge",1000.0,.75,1.0)
        assertEquals(AccuracyState.QUALIFIED,result.state)
        val ref=TerrainFixtures.loss("ridge",1000.0,.75,1.0,.125)
        assertTrue(abs(assertNotNull(result.lossDb)-ref)<=.25)
    }
    @Test fun sampleBudgetNeverPublishesPartialEstimate() {
        val result=FeatureSuite.analytic("ridge",1000.0,.75,1.0,10)
        assertEquals(AccuracyState.WORK_LIMIT,result.state);assertNull(result.lossDb);assertTrue(result.samples<=10)
    }
    @Test fun cancellationDoesNotPublish() {
        val result=FeatureSuite.analytic("ridge",1000.0,.75,1.0,250000,object:StopSignal{override fun isCancelled()=true})
        assertEquals(AccuracyState.CANCELLED,result.state);assertNull(result.lossDb);assertEquals(0,result.samples)
    }
    @Test fun missingTerrainIsNotFlatGround() {
        val r=featurePredict(1000.0,51.0,FeatureWork(250000,200000,NeverStop()),{doubleArrayOf(0.0,1000.0)}){null}
        assertEquals(AccuracyState.MISSING_TERRAIN,r.state);assertNull(r.lossDb)
    }
    @Test fun geometryLimitIsIndependentOfSampleLimit() {
        val engine=WideEngine(object:RasterReader{override fun value(index:Int)=100},20000,5000,52.0,-117.0,3600,50.96,-115.16)
        val r=FeatureSampler(engine).predict(1000.0,0.0,250000,1,NeverStop())
        assertEquals(AccuracyState.WORK_LIMIT,r.state);assertEquals(1,r.geometry);assertNull(r.lossDb)
    }
    @Test fun cancellationInterruptsFeatureDiscovery() {
        val engine=WideEngine(object:RasterReader{override fun value(index:Int)=100},20000,5000,52.0,-117.0,3600,50.96,-115.16)
        var checks=0
        val r=FeatureSampler(engine).predict(1000.0,0.0,250000,200000,object:StopSignal{override fun isCancelled()=++checks>100})
        assertEquals(AccuracyState.CANCELLED,r.state);assertNull(r.lossDb);assertTrue(r.geometry in 1..100)
    }
}
