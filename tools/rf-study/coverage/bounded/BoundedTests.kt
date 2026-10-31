package study

import kotlin.test.*

class BoundedTests {
    private fun source(width: Int) = object: TerrainRows {
        override fun read(start: Int,count: Int) = ShortArray(count) { ((start+it)%20000-10000).toShort() }
    }
    @Test fun cacheMatchesSignedSourceAcrossEdgesAndPartialTiles() {
        val source = source(259); val cache = BoundedRaster(source,259,257,32768,NeverStop())
        for(y in listOf(0,127,128,255,256)) for(x in listOf(0,127,128,255,258)) {
            val i = y*259+x
            assertEquals(source.read(i,1)!![0].toInt(),cache.value(i))
        }
        assertTrue(cache.evictions>0); assertTrue(cache.peakPayloadBytes<=32768)
        assertEquals(-32768,cache.value(-1)); assertEquals(-32768,cache.value(259*257))
        cache.clear(); assertEquals(0,cache.residentBytes)
    }
    @Test fun lruRetainsRecentlyUsedTile() {
        val cache = BoundedRaster(source(512),512,128,65536,NeverStop())
        cache.value(0); cache.value(128); cache.value(0); cache.value(256)
        assertEquals(3L,cache.misses); assertEquals(1L,cache.evictions)
        cache.value(0); assertEquals(3L,cache.misses)
        cache.value(128); assertEquals(4L,cache.misses)
    }
    @Test fun failedAndTruncatedLoadsAreNotCached() {
        var fail = true
        val cache = BoundedRaster(object: TerrainRows {
            override fun read(start:Int,count:Int):ShortArray? = if(fail) null else ShortArray(count){-7}
        },256,256,32768,NeverStop())
        assertEquals(-32768,cache.value(0)); assertEquals(0,cache.residentBytes)
        fail=false; assertEquals(-7,cache.value(0)); assertEquals(1L,cache.failedLoads)
        val truncated = BoundedRaster(object:TerrainRows {override fun read(start:Int,count:Int)=ShortArray(count-1)},256,256,32768,NeverStop())
        assertEquals(-32768,truncated.value(0)); assertEquals(0,truncated.residentBytes)
    }
    @Test fun nodataIsPreservedAndCancellationDiscardsPartialTile() {
        var reads = 0; var cancelled = false
        val stop=object:StopSignal{override fun isCancelled()=cancelled}
        val cache=BoundedRaster(object:TerrainRows {
            override fun read(start:Int,count:Int):ShortArray {reads++;if(reads==3)cancelled=true;return ShortArray(count){-32768}}
        },256,256,32768,stop)
        assertEquals(-32768,cache.value(0));assertEquals(3,reads);assertEquals(0,cache.residentBytes)
        val nodata=BoundedRaster(object:TerrainRows{override fun read(start:Int,count:Int)=ShortArray(count){-32768}},128,128,32768,NeverStop())
        assertEquals(-32768,nodata.value(0));assertEquals(32768,nodata.residentBytes)
    }
    @Test fun cacheRejectsImpossibleBudgetsAndDimensions() {
        assertFailsWith<IllegalArgumentException>{BoundedRaster(source(2),2,2,32767,NeverStop())}
        assertFailsWith<IllegalArgumentException>{BoundedRaster(source(2),Int.MAX_VALUE,2,32768,NeverStop())}
    }
    @Test fun preflightRejectsOversizedGridsAndImpossibleMinimumWork() {
        assertEquals("cell_budget",BoundedPreflight.estimate(BoundedConfig(100000,200,-100000,100000,-100000,100000,150000000,200000)).reason)
        assertEquals("minimum_work_budget",BoundedPreflight.estimate(BoundedConfig(20000,2000,-20000,20000,-20000,20000,1,200000)).reason)
        assertEquals("path_budget",BoundedPreflight.estimate(BoundedConfig(20000,2000,-20000,20000,-20000,20000,150000000,1)).reason)
        assertFalse(BoundedPreflight.estimate(BoundedConfig(100000,50,Int.MIN_VALUE,Int.MAX_VALUE,0,0,150000000,200000)).accepted)
    }
    @Test fun preflightBoundsEveryAcceptedScenario() {
        for(i in 0 until BoundedSuite.count()) {
            val c=BoundedSuite.config(i);val e=BoundedPreflight.estimate(c)
            assertTrue(e.accepted);assertTrue(e.minimumSampleWork<=c.sampleBudget)
            assertTrue(e.minimumSampleWork<=e.maximumSampleWork);assertTrue(e.cells<=60000)
        }
    }
    @Test fun cancelledJobCannotResumeOrPublish() {
        val engine=WideEngine(object:RasterReader{override fun value(index:Int)=100},20000,5000,52.0,-117.0,3600,50.96,-115.16)
        val job=BoundedJob(engine,BoundedSuite.config(0))
        val before=job.csv()
        job.step(8,object:StopSignal{override fun isCancelled()=true})
        assertEquals(BoundedState.CANCELLED,job.state);assertEquals(0,job.completed)
        assertEquals(0,job.step(8,NeverStop()));assertEquals(before,job.csv());assertEquals(0L,job.samplesUsed)
    }
}
