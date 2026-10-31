#!/usr/bin/env python3
"""Read-only upstream probe. Build libstudy.dylib/.so from the pinned upstream first.
Usage: python3 site_planner_probe.py CHECKOUT LIBRARY OUTPUT_JSON [CASE]
No upstream code or terrain is vendored into NodeScope.
"""
import array
import ctypes as c
import gzip
import hashlib
import json
from pathlib import Path
import resource
import subprocess
import sys
import time

PIN = '481e5a482f10fc6956a98aed39aacdb59c3fb6ec'
root, library, output = map(Path, sys.argv[1:4])
assert subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() == PIN
assert subprocess.check_output(['git', '-C', str(root/'splat'), 'rev-parse', 'HEAD'], text=True).strip() == '08f06f56470a8361a5609503a6b1cb02f07f48dc'
lib = c.CDLL(str(library.resolve()))
lib.splat_create.argtypes = [c.c_double] * 9 + [c.c_int] * 2 + [c.c_double] * 4 + [c.c_int]
lib.splat_create.restype = c.c_int
for name in ['splat_page_count', 'splat_radial_count', 'splat_rasterize']:
    getattr(lib, name).argtypes = [c.c_int]
lib.splat_page_info.argtypes = [c.c_int, c.c_int, c.POINTER(c.c_int32)]
lib.splat_load_page.argtypes = [c.c_int, c.c_int, c.POINTER(c.c_int16)]
lib.splat_run_radials.argtypes = [c.c_int, c.c_int, c.c_int]
lib.splat_region_info.argtypes = [c.c_int, c.POINTER(c.c_double)]
lib.splat_errnum_counts.argtypes = [c.c_int, c.POINTER(c.c_int32)]
lib.splat_destroy.argtypes = [c.c_int]
for name in ['splat_signal_ptr', 'splat_mask_ptr']:
    getattr(lib, name).argtypes = [c.c_int]
    getattr(lib, name).restype = c.POINTER(c.c_uint8)
climates = dict(zip(['equatorial','continental_subtropical','maritime_subtropical','desert','continental_temperate','maritime_temperate_land','maritime_temperate_sea'], range(1,8)))
# Unpack only supplied fixture terrain; preserve upstream SDF ordering and signed elevations.
pages = {}
for file in sorted((root/'test/fixtures/terrain').glob('*.sdf.gz')):
    with gzip.open(file, 'rt') as stream:
        header = [int(stream.readline()) for _ in range(4)]
        data = array.array('h', map(int, stream))
    assert len(data) == 1200 * 1200
    pages[(header[1], (header[0]-1) % 360)] = data
results = []
for file in sorted((root/'test/fixtures/cases').glob('*.json')):
    if len(sys.argv) > 4 and file.stem != sys.argv[4]:
        continue
    case = json.loads(file.read_text())
    erp = float(f"{10 ** ((case['tx_power'] + case['tx_gain'] - case['system_loss'] - 30) / 10):.2f}")
    # Deliberately reproduce upstream legacy golden conversions, NOT production defaults.
    h = lib.splat_create(case['lat'], case['lon'], case['tx_height'], case['rx_height']/0.3048,
        case['frequency_mhz'], erp, case['ground_dielectric'], case['ground_conductivity'],
        case['atmosphere_bending'], climates[case['radio_climate']], int(case['polarization']=='vertical'),
        round(case['situation_fraction']/100,2), round(case['time_fraction']/100,2),
        case['clutter_height'], min(case['radius'],100000)/1000,1200)
    assert h > 0
    missing = []
    for i in range(lib.splat_page_count(h)):
        info = (c.c_int32 * 2)()
        assert lib.splat_page_info(h,i,info) >= 0
        key = tuple(info)
        if key not in pages:
            missing.append(key)
            continue
        buffer = (c.c_int16 * len(pages[key])).from_buffer(pages[key])
        assert lib.splat_load_page(h,i,buffer) >= 0
    durations = []
    radial_count = lib.splat_radial_count(h)
    start = time.perf_counter()
    for index in range(0,radial_count,16):
        t = time.perf_counter()
        assert lib.splat_run_radials(h,index,16) >= 0
        durations.append(time.perf_counter()-t)
    seconds = time.perf_counter()-start
    assert lib.splat_rasterize(h) >= 0
    region = (c.c_double * 8)()
    lib.splat_region_info(h,region)
    count = int(region[0]*region[1])
    signal = c.string_at(lib.splat_signal_ptr(h),count)
    mask = c.string_at(lib.splat_mask_ptr(h),count)
    gold = root/'test/fixtures/golden-engine'/file.stem
    gs = gzip.decompress(Path(str(gold)+'.signal.u8.gz').read_bytes())
    gm = gzip.decompress(Path(str(gold)+'.mask.u8.gz').read_bytes())
    assert len(gs)==count and len(gm)==count
    error = (c.c_int32 * 6)()
    lib.splat_errnum_counts(h,error)
    result = dict(case=file.stem, compute_seconds=seconds, max_16_radial_seconds=max(durations),
        region=list(region), missing_fixture_pages=missing, itm_error_counts=list(error),
        signal_sha256=hashlib.sha256(signal).hexdigest(), mask_sha256=hashlib.sha256(mask).hexdigest(),
        signal_byte_mismatches=sum(a!=b for a,b in zip(signal,gs)),
        mask_byte_mismatches=sum(a!=b for a,b in zip(mask,gm)),
        process_peak_rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    results.append(result)
    lib.splat_destroy(h)
    print(json.dumps(result), flush=True)
    output.write_text(json.dumps(dict(upstream=PIN, host=sys.platform, results=results),indent=2)+'\n')
