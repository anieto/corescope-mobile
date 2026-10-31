import dataclasses
import gzip
import hashlib
import http.server
import json
import os
from pathlib import Path
import random
import socket
import struct
import tempfile
import threading
import time
import unittest
from unittest import mock

from repository import CONTROL_RESERVE, DownloadBudget, Failure, META_RESERVE, Repository, State, TileSpec


class FixtureServer:
    def __init__(self):
        self.routes = {}
        self.hits = {}
        self.started = threading.Event()
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                owner.hits[self.path] = owner.hits.get(self.path, 0) + 1
                route = owner.routes.get(self.path, {'status': 404})
                status = route.get('status', 200)
                if route.get('fail_first') and owner.hits[self.path] == 1: status = 503
                self.send_response(status)
                payload = route.get('payload', b'') if status == 200 else b''
                if route.get('redirect'): self.send_header('Location', route['redirect'])
                if not route.get('omit_length'): self.send_header('Content-Length', str(route.get('length', len(payload))))
                for key, value in route.get('headers', {}).items(): self.send_header(key, value)
                self.end_headers()
                owner.started.set()
                if status != 200: return
                payload = payload[:route.get('cut', len(payload))]
                chunk = route.get('chunk', 65536)
                try:
                    for start in range(0, len(payload), chunk):
                        self.wfile.write(payload[start:start+chunk]); self.wfile.flush()
                        if route.get('delay'): time.sleep(route['delay'])
                except (BrokenPipeError, ConnectionResetError): pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()


class RepositoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.server = FixtureServer()
    @classmethod
    def tearDownClass(cls): cls.server.close()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='nodescope-repository-test-')
        self.root = Path(self.tmp.name) / 'cache'
        self.server.routes.clear(); self.server.hits.clear(); self.server.started.clear()
        self.raw = struct.pack('>25h', -32768, -23, 0, *range(22))
        self.packed = gzip.compress(self.raw, mtime=0)
        self.server.routes['/a'] = {'payload': self.packed}
        self.spec = self.make_spec('/a')
        self.repo = Repository(self.root, 1_000_000, timeout=.2)

    def tearDown(self):
        self.repo.close()
        self.tmp.cleanup()

    def make_spec(self, path, payload=None, **changes):
        payload = self.packed if payload is None else payload
        return TileSpec('fixture-dem', 'r1', 'N50W115', hashlib.sha256(payload).hexdigest(), len(payload), 5,
                        self.server.base + path, 'test datum', 'Synthetic test data', **changes)

    def failure(self, state, spec=None, budget=None, **kwargs):
        with self.assertRaises(Failure) as failure:
            self.repo.acquire(spec or self.spec, budget or DownloadBudget(1_000_000), **kwargs)
        self.assertEqual(state, failure.exception.state)
        self.assertFalse(list(self.root.glob('.stage-*')))
        self.assertEqual(0, self.repo.reserved)

    def test_signed_heights_nodata_and_provenance_are_preserved(self):
        with self.repo.acquire(self.spec, DownloadBudget(1000)) as tile:
            self.assertEqual(self.raw, tile.path.read_bytes())
            self.assertEqual((-32768, -23, 0), struct.unpack('>3h', tile.path.read_bytes()[:6]))
            self.assertFalse(tile.from_cache)
            self.assertEqual('none', tile.metadata['resampling'])
            self.assertEqual(-32768, tile.metadata['nodata'])
            self.assertEqual(self.spec.identity, tile.metadata['identity'])
            self.assertTrue(tile.metadata['fetched_utc'])

    def test_offline_cache_hit_does_not_contact_server(self):
        with self.repo.acquire(self.spec, DownloadBudget(1000)): pass
        with self.repo.acquire(self.spec, DownloadBudget(0), offline=True) as tile: self.assertTrue(tile.from_cache)
        self.assertEqual(1, self.server.hits['/a'])

    def test_offline_miss_does_not_contact_server(self):
        self.failure(State.OFFLINE_MISS, offline=True)
        self.assertFalse(self.server.hits)

    def test_404_is_missing_terrain_not_ocean(self):
        self.failure(State.MISSING_TILE, dataclasses.replace(self.spec, url=self.server.base+'/missing'))
        self.assertFalse(self.repo.entries)

    def test_transient_failure_has_bounded_retry(self):
        self.server.routes['/a']['fail_first'] = True
        with self.repo.acquire(self.spec, DownloadBudget(1000)): pass
        self.assertEqual(2, self.server.hits['/a'])

    def test_persistent_server_failure_stops_after_attempt_limit(self):
        self.server.routes['/a']['status'] = 503
        self.failure(State.NETWORK_ERROR)
        self.assertEqual(2, self.server.hits['/a'])

    def test_redirect_is_not_followed(self):
        self.server.routes['/a'] = {'status': 302, 'redirect': self.server.base+'/other'}
        self.failure(State.NETWORK_ERROR)
        self.assertNotIn('/other', self.server.hits)

    def test_wrong_content_length_is_rejected(self):
        self.server.routes['/a']['length'] = len(self.packed)+1
        self.failure(State.CORRUPT)

    def test_missing_content_length_is_streamed_with_byte_cap(self):
        self.server.routes['/a']['omit_length'] = True
        with self.repo.acquire(self.spec, DownloadBudget(1000)): pass

    def test_chunked_transfer_is_not_required_for_lengthless_body(self):
        self.server.routes['/a'].update(omit_length=True, chunk=1)
        with self.repo.acquire(self.spec, DownloadBudget(1000)) as tile: self.assertEqual(self.raw, tile.path.read_bytes())

    def test_oversized_unknown_length_response_is_rejected(self):
        self.server.routes['/a'].update(payload=self.packed+b'x'*1000, omit_length=True)
        budget=DownloadBudget(1000)
        self.failure(State.CORRUPT, budget=budget)
        self.assertEqual(len(self.packed)+1, budget.received)

    def test_truncated_response_is_rejected(self):
        self.server.routes['/a']['cut'] = len(self.packed)//2
        self.failure(State.CORRUPT)

    def test_changed_compressed_digest_is_rejected(self):
        self.failure(State.CORRUPT, dataclasses.replace(self.spec, compressed_sha256='0'*64))

    def test_corrupt_gzip_with_matching_pin_is_rejected(self):
        payload=self.packed[:-8]+b'\x00'*8
        self.server.routes['/a']['payload']=payload
        self.failure(State.CORRUPT, self.make_spec('/a',payload))

    def test_bounded_decompression_rejects_bomb(self):
        payload=gzip.compress(b'\x00'*2_000_000,mtime=0)
        self.server.routes['/a']['payload']=payload
        self.failure(State.CORRUPT,self.make_spec('/a',payload))
        self.assertLessEqual(self.repo.peak_reserved,self.repo.quota)

    def test_wrong_decoded_dimensions_are_rejected(self):
        payload=gzip.compress(self.raw[:-2],mtime=0)
        self.server.routes['/a']['payload']=payload
        self.failure(State.CORRUPT,self.make_spec('/a',payload))

    def test_http_content_encoding_cannot_silently_change_pinned_bytes(self):
        self.server.routes['/a']['headers']={'Content-Encoding':'gzip'}
        self.failure(State.CORRUPT)

    def test_known_insufficient_download_budget_is_rejected_before_network(self):
        budget=DownloadBudget(10)
        self.failure(State.DOWNLOAD_LIMIT,budget=budget)
        self.assertEqual(0,budget.received);self.assertFalse(self.server.hits)

    def test_download_budget_is_shared_across_tile_requests(self):
        budget=DownloadBudget(len(self.packed)+10)
        with self.repo.acquire(self.spec,budget): pass
        second=dataclasses.replace(self.spec,tile='N50W116')
        self.failure(State.DOWNLOAD_LIMIT,second,budget=budget)
        self.assertEqual(len(self.packed),budget.received)

    def test_precancelled_request_does_no_io(self):
        cancel=threading.Event();cancel.set()
        self.failure(State.CANCELLED,cancel=cancel)
        self.assertFalse(self.server.hits)

    def test_cancellation_interrupts_streaming_body(self):
        raw=random.Random(7).randbytes(100_000);payload=gzip.compress(raw,mtime=0)
        self.server.routes['/a'].update(payload=payload,chunk=100,delay=.01)
        spec=self.make_spec('/a',payload)
        cancel=threading.Event()
        def stop(): self.server.started.wait(1);time.sleep(.03);cancel.set()
        thread=threading.Thread(target=stop);thread.start();start=time.monotonic()
        self.failure(State.CANCELLED,spec,cancel=cancel);thread.join()
        self.assertLess(time.monotonic()-start,1)

    def test_streaming_deadline_stops_drip_response(self):
        self.repo.attempt_seconds=.05
        self.server.routes['/a'].update(chunk=1,delay=.02)
        self.failure(State.TIMEOUT)

    def test_quota_rejects_staging_before_network(self):
        self.repo.quota=META_RESERVE
        self.failure(State.QUOTA)
        self.assertFalse(self.server.hits)

    def test_pinned_tiles_cannot_be_evicted(self):
        self.repo.quota=CONTROL_RESERVE+self.spec.decoded_bytes+META_RESERVE+self.spec.packed_bytes
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as first:
            self.failure(State.QUOTA,dataclasses.replace(self.spec,tile='N50W116'))
            self.assertEqual(self.raw,first.path.read_bytes())
        with self.repo.acquire(dataclasses.replace(self.spec,tile='N50W116'),DownloadBudget(1000)): pass
        self.assertEqual(1,self.repo.evictions)

    def test_lru_evicts_least_recent_unpinned_tile(self):
        self.repo.quota=CONTROL_RESERVE+2*(self.spec.decoded_bytes+META_RESERVE)+self.spec.packed_bytes
        b=dataclasses.replace(self.spec,tile='N50W116');c=dataclasses.replace(self.spec,tile='N50W117')
        for spec in [self.spec,b,self.spec,c]:
            with self.repo.acquire(spec,DownloadBudget(1000)): pass
        self.assertIn(self.spec.key,self.repo.entries);self.assertIn(c.key,self.repo.entries);self.assertNotIn(b.key,self.repo.entries)

    def test_clear_only_removes_unpinned_terrain(self):
        b=dataclasses.replace(self.spec,tile='N50W116')
        with self.repo.acquire(b,DownloadBudget(1000)): pass
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as active:
            self.repo.clear_unpinned();self.assertEqual([self.spec.key],list(self.repo.entries));self.assertTrue(active.path.exists())
        self.repo.clear_unpinned();self.assertFalse(self.repo.entries)

    def test_cached_corruption_is_not_used_offline(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: path=tile.path
        path.write_bytes(b'x'*len(self.raw))
        self.failure(State.CORRUPT,offline=True)
        self.assertFalse(self.repo.entries)

    def test_cached_corruption_is_refetched_online(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: path=tile.path
        path.write_bytes(b'x'*len(self.raw))
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: self.assertEqual(self.raw,tile.path.read_bytes())
        self.assertEqual(2,self.server.hits['/a'])

    def test_deleted_cached_file_is_refetched(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: path=tile.path
        path.unlink()
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: self.assertEqual(self.raw,tile.path.read_bytes())

    def test_purged_cached_directory_is_refetched(self):
        import shutil
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: directory=tile.path.parent
        shutil.rmtree(directory)
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile:self.assertEqual(self.raw,tile.path.read_bytes())

    def test_rate_limit_is_not_immediately_retried(self):
        self.server.routes['/a']['status']=429
        self.failure(State.NETWORK_ERROR)
        self.assertEqual(1,self.server.hits['/a'])

    def test_revision_resolution_datum_and_content_are_cache_identity(self):
        keys={self.spec.key,dataclasses.replace(self.spec,revision='r2').key,dataclasses.replace(self.spec,side=3).key,
              dataclasses.replace(self.spec,vertical_datum='different').key,dataclasses.replace(self.spec,compressed_sha256='0'*64).key}
        self.assertEqual(5,len(keys))
        self.assertEqual(self.spec.key,dataclasses.replace(self.spec,url=self.server.base+'/mirror').key)

    def test_cache_key_has_stable_integer_and_utf8_encoding(self):
        spec=TileSpec('fixture-dem','r1','N50W115','0'*64,123,5,'https://example.com/tile','EGM96','Terrain – Café')
        self.assertIs(type(spec.identity['resolution_arcseconds']),int)
        self.assertEqual('26312d9bcbb59806578ac28bb1e8734f79cb0c6558c629f24cc19c9bdf7ba3a7',spec.key)

    def test_cache_restart_preserves_valid_content_and_fetch_time(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: fetched=tile.metadata['fetched_utc']
        self.repo.close();self.repo=Repository(self.root,1_000_000)
        with self.repo.acquire(self.spec,DownloadBudget(0),offline=True) as tile:self.assertEqual(fetched,tile.metadata['fetched_utc'])

    def test_restart_removes_interrupted_stages_and_corrupt_manifest(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as tile: directory=tile.path.parent
        self.repo.close();stage=self.root/'.stage-interrupted';stage.mkdir();(stage/'partial').write_bytes(b'partial')
        (directory/'metadata.json').write_text('{broken')
        self.repo=Repository(self.root,1_000_000)
        self.assertFalse(stage.exists());self.assertFalse(self.repo.entries)

    def test_second_process_owner_is_rejected(self):
        with self.assertRaises(Failure) as caught:Repository(self.root,1_000_000)
        self.assertEqual(State.BUSY,caught.exception.state)

    def test_atomic_commit_failure_cleans_staging(self):
        original=os.replace
        def fail_commit(source,target):
            if Path(source).name.startswith('.stage-'):raise OSError('injected rename failure')
            return original(source,target)
        with mock.patch('repository.os.replace',side_effect=fail_commit):self.failure(State.IO_ERROR)
        self.assertFalse(self.repo.entries)

    def test_staging_creation_failure_releases_reservation(self):
        original=Path.mkdir
        def fail_stage(path,*args,**kwargs):
            if path.name.startswith('.stage-'):raise OSError('injected disk-full failure')
            return original(path,*args,**kwargs)
        with mock.patch('pathlib.Path.mkdir',new=fail_stage):self.failure(State.IO_ERROR)

    def test_metadata_write_failure_does_not_publish_tile(self):
        with mock.patch.object(self.repo,'_metadata',side_effect=OSError('injected write failure')):self.failure(State.IO_ERROR)
        self.assertFalse(self.repo.entries)

    def test_cancellation_before_commit_does_not_publish_tile(self):
        cancel=threading.Event();original=self.repo._metadata
        def cancel_after_metadata(*args):original(*args);cancel.set()
        with mock.patch.object(self.repo,'_metadata',side_effect=cancel_after_metadata):self.failure(State.CANCELLED,cancel=cancel)
        self.assertFalse(self.repo.entries)

    def test_pinned_corrupt_tile_is_not_deleted_under_reader(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)) as lease:
            lease.path.write_bytes(b'x'*len(self.raw))
            self.failure(State.CORRUPT,offline=True)
            self.assertTrue(lease.path.exists())

    def test_lease_release_is_idempotent(self):
        lease=self.repo.acquire(self.spec,DownloadBudget(1000));lease.close();lease.close()
        self.assertEqual(0,self.repo.pins[self.spec.key])

    def test_cannot_close_cache_while_tile_is_in_use(self):
        with self.repo.acquire(self.spec,DownloadBudget(1000)):
            with self.assertRaises(Failure) as caught:self.repo.close()
            self.assertEqual(State.BUSY,caught.exception.state)

    def test_plan_deduplicates_and_discloses_retry_bound(self):
        plan=self.repo.plan([self.spec,self.spec])
        self.assertEqual(1,plan['tiles']);self.assertEqual(self.spec.packed_bytes,plan['expected_download_bytes'])
        self.assertEqual((self.spec.packed_bytes+1)*2,plan['maximum_attempt_bytes'])
        with self.repo.acquire(self.spec,DownloadBudget(1000)): pass
        self.assertEqual(0,self.repo.plan([self.spec])['expected_download_bytes'])

    def test_pinned_set_quota_is_rejected_before_any_download(self):
        self.repo.quota=CONTROL_RESERVE+self.spec.decoded_bytes+META_RESERVE+self.spec.packed_bytes
        specs=[self.spec,dataclasses.replace(self.spec,tile='N50W116')]
        self.assertFalse(self.repo.plan(specs)['can_prepare_pinned_set'])
        with self.assertRaises(Failure) as error:self.repo.acquire_many(specs,DownloadBudget(1000))
        self.assertEqual(State.QUOTA,error.exception.state);self.assertFalse(self.server.hits)

    def test_batch_deduplicates_and_pins_complete_set(self):
        b=dataclasses.replace(self.spec,tile='N50W116')
        leases=self.repo.acquire_many([self.spec,b,self.spec],DownloadBudget(1000))
        try:
            self.assertEqual(2,len(leases));self.repo.clear_unpinned()
            self.assertEqual(2,len(self.repo.entries));self.assertTrue(all(self.repo.pins[x.key]==1 for x in leases))
        finally:
            for lease in leases:lease.close()

    def test_partial_batch_failure_releases_pins_without_discarding_valid_cache(self):
        missing=dataclasses.replace(self.spec,tile='N50W116',url=self.server.base+'/missing')
        with self.assertRaises(Failure) as error:self.repo.acquire_many([self.spec,missing],DownloadBudget(1000))
        self.assertEqual(State.MISSING_TILE,error.exception.state)
        self.assertIn(self.spec.key,self.repo.entries);self.assertFalse(any(self.repo.pins.values()))

    def test_batch_offline_miss_does_not_start_network(self):
        with self.assertRaises(Failure) as error:self.repo.acquire_many([self.spec],DownloadBudget(1000),offline=True)
        self.assertEqual(State.OFFLINE_MISS,error.exception.state);self.assertFalse(self.server.hits)

    def test_unowned_cache_directory_is_not_deleted(self):
        root=Path(self.tmp.name)/'unrelated';root.mkdir();file=root/'keep';file.write_text('keep')
        with self.assertRaises(Failure) as caught:Repository(root,1_000_000)
        self.assertEqual(State.INVALID_CACHE,caught.exception.state);self.assertEqual('keep',file.read_text())

    def test_invalid_specs_cannot_create_paths_or_cleartext_remote_requests(self):
        for kwargs in [{'tile':'../../oops'},{'tile':'N90W115'},{'side':1},{'side':3.5},{'url':'http://example.com/tile'},{'compressed_sha256':'bad'}]:
            with self.assertRaises(ValueError):dataclasses.replace(self.spec,**kwargs)


if __name__ == '__main__':
    unittest.main()
