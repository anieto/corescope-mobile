#!/usr/bin/env python3
"""Replay pinned HGT files over loopback; no requests to an external terrain service."""
import argparse
import dataclasses
import gzip
import hashlib
import http.server
import json
from pathlib import Path
import random
import struct
import tempfile
import threading
import time

from repository import DownloadBudget, Failure, Repository, State, TileSpec

p=argparse.ArgumentParser();p.add_argument('terrain',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
manifest=json.loads((a.terrain/'manifest.json').read_text())
pins=json.loads((Path(__file__).resolve().parent.parent/'wide/terrain-pins.json').read_text())
files={('/'+r['tile']+'.hgt.gz'):a.terrain/(r['tile']+'.hgt.gz') for r in manifest['tiles']}
for r in manifest['tiles']:
    payload=files['/'+r['tile']+'.hgt.gz'].read_bytes()
    assert len(payload)==r['compressed_bytes'] and hashlib.sha256(payload).hexdigest()==pins[r['tile']]
hits={};slow=set();first_chunk=threading.Event()
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        hits[self.path]=hits.get(self.path,0)+1
        path=files.get(self.path)
        if path is None:self.send_error(404);return
        self.send_response(200);self.send_header('Content-Length',str(path.stat().st_size));self.end_headers()
        try:
            with path.open('rb') as f:
                while data:=f.read(65536):
                    self.wfile.write(data);self.wfile.flush();first_chunk.set()
                    if self.path in slow:time.sleep(.01)
        except (BrokenPipeError,ConnectionResetError):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
base='http://127.0.0.1:'+str(server.server_port)
specs=[TileSpec('mapzen-skadi','pinned-2026-10-30',r['tile'],pins[r['tile']],r['compressed_bytes'],3601,
               base+'/'+r['tile']+'.hgt.gz',manifest['datum'],manifest['attribution'],origin_url=r['url']) for r in manifest['tiles']]
by_name={s.tile:s for s in specs}
summary={'scope':'host-side repository contract; loopback replay of pinned files, not mobile network qualification',
         'quota_bytes':75_000_000,'network_limit_bytes':250_000_000,'tiles':[]}
try:
    with tempfile.TemporaryDirectory(prefix='nodescope-real-terrain-repository-') as temporary:
        root=Path(temporary)/'cache';budget=DownloadBudget(summary['network_limit_bytes'])
        with Repository(root,summary['quota_bytes'],timeout=1) as repo:
            summary['plan']=repo.plan(specs)
            try:repo.acquire_many(specs,budget)
            except Failure as error:assert error.state==State.QUOTA
            else:raise AssertionError('All eight pinned tiles exceed the study quota')
            assert not hits
            summary['oversized_pinned_set_rejected_before_network']=True
            first=repo.acquire(by_name['N50W114'],budget);second=repo.acquire(by_name['N51W114'],budget)
            try:
                try:repo.acquire(by_name['N50W115'],budget)
                except Failure as error:assert error.state==State.QUOTA
                else:raise AssertionError('Pinned tiles were not protected')
                assert first.path.exists() and second.path.exists()
                summary['pinned_quota_rejection']=True
            finally:first.close();second.close()
            for spec in specs:
                start=time.perf_counter()
                with repo.acquire(spec,budget) as lease:
                    # Separate reference decode in the study harness, not in the bounded repository.
                    raw=gzip.decompress(files['/'+spec.tile+'.hgt.gz'].read_bytes())
                    assert len(raw)==spec.decoded_bytes
                    digest=hashlib.sha256(raw).hexdigest();assert digest==lease.metadata['decoded_sha256']
                    rng=random.Random(spec.tile);indices={0,3600,3601,len(raw)//2-1}
                    indices.update(rng.randrange(len(raw)//2) for _ in range(100))
                    with lease.path.open('rb') as f:
                        for index in indices:
                            f.seek(index*2);assert f.read(2)==raw[index*2:index*2+2]
                    summary['tiles'].append({'tile':spec.tile,'cache_key':spec.key,'compressed_sha256':spec.compressed_sha256,
                        'decoded_sha256':digest,'decoded_bytes':len(raw),'samples_checked':len(indices),
                        'fetch_decode_verify_ms':(time.perf_counter()-start)*1000,'metadata':lease.metadata})
                assert repo.used<=repo.quota and repo.peak_reserved<=repo.quota
            summary['evictions']=repo.evictions
            summary['peak_reserved_bytes']=repo.peak_reserved
            cached=specs[-1];calls_before=sum(hits.values())
        # Restart and offline read must use validated committed data with preserved provenance.
        with Repository(root,summary['quota_bytes'],timeout=1) as repo:
            with repo.acquire(cached,DownloadBudget(0),offline=True) as lease:
                assert lease.from_cache
                summary['offline_restart_verified']=True
            assert sum(hits.values())==calls_before
            target=by_name['N50W116'];repo.clear_unpinned()
            route='/'+target.tile+'.hgt.gz';slow.add(route);first_chunk.clear();cancel=threading.Event()
            def stop():first_chunk.wait(2);time.sleep(.025);cancel.set()
            worker=threading.Thread(target=stop);worker.start();before=budget.received;start=time.perf_counter()
            try:repo.acquire(target,budget,cancel)
            except Failure as error:assert error.state==State.CANCELLED
            else:raise AssertionError('Expected cancellation')
            worker.join()
            summary['cancelled_payload_bytes']=budget.received-before
            summary['cancelled_request_total_ms']=(time.perf_counter()-start)*1000
            assert 0<summary['cancelled_payload_bytes']<target.packed_bytes
            assert not repo.entries and not list(root.glob('.stage-*')) and repo.reserved==0
            summary['partial_cleanup_verified']=True
            summary['peak_reserved_bytes']=max(summary['peak_reserved_bytes'],repo.peak_reserved)
        summary['received_payload_bytes']=budget.received
        summary['requests']=hits
        assert budget.received<=budget.limit
        summary['passed']=True
finally:
    server.shutdown();server.server_close();thread.join()
(a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k not in ['tiles','requests']},indent=2))
