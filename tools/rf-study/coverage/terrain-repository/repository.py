"""Host-side executable terrain repository contract. Standard library; one worker/process.

This is not a mobile adapter. Quotas bound owned file payload/reservations, not filesystem blocks.
"""
from __future__ import annotations

import dataclasses
import datetime
import enum
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

CHUNK = 65536
META_RESERVE = 8192  # Two metadata copies during atomic replacement, <=4096 bytes each.
CONTROL_RESERVE = 4096
DECODER = 'signed-be-hgt-v1'


class State(str, enum.Enum):
    CANCELLED = 'CANCELLED'
    OFFLINE_MISS = 'OFFLINE_MISS'
    MISSING_TILE = 'MISSING_TILE'
    NETWORK_ERROR = 'NETWORK_ERROR'
    TIMEOUT = 'TIMEOUT'
    CORRUPT = 'CORRUPT'
    DOWNLOAD_LIMIT = 'DOWNLOAD_LIMIT'
    QUOTA = 'QUOTA'
    IO_ERROR = 'IO_ERROR'
    BUSY = 'BUSY'
    INVALID_CACHE = 'INVALID_CACHE'


class Failure(Exception):
    def __init__(self, state: State):
        self.state = state
        super().__init__(state.value)


def check_cancel(cancel: threading.Event):
    if cancel.is_set():
        raise Failure(State.CANCELLED)


