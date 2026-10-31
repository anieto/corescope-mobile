#!/usr/bin/env python3
"""Probe unmodified driver's missing-terrain, input and cancellation boundaries."""
import ctypes as c,json,sys,time
lib=c.CDLL(sys.argv[1])
lib.splat_create.argtypes=[c.c_double]*9+[c.c_int]*2+[c.c_double]*4+[c.c_int]
lib.splat_mask_ptr.restype=c.POINTER(c.c_uint8)
args=[51.5074,-0.1278,20.,4.92126,868.,0.1,15.,0.005,301.,6,1,0.5,0.9,0.,10.,1200]
h=lib.splat_create(*args);assert h>0
# No terrain loaded: this deliberately tests the upstream fallback, not real coverage.
t=time.perf_counter();n=lib.splat_radial_count(h)
for i in range(0,n,16):assert lib.splat_run_radials(h,i,16)>0
lib.splat_rasterize(h);info=(c.c_double*8)();lib.splat_region_info(h,info)
mask=c.string_at(lib.splat_mask_ptr(h),int(info[0]*info[1]))
results={'missing_terrain_computed_cells':sum(bool(k&248) for k in mask),'missing_terrain_seconds':time.perf_counter()-t}
lib.splat_destroy(h)
args[2]=-10.;h=lib.splat_create(*args)
results['negative_tx_height_accepted']=h>0
if h>0:lib.splat_destroy(h)
args[2]=20.;h=lib.splat_create(*args)
t=time.perf_counter();lib.splat_run_radials(h,0,16);chunk=time.perf_counter()-t
t=time.perf_counter();lib.splat_destroy(h);destroy=time.perf_counter()-t
results.update(partial_16_radial_seconds=chunk,destroy_after_partial_seconds=destroy,
 cancellation_note='Caller stops scheduling chunks then destroys; no mid-chunk cancellation API. Host, synthetic absent terrain only.')
print(json.dumps(results,indent=2))