@dataclasses.dataclass(frozen=True)
class TileSpec:
    dataset: str
    revision: str
    tile: str
    compressed_sha256: str
    packed_bytes: int
    side: int
    url: str
    vertical_datum: str
    attribution: str
    source_type: str = 'not independently resolved'
    origin_url: str = ''

    def __post_init__(self):
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,48}', self.dataset): raise ValueError('dataset')
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,48}', self.revision): raise ValueError('revision')
        if not re.fullmatch(r'[NS]\d{2}[EW]\d{3}', self.tile): raise ValueError('tile')
        lat = int(self.tile[1:3]) * (1 if self.tile[0] == 'N' else -1)
        lon = int(self.tile[4:7]) * (1 if self.tile[3] == 'E' else -1)
        if not (-90 <= lat < 90 and -180 <= lon < 180): raise ValueError('tile bounds')
        if not re.fullmatch(r'[a-f0-9]{64}', self.compressed_sha256): raise ValueError('digest')
        if type(self.packed_bytes) is not int or type(self.side) is not int: raise ValueError('dimensions')
        if not (1 <= self.packed_bytes <= 40_000_000 and 2 <= self.side <= 3601): raise ValueError('dimensions')
        if 3600 % (self.side - 1) != 0: raise ValueError('unsupported angular resolution')
        parsed = urllib.parse.urlsplit(self.url)
        if not (parsed.scheme == 'https' or (parsed.scheme == 'http' and parsed.hostname in ['127.0.0.1', 'localhost'])): raise ValueError('transport')
        if not parsed.hostname or parsed.username or parsed.password or parsed.fragment: raise ValueError('URL')
        if not all(isinstance(x, str) and 0 < len(x) <= 1024 and '\n' not in x for x in [self.url, self.vertical_datum, self.attribution, self.source_type]): raise ValueError('provenance')
        if self.origin_url and (len(self.origin_url)>1024 or urllib.parse.urlsplit(self.origin_url).scheme!='https'): raise ValueError('source origin')

    @property
    def decoded_bytes(self):
        return self.side * self.side * 2

    @property
    def identity(self):
        lat = int(self.tile[1:3]) * (1 if self.tile[0] == 'N' else -1)
        lon = int(self.tile[4:7]) * (1 if self.tile[3] == 'E' else -1)
        return {k: v for k, v in dataclasses.asdict(self).items() if k not in ['url', 'origin_url']} | {
            'decoder': DECODER, 'resolution_arcseconds': 3600 // (self.side - 1), 'nodata': -32768,
            'footprint': {'south':lat,'north':lat+1,'west':lon,'east':lon+1}}

    @property
    def key(self):
        return hashlib.sha256(json.dumps(self.identity, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()


@dataclasses.dataclass
class DownloadBudget:
    limit: int
    received: int = 0

    def __post_init__(self):
        if type(self.limit) is not int or type(self.received) is not int or not (0 <= self.received <= self.limit <= 512_000_000): raise ValueError('download budget')

    @property
    def remaining(self):
        return self.limit - self.received


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Lease:
    def __init__(self, owner: Repository, key: str, metadata: dict, from_cache: bool):
        self.owner = owner
        self.key = key
        self.metadata = metadata
        self.from_cache = from_cache
        self.path = owner.root / key / 'terrain.hgt'
        self.closed = False

    def close(self):
        with self.owner.mutex:
            if not self.closed:
                self.owner.pins[self.key] -= 1
                self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class Repository:
    def __init__(self, root: Path, quota: int, timeout: float = 2, attempts: int = 2, attempt_seconds: float = 30):
        if type(quota) is not int or type(attempts) is not int: raise ValueError('repository limits')
        if not (8192 <= quota <= 2_000_000_000 and .05 <= timeout <= 30 and 1 <= attempts <= 3 and .05 <= attempt_seconds <= 120): raise ValueError('repository limits')
        self.root, self.quota, self.timeout, self.attempts = root, quota, timeout, attempts
        self.attempt_seconds = attempt_seconds
        self.mutex = threading.RLock()
        self.entries: dict[str, dict] = {}
        self.pins: dict[str, int] = {}
        self.sequence = 0
        self.reserved = 0
        self.peak_reserved = 0
        self.evictions = 0
        self.closed = False
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
        root.mkdir(parents=True, exist_ok=True)
        marker = root / '.terrain-repository-v1'
        if root.is_symlink() or (marker.exists() and marker.read_text() != DECODER):
            raise Failure(State.INVALID_CACHE)
        if not marker.exists():
            if any(root.iterdir()):
                raise Failure(State.INVALID_CACHE)
            marker.write_text(DECODER)
        self.lock_file = (root / '.lock').open('a+b')
        try:
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock_file.close()
            raise Failure(State.BUSY)
        try:
            self._recover()
            self._room(0)
            self.peak_reserved = self.used
        except BaseException:
            self.close()
            raise

    @property
    def used(self):
        return CONTROL_RESERVE + sum(m['spec']['side'] ** 2 * 2 + META_RESERVE for m in self.entries.values())

    def _remove(self, key):
        assert not self.pins.get(key, 0)
        path = self.root / key
        if path.is_symlink() or path.is_file():
            path.unlink()
        elif path.exists():
            shutil.rmtree(path)
        self.entries.pop(key, None)
        self.pins.pop(key, None)

    def _recover(self):
        children = list(self.root.iterdir())
        if len(children) > 4096:
            raise Failure(State.INVALID_CACHE)
        for path in children:
            if path.name.startswith('.stage-'):
                if path.is_symlink() or path.is_file(): path.unlink()
                else: shutil.rmtree(path)
            elif re.fullmatch(r'[a-f0-9]{64}', path.name):
                try:
                    if path.is_symlink() or not path.is_dir(): raise ValueError()
                    if any(p.name not in ['terrain.hgt','metadata.json','metadata.part'] for p in path.iterdir()): raise ValueError()
                    meta_path = path / 'metadata.json'
                    terrain = path / 'terrain.hgt'
                    if meta_path.is_symlink() or terrain.is_symlink() or meta_path.stat().st_size > 4096: raise ValueError()
                    metadata = json.loads(meta_path.read_text())
                    spec = TileSpec(**metadata['spec'])
                    if metadata['schema'] != 1 or metadata['identity'] != spec.identity or metadata['decoder'] != DECODER or spec.key != path.name or terrain.stat().st_size != spec.decoded_bytes: raise ValueError()
                    if not re.fullmatch(r'[a-f0-9]{64}', metadata['decoded_sha256']): raise ValueError()
                    if not isinstance(metadata['access_sequence'], int) or metadata['access_sequence'] < 0: raise ValueError()
                    self.sequence = max(self.sequence, metadata['access_sequence'])
                    # Remove interrupted metadata replacements; never use them as committed metadata.
                    (path / 'metadata.part').unlink(missing_ok=True)
                    self.entries[path.name] = metadata
                    self.pins[path.name] = 0
                except (OSError, ValueError, TypeError, KeyError, AssertionError):
                    self._remove(path.name)

    def _room(self, needed):
        if needed > self.quota:
            raise Failure(State.QUOTA)
        while self.used + needed > self.quota:
            available = [k for k in self.entries if not self.pins.get(k, 0)]
            if not available:
                raise Failure(State.QUOTA)
            victim = min(available, key=lambda k: (self.entries[k]['access_sequence'], k))
            self._remove(victim)
            self.evictions += 1

    def _metadata(self, directory: Path, metadata: dict):
        data = json.dumps(metadata, sort_keys=True, separators=(',', ':')).encode()
        if len(data) > 4096:
            raise Failure(State.INVALID_CACHE)
        temporary = directory / 'metadata.part'
        with temporary.open('wb') as f:
            f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(temporary, directory / 'metadata.json')

    @staticmethod
    def _digest(path: Path, cancel: threading.Event):
        digest = hashlib.sha256()
        with path.open('rb') as f:
            while True:
                check_cancel(cancel)
                data = f.read(CHUNK)
                if not data: return digest.hexdigest()
                digest.update(data)

    def plan(self, specs: list[TileSpec]):
        """Metadata-only estimate; cached files are fully revalidated before any lease."""
        with self.mutex:
            if self.closed: raise Failure(State.INVALID_CACHE)
            return self._plan(specs)

    def _plan(self, specs):
        if len(specs) > 256: raise Failure(State.QUOTA)
        unique = {s.key: s for s in specs}
        missing = [s for key, s in unique.items() if key not in self.entries]
        outside_pins = sum(m['spec']['side']**2*2+META_RESERVE for key,m in self.entries.items() if key not in unique and self.pins.get(key,0))
        pinned_bytes = CONTROL_RESERVE + outside_pins + sum(s.decoded_bytes+META_RESERVE for s in unique.values())
        staging_bytes = pinned_bytes + max((s.packed_bytes for s in missing),default=0)
        return {'tiles': len(unique), 'uncached_tiles': len(missing),
                'expected_download_bytes': sum(s.packed_bytes for s in missing),
                'maximum_attempt_bytes': sum((s.packed_bytes + 1) * self.attempts for s in missing),
                'decoded_payload_bytes': sum(s.decoded_bytes for s in unique.values()),
                'largest_staging_reservation': max((s.packed_bytes + s.decoded_bytes + META_RESERVE for s in missing), default=0),
                'pinned_set_reservation_bytes': pinned_bytes,
                'worst_pinned_staging_bytes': staging_bytes,
                'can_prepare_pinned_set': staging_bytes <= self.quota,
                'cache_validation_pending': True}

    def acquire_many(self, specs: list[TileSpec], budget: DownloadBudget, cancel=None, offline=False):
        """Return the complete pinned set or release every lease acquired by this call."""
        with self.mutex:
            cancel = cancel or threading.Event()
            check_cancel(cancel)
            plan = self.plan(specs)
            if offline and plan['uncached_tiles']: raise Failure(State.OFFLINE_MISS)
            if not plan['can_prepare_pinned_set']: raise Failure(State.QUOTA)
            if not offline and budget.remaining < plan['expected_download_bytes'] + plan['uncached_tiles']:
                raise Failure(State.DOWNLOAD_LIMIT)
            leases=[]
            try:
                for spec in {s.key:s for s in specs}.values():
                    leases.append(self.acquire(spec,budget,cancel,offline))
                return leases
            except BaseException:
                for lease in leases: lease.close()
                raise

    def _download(self, spec: TileSpec, path: Path, budget: DownloadBudget, cancel: threading.Event):
        for attempt in range(self.attempts):
            check_cancel(cancel)
            if budget.remaining < spec.packed_bytes + 1: raise Failure(State.DOWNLOAD_LIMIT)
            deadline = time.monotonic() + self.attempt_seconds
            try:
                request = urllib.request.Request(spec.url, headers={'Accept-Encoding': 'identity', 'User-Agent': 'NodeScope-Terrain-Study/1'})
                with self.opener.open(request, timeout=self.timeout) as response:
                    check_cancel(cancel)
                    if response.status != 200: raise Failure(State.NETWORK_ERROR)
                    if response.headers.get('Content-Encoding', 'identity') != 'identity': raise Failure(State.CORRUPT)
                    length = response.headers.get('Content-Length')
                    if length is not None and (not length.isdigit() or int(length) != spec.packed_bytes): raise Failure(State.CORRUPT)
                    count = 0
                    with path.open('wb') as target:
                        while True:
                            check_cancel(cancel)
                            if time.monotonic() >= deadline: raise Failure(State.TIMEOUT)
                            if budget.remaining == 0: raise Failure(State.DOWNLOAD_LIMIT)
                            data = response.read1(min(CHUNK, budget.remaining, spec.packed_bytes + 1 - count))
                            budget.received += len(data)
                            if not data: break
                            count += len(data)
                            if count > spec.packed_bytes: raise Failure(State.CORRUPT)
                            target.write(data)
                        target.flush(); os.fsync(target.fileno())
                    if count != spec.packed_bytes: raise Failure(State.CORRUPT)
                    return
            except urllib.error.HTTPError as error:
                code = error.code; error.close()
                check_cancel(cancel)
                if code == 404: raise Failure(State.MISSING_TILE)
                # Do not immediately retry rate limiting; native policy must honor Retry-After.
                if code not in [408, 500, 502, 503, 504] or attempt + 1 == self.attempts: raise Failure(State.NETWORK_ERROR)
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                check_cancel(cancel)
                if attempt + 1 == self.attempts: raise Failure(State.NETWORK_ERROR) from error
            if cancel.wait(.05 * (attempt + 1)): raise Failure(State.CANCELLED)

    def acquire(self, spec: TileSpec, budget: DownloadBudget, cancel: threading.Event | None = None, offline=False):
        cancel = cancel or threading.Event()
        with self.mutex:
            if self.closed: raise Failure(State.INVALID_CACHE)
            check_cancel(cancel)
            key = spec.key
            try:
                if key in self.entries:
                    metadata = self.entries[key]
                    path = self.root / key / 'terrain.hgt'
                    try:
                        valid = not path.is_symlink() and path.stat().st_size == spec.decoded_bytes and self._digest(path, cancel) == metadata['decoded_sha256']
                    except FileNotFoundError:
                        valid = False
                    if valid:
                        self.sequence += 1
                        metadata = metadata | {'access_sequence': self.sequence}
                        self._metadata(path.parent, metadata)
                        self.entries[key] = metadata; self.pins[key] += 1
                        return Lease(self, key, dict(metadata), True)
                    # Never remove a file while a caller holds it, even if externally damaged.
                    if self.pins.get(key, 0): raise Failure(State.CORRUPT)
                    self._remove(key)
                    if offline: raise Failure(State.CORRUPT)
                if offline: raise Failure(State.OFFLINE_MISS)
                if budget.remaining < spec.packed_bytes + 1: raise Failure(State.DOWNLOAD_LIMIT)
                needed = spec.packed_bytes + spec.decoded_bytes + META_RESERVE
                self._room(needed)
                self.reserved = needed
                self.peak_reserved = max(self.peak_reserved, self.used + needed)
                stage = self.root / ('.stage-' + uuid.uuid4().hex)
                try:
                    stage.mkdir()
                    packed = stage / 'download.gz'; decoded = stage / 'terrain.hgt'
                    self._download(spec, packed, budget, cancel)
                    if self._digest(packed, cancel) != spec.compressed_sha256: raise Failure(State.CORRUPT)
                    digest = hashlib.sha256(); count = 0
                    try:
                        with gzip.open(packed, 'rb') as source, decoded.open('wb') as target:
                            while True:
                                check_cancel(cancel)
                                data = source.read(min(CHUNK, spec.decoded_bytes + 1 - count))
                                if not data: break
                                count += len(data)
                                if count > spec.decoded_bytes: raise Failure(State.CORRUPT)
                                target.write(data); digest.update(data)
                            target.flush(); os.fsync(target.fileno())
                    except (gzip.BadGzipFile, EOFError, zlib.error) as error:
                        raise Failure(State.CORRUPT) from error
                    if count != spec.decoded_bytes: raise Failure(State.CORRUPT)
                    check_cancel(cancel)
                    self.sequence += 1
                    metadata = {'schema': 1, 'spec': dataclasses.asdict(spec), 'identity': spec.identity,
                                'decoder': DECODER, 'decoded_sha256': digest.hexdigest(), 'nodata': -32768,
                                'horizontal_datum': 'EPSG:4326', 'resampling': 'none',
                                'fetched_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                'access_sequence': self.sequence}
                    self._metadata(stage, metadata)
                    packed.unlink()
                    check_cancel(cancel)
                    os.replace(stage, self.root / key)
                    self.entries[key] = metadata; self.pins[key] = 1
                    return Lease(self, key, dict(metadata), False)
                finally:
                    if stage.exists(): shutil.rmtree(stage)
                    self.reserved = 0
            except OSError as error:
                raise Failure(State.IO_ERROR) from error

    def clear_unpinned(self):
        with self.mutex:
            if self.closed: raise Failure(State.INVALID_CACHE)
            for key in list(self.entries):
                if not self.pins.get(key, 0): self._remove(key)

    def close(self):
        with self.mutex:
            if not self.closed:
                if any(self.pins.values()): raise Failure(State.BUSY)
                self.closed = True
                fcntl.flock(self.lock_file, fcntl.LOCK_UN)
                self.lock_file.close()

    def __enter__(self): return self
    def __exit__(self, *args): self.close()
