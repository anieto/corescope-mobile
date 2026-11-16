#!/usr/bin/env python3
"""Saved-plans export packages (deliverable D): reference reader and cases.

Rules: docs/node-planner/saved-plans-v3-package.md. Writes, next to this script:
  packages/package-cases-v1.json   packages (base64) with the outcome a reader must reach

Each case is read by the reference reader below, which follows the document step by step
(its own ZIP parser, not Python's zipfile, so it checks what the apps check). Outcomes:
  valid            the package imports as is
  resultUnusable   the package is valid, but the listed studies' results can't be imported;
                   their inputs can be, after an explicit acknowledgement
  future           a newer package version: refused, local data untouched, "needs a newer version"
  invalid          refused with the reason given

Run from the repository root: python3 -I shared/fixtures/saved-plans/make-package-fixtures.py
"""
import base64
import copy
import hashlib
import importlib.util
import json
import os
import re
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v3 = load('v3', 'make-v3-fixtures.py')
results = load('results', 'make-result-fixtures.py')
Invalid, need, keys = v3.Invalid, v3.need, v3.keys

FORMAT = 'nodescope.saved-plans'
MiB = 1024 * 1024
MAX_PACKAGE_BYTES = 512 * MiB
MAX_ENTRIES = 101
MAX_CENTRAL_DIRECTORY = 1 * MiB
MAX_MANIFEST_BYTES = 16 * MiB
MAX_EXPANDED_TOTAL = 512 * MiB
RESULT_LIMITS = {'nodescope.coverage-result': 8 * MiB, 'nodescope.link-profile-result': 64 * 1024}
RESULT_KIND = {'nodescope.coverage-result': 'coverage', 'nodescope.link-profile-result': 'linkProfile'}
RESULT_ENTRY = re.compile(r'^results/([0-9a-f]{64})\.result$')
ALLOWED_FLAGS = 0x0002 | 0x0004 | 0x0008 | 0x0800   # deflate options, data descriptor, UTF-8 names
OMISSION_REASONS = {'notOnDevice', 'couldNotBeVerified', 'needsNewerVersion', 'notReadableHere'}
APP_VERSION = re.compile(r'^[0-9A-Za-z.+-]{1,32}$')


class Future(Exception):
    pass


# ---------------------------------------------------------------- container (ZIP subset)

def read_entries(data):
    """The package's entries by name, read through the central directory. Raises Invalid."""
    need(22 <= len(data) <= MAX_PACKAGE_BYTES, 'package size out of range')
    # End of central directory: the record whose comment ends exactly at the end of the file.
    eocd = None
    for offset in range(len(data) - 22, max(-1, len(data) - 22 - 65535 - 1), -1):
        if data[offset:offset + 4] == b'PK\x05\x06':
            comment = struct.unpack_from('<H', data, offset + 20)[0]
            if offset + 22 + comment == len(data):
                eocd = offset
                break
    need(eocd is not None, 'not a package: no end of central directory')
    need(eocd < 20 or data[eocd - 20:eocd - 16] != b'PK\x06\x07', 'ZIP64 is not supported')
    disk, cd_disk, here, total, cd_size, cd_offset, _ = struct.unpack_from('<HHHHIIH', data, eocd + 4)
    need(disk == 0 and cd_disk == 0 and here == total, 'multi-disk archives are not supported')
    need(0xFFFF not in (here, total) and 0xFFFFFFFF not in (cd_size, cd_offset), 'ZIP64 is not supported')
    need(1 <= total <= MAX_ENTRIES, f'a package has 1 to {MAX_ENTRIES} entries')
    need(cd_size <= MAX_CENTRAL_DIRECTORY and cd_offset + cd_size == eocd, 'central directory out of place')

    entries, position = [], cd_offset
    for _ in range(total):
        need(position + 46 <= eocd and data[position:position + 4] == b'PK\x01\x02', 'damaged central directory')
        (_, _, flags, method, _, _, crc, csize, usize, name_length, extra_length, comment_length,
         start_disk, _, _, local) = struct.unpack_from('<HHHHHHIIIHHHHHII', data, position + 4)
        name_bytes = data[position + 46:position + 46 + name_length]
        position += 46 + name_length + extra_length + comment_length
        need(position <= eocd, 'damaged central directory')
        need(0xFFFFFFFF not in (csize, usize, local), 'ZIP64 is not supported')
        need(flags & 0x0001 == 0 and flags & 0x0040 == 0 and flags & 0x2000 == 0, 'encrypted entries are not supported')
        need(flags & ~ALLOWED_FLAGS == 0, 'unsupported entry flags')
        need(method in (0, 8), f'unsupported compression method {method}')
        need(start_disk == 0, 'multi-disk archives are not supported')
        try:
            name = name_bytes.decode('ascii')
        except UnicodeDecodeError:
            raise Invalid('unexpected entry name')
        need(name == 'manifest.json' or RESULT_ENTRY.match(name), f'unexpected entry name {name!r}')
        need(name not in [e['name'] for e in entries], f'duplicate entry {name}')
        limit = MAX_MANIFEST_BYTES if name == 'manifest.json' else max(RESULT_LIMITS.values())
        need(usize <= limit, f'{name} is larger than its limit')
        need(method == 8 or csize == usize, 'stored entry sizes differ')
        need(csize <= usize + 1024 + usize // 1000, f'{name}: compressed size out of range')
        entries.append(dict(name=name, flags=flags, method=method, crc=crc, csize=csize, usize=usize, local=local))
    need(position == eocd, 'damaged central directory')
    need(sum(e['usize'] for e in entries) <= MAX_EXPANDED_TOTAL, 'package expands beyond its limit')

    # Local headers: each matches its central record, and no entry's data overlaps another's.
    boundaries = sorted(e['local'] for e in entries) + [cd_offset]
    out = {}
    for entry in entries:
        local = entry['local']
        need(local + 30 <= cd_offset and data[local:local + 4] == b'PK\x03\x04', 'damaged local header')
        _, flags, method, _, _, _, _, _, name_length, extra_length = struct.unpack_from('<HHHHHIIIHH', data, local + 4)
        need(flags == entry['flags'] and method == entry['method'], 'local header disagrees with central directory')
        need(data[local + 30:local + 30 + name_length] == entry['name'].encode('ascii'),
             'local header disagrees with central directory')
        start = local + 30 + name_length + extra_length
        following = boundaries[boundaries.index(local) + 1]
        need(start + entry['csize'] <= following, 'entries overlap')
        raw = data[start:start + entry['csize']]
        if method == 0:
            content = raw
        else:
            inflater = zlib.decompressobj(-15)
            try:
                content = inflater.decompress(raw, entry['usize'] + 1)
            except zlib.error:
                raise Invalid(f'{entry["name"]}: damaged compressed data')
            need(inflater.eof and not inflater.unconsumed_tail and not inflater.unused_data,
                 f'{entry["name"]}: compressed data does not match its sizes')
        need(len(content) == entry['usize'], f'{entry["name"]}: compressed data does not match its sizes')
        need(zlib.crc32(content) == entry['crc'], f'{entry["name"]}: checksum mismatch')
        out[entry['name']] = content
    return out


# ---------------------------------------------------------------- manifest

def strict_json(data, where):
    def no_duplicates(pairs):
        names = [k for k, _ in pairs]
        need(len(set(names)) == len(names), f'{where}: duplicate field')
        return dict(pairs)

    def no_constants(token):
        raise Invalid(f'{where}: non-finite number')
    try:
        return json.loads(data.decode('utf-8'), object_pairs_hook=no_duplicates, parse_constant=no_constants)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise Invalid(f'{where} is not valid JSON')


def read_package(data):
    """Returns (manifest, files, unusable study IDs). Raises Invalid or Future."""
    files = read_entries(data)
    need('manifest.json' in files, 'manifest.json is missing')
    m = strict_json(files['manifest.json'], 'manifest.json')
    need(isinstance(m, dict) and m.get('format') == FORMAT, 'not a NodeScope saved-plans package')
    version = m.get('version')
    need(isinstance(version, int) and not isinstance(version, bool) and version >= 1, 'package version must be a positive integer')
    if version > 1:
        raise Future(f'package version {version} needs a newer version of NodeScope')
    keys(m, ['format', 'version', 'scope', 'exportedAtMilliseconds', 'exportedBy', 'includesResults', 'includesNotes',
             'candidateSitePlans', 'studyPlans', 'studies', 'omittedResults'], where='manifest')
    need(m['scope'] in ('study', 'plan', 'library'), f'unknown scope {m["scope"]!r}')
    exported = m['exportedAtMilliseconds']
    need(isinstance(exported, int) and not isinstance(exported, bool) and 0 <= exported <= v3.MAX_MS,
         'exportedAtMilliseconds must be epoch milliseconds')
    keys(m['exportedBy'], ['platform', 'appVersion'], where='exportedBy')
    need(m['exportedBy']['platform'] in ('ios', 'android'), 'exportedBy.platform must be ios or android')
    need(isinstance(m['exportedBy']['appVersion'], str) and APP_VERSION.match(m['exportedBy']['appVersion']),
         'exportedBy.appVersion out of range')
    need(isinstance(m['includesResults'], bool) and isinstance(m['includesNotes'], bool), 'includes flags must be booleans')

    sites, plans, studies, omitted = m['candidateSitePlans'], m['studyPlans'], m['studies'], m['omittedResults']
    need(all(isinstance(x, list) for x in (sites, plans, studies, omitted)), 'record collections must be arrays')

    # Records: exactly the v3 library rules. Result references in a newer result version are
    # set aside first (that study's result is unusable, not the package).
    unusable = set()
    checked = copy.deepcopy(studies)
    for study in checked:
        ref = study.get('resultRef') if isinstance(study, dict) else None
        if isinstance(ref, dict) and ref.get('format') in RESULT_KIND and ref.get('version') != 1:
            need(isinstance(ref.get('version'), int) and not isinstance(ref.get('version'), bool) and ref['version'] > 1,
                 'resultRef.version must be a positive integer')
            unusable.add(study.get('id'))
            ref['version'] = 1
    v3.validate({'schemaVersion': 3, 'documentRevision': 1, 'plans': sites, 'studyPlans': plans, 'studies': checked})

    if m['scope'] == 'study':
        need(len(studies) == 1 and not plans and not sites, 'a study package holds exactly one study and nothing else')
        need(studies[0].get('planID') is None, 'a study package carries no plan membership')
    elif m['scope'] == 'plan':
        need(len(plans) == 1 and not sites, 'a plan package holds exactly one plan and its studies')
        need(all(s.get('planID') == plans[0]['id'] for s in studies), 'every study in a plan package belongs to its plan')
    if not m['includesNotes']:
        need(all('notes' not in p for p in plans), 'notes present although the package says they were omitted')

    # Results: every reference has its entry, every entry is referenced, and bytes match.
    referenced = set()
    for study in studies:
        ref = study.get('resultRef')
        if ref is None:
            continue
        need(m['includesResults'], 'a result reference in a package without results')
        need(ref['format'] in RESULT_KIND, f'{ref["format"]} results are not portable: they never travel in a package')
        name = f'results/{ref["sha256"]}.result'
        need(name in files, f'{name} is missing')
        content = files[name]
        need(len(content) == ref['byteLength'], f'{name}: byteLength mismatch')
        need(hashlib.sha256(content).hexdigest() == ref['sha256'], f'{name}: sha256 mismatch')
        referenced.add(name)
        if study['id'] in unusable:
            continue
        if ref['byteLength'] > RESULT_LIMITS[ref['format']]:
            unusable.add(study['id'])
            continue
        try:
            need(ref['inputFingerprint'] == v3.study_fingerprint(study), 'reference fingerprint does not match the inputs')
            payload = strict_json(content, name)
            wrapper = {'kind': study['kind'], study['kind']: study[study['kind']]}
            (results.validate_coverage if study['kind'] == 'coverage' else results.validate_link)(wrapper, payload)
        except (Invalid, results.Invalid):  # the result module loads its own copy of the v3 rules
            unusable.add(study['id'])
    need(set(files) - {'manifest.json'} == referenced, 'a result entry no study references')

    study_ids = {s['id'] for s in studies}
    seen = set()
    for item in omitted:
        keys(item, ['studyID', 'reason'], where='omittedResults')
        need(item['studyID'] in study_ids and item['studyID'] not in seen, 'omittedResults must name each package study at most once')
        seen.add(item['studyID'])
        need(item['reason'] in OMISSION_REASONS, f'unknown omission reason {item["reason"]!r}')
        study = next(s for s in studies if s['id'] == item['studyID'])
        need(study.get('resultRef') is None, 'an omitted result cannot also be included')
    return m, files, sorted(unusable)


# ---------------------------------------------------------------- writer (for fixtures)

def zip_bytes(entries, comment=b'', data_descriptor=False):
    """entries: dicts with name and content, optionally method, flags and lies to patch in."""
    out, central = bytearray(), bytearray()
    for e in entries:
        name = e['name'].encode('utf-8') if isinstance(e['name'], str) else e['name']
        content = e['content']
        method = e.get('method', 8)
        stored = content if method == 0 else (lambda c: c.compress(content) + c.flush())(zlib.compressobj(9, zlib.DEFLATED, -15))
        crc = e.get('crc', zlib.crc32(content))
        usize = e.get('usize', len(content))
        csize = e.get('csize', len(stored) + e.get('csize_extra', 0))
        flags = e.get('flags', 0x0008 if data_descriptor and method == 8 else 0)
        local = len(out)
        local_name = e.get('local_name', name)
        local_flags = e.get('local_flags', flags)
        described = flags & 0x0008
        out += struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, local_flags, method, 0, 0x5B21,
                           0 if described else crc, 0 if described else csize, 0 if described else usize, len(local_name), 0)
        out += local_name + stored
        if described:
            out += struct.pack('<IIII', 0x08074b50, crc, csize, usize)
        central += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, flags, method, 0, 0x5B21, crc, csize, usize,
                               len(name), 0, 0, 0, 0, 0, e.get('local_offset', local))
        central += name
    cd_offset = len(out)
    out += central
    count = len(entries)
    out += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, count, count, len(central), cd_offset, len(comment)) + comment
    return bytes(out)


def manifest_bytes(m):
    return v3.encoded(m)


# ---------------------------------------------------------------- reference content

def library():
    document, files, _missing = v3.build_reference()
    return document, files


def base(scope, include_results=True, include_notes=True):
    document, files = library()
    plan_id = document['studyPlans'][0]['id']
    by_id = {s['id']: s for s in document['studies']}
    portable = {sha: data for sha, data in files.items()}

    def drop(study, reason, omitted):
        study.pop('resultRef', None)
        omitted.append(dict(studyID=study['id'], reason=reason))

    if scope == 'study':
        studies = [copy.deepcopy(by_id['40000000-0000-4000-8000-000000000001'])]
        studies[0].pop('planID')
        plans, sites = [], []
    elif scope == 'plan':
        studies = [copy.deepcopy(s) for s in document['studies'] if s.get('planID') == plan_id]
        plans, sites = [copy.deepcopy(document['studyPlans'][0])], []
    else:
        studies = copy.deepcopy(document['studies'])
        plans, sites = copy.deepcopy(document['studyPlans']), copy.deepcopy(document['plans'])

    omitted = []
    for study in studies:
        ref = study.get('resultRef')
        if ref is None:
            continue
        if not include_results:
            study.pop('resultRef')
        elif ref['format'].startswith('legacy.'):
            # Converted to the portable format when this platform can read it; otherwise left out, and said so.
            drop(study, 'notReadableHere', omitted)
        elif ref['sha256'] not in portable:
            drop(study, 'notOnDevice', omitted)
    if not include_notes:
        for plan in plans:
            plan.pop('notes', None)
    m = dict(format=FORMAT, version=1, scope=scope, exportedAtMilliseconds=1794790000000,
             exportedBy=dict(platform='ios', appVersion='0.10.7'), includesResults=include_results,
             includesNotes=include_notes, candidateSitePlans=sites, studyPlans=plans, studies=studies,
             omittedResults=omitted)
    return m, portable


def entries_for(m, files, method=8):
    shas = sorted({s['resultRef']['sha256'] for s in m['studies'] if s.get('resultRef')})
    return [dict(name='manifest.json', content=manifest_bytes(m), method=method)] + \
           [dict(name=f'results/{sha}.result', content=files[sha], method=method) for sha in shas]


def package(m, files, **options):
    return zip_bytes(entries_for(m, files), **options)


# ---------------------------------------------------------------- cases

def cases():
    out = []

    def add(name, data, expect, reason=None, unusable=(), summary=None):
        out.append(dict(name=name, expect=expect, reason=reason, unusableStudyIDs=sorted(unusable),
                        summary=summary, packageBase64=base64.b64encode(data).decode('ascii')))

    def summary(m):
        return dict(scope=m['scope'], candidateSitePlans=len(m['candidateSitePlans']), studyPlans=len(m['studyPlans']),
                    studies=len(m['studies']), results=sum(1 for s in m['studies'] if s.get('resultRef')),
                    resultFiles=len({s['resultRef']['sha256'] for s in m['studies'] if s.get('resultRef')}),
                    omittedResults=len(m['omittedResults']))

    def variant(name, change, expect='invalid', reason=None, unusable=(), scope='library', entries_change=None, **options):
        m, files = base(scope)
        change(m, files)
        entries = entries_for(m, files)
        if entries_change:
            entries_change(entries)
        add(name, zip_bytes(entries, **options), expect, reason, unusable)

    # Valid packages.
    for scope in ('study', 'plan', 'library'):
        m, files = base(scope)
        add(f'{scope} with results', package(m, files), 'valid', summary=summary(m))
    m, files = base('library', include_results=False)
    add('library inputs only', package(m, files), 'valid', summary=summary(m))
    m, files = base('plan', include_notes=False)
    add('plan without notes', package(m, files), 'valid', summary=summary(m))
    m, files = base('library')
    add('stored entries', zip_bytes(entries_for(m, files, method=0)), 'valid', summary=summary(m))
    add('data descriptors', package(m, files, data_descriptor=True), 'valid', summary=summary(m))
    add('archive comment', package(m, files, comment=b'NodeScope'), 'valid', summary=summary(m))
    m, files = base('study')
    link = base('plan')[0]['studies'][1]
    link.pop('planID')
    m['studies'] = [link]
    add('study link profile', package(m, files), 'valid', summary=summary(m))
    m, files = base('study', include_results=False)
    add('study inputs only', package(m, files), 'valid', summary=summary(m))

    # Container.
    m, files = base('library')
    good = package(m, files)
    add('not a zip', b'{"format":"nodescope.saved-plans"}' + b' ' * 40, 'invalid', 'no end of central directory')
    add('empty', b'', 'invalid', 'package size out of range')
    add('truncated', good[:-30], 'invalid', 'no end of central directory')
    add('trailing bytes', good + b'\0' * 8, 'invalid', 'no end of central directory')
    add('zip64 locator', good[:-22] + b'PK\x06\x07' + b'\0' * 16 + good[-22:], 'invalid', 'ZIP64 is not supported')
    add('multi-disk', good[:-18] + b'\x01\x00' + good[-16:], 'invalid', 'multi-disk')

    def entry_change(name, field, value):
        def apply(entries):
            target = next(e for e in entries if e['name'] == name)
            target[field] = value
        return apply

    variant('encrypted entry', lambda m, f: None, reason='encrypted entries are not supported',
            entries_change=entry_change('manifest.json', 'flags', 0x0001))
    variant('unsupported flags', lambda m, f: None, reason='unsupported entry flags',
            entries_change=entry_change('manifest.json', 'flags', 0x0010))
    variant('bzip2 method', lambda m, f: None, reason='unsupported compression method 12',
            entries_change=entry_change('manifest.json', 'method', 12))
    for label, bad in [('parent path', '../manifest.json'), ('absolute path', '/manifest.json'),
                       ('nested parent', 'results/../manifest.json'), ('backslash', 'results\\x.result'),
                       ('unknown entry', 'notes.txt'), ('uppercase hash', 'results/' + 'A' * 64 + '.result'),
                       ('folder entry', 'results/')]:
        def extra(entries, bad=bad):
            entries.append(dict(name=bad, content=b'', method=0))
        variant(f'entry name: {label}', lambda m, f: None, reason='unexpected entry name', entries_change=extra)
    variant('entry name: not ascii', lambda m, f: None, reason='unexpected entry name',
            entries_change=lambda e: e.append(dict(name='résults.json'.encode('utf-8'), content=b'', method=0)))
    variant('duplicate entry', lambda m, f: None, reason='duplicate entry manifest.json',
            entries_change=lambda e: e.append(dict(e[0])))
    variant('too many entries', lambda m, f: None, reason='a package has 1 to 101 entries',
            entries_change=lambda e: e.extend(dict(name=f'results/{i:064x}.result', content=b'x', method=0) for i in range(101)))
    variant('checksum mismatch', lambda m, f: None, reason='manifest.json: checksum mismatch',
            entries_change=entry_change('manifest.json', 'crc', 1))
    variant('local name differs', lambda m, f: None, reason='local header disagrees',
            entries_change=entry_change('manifest.json', 'local_name', b'manifest.jsoN'))
    variant('local flags differ', lambda m, f: None, reason='local header disagrees',
            entries_change=entry_change('manifest.json', 'local_flags', 0x0800))
    variant('overlapping entries', lambda m, f: None, reason='entries overlap',
            entries_change=lambda e: e[1].update(csize_extra=10))
    variant('manifest declared too large', lambda m, f: None, reason='manifest.json is larger than its limit',
            entries_change=entry_change('manifest.json', 'usize', MAX_MANIFEST_BYTES + 1))
    variant('expands beyond declared size', lambda m, f: None, reason='compressed data does not match its sizes',
            entries_change=lambda e: e[0].update(usize=len(e[0]['content']) - 1))
    variant('stored sizes differ', lambda m, f: None, reason='stored entry sizes differ',
            entries_change=lambda e: e[0].update(method=0, usize=len(e[0]['content']) + 1))
    # A small deflate stream that would expand far beyond what it declares: inflating stops at the declared size.
    bomb = zlib.compressobj(9, zlib.DEFLATED, -15)
    bomb_data = bomb.compress(b'0' * MiB) + bomb.flush()
    add('compression bomb', _with_raw(base('library'), 'results/' + '0' * 64 + '.result', bomb_data, 1000),
        'invalid', 'compressed data does not match its sizes')
    variant('manifest missing', lambda m, f: None, reason='manifest.json is missing',
            entries_change=lambda e: e.pop(0))

    # Manifest.
    def raw_manifest(name, content, expect='invalid', reason=None):
        m, files = base('library')
        entries = entries_for(m, files)
        entries[0]['content'] = content
        add(name, zip_bytes(entries), expect, reason)

    raw_manifest('manifest not json', b'{"format":', reason='manifest.json is not valid JSON')
    raw_manifest('manifest duplicate field', b'{"format":"nodescope.saved-plans","format":"x"}', reason='duplicate field')
    raw_manifest('manifest NaN', b'{"format":"nodescope.saved-plans","version":NaN}', reason='non-finite number')
    variant('other format', lambda m, f: m.update(format='nodescope.saved-plan'), reason='not a NodeScope saved-plans package')
    variant('future version', lambda m, f: m.update(version=2, newField=True), expect='future', reason='needs a newer version')
    variant('version zero', lambda m, f: m.update(version=0), reason='package version must be a positive integer')
    variant('unknown field', lambda m, f: m.update(extra=1), reason='manifest has unknown fields')
    variant('missing field', lambda m, f: m.pop('omittedResults'), reason='manifest is missing')
    variant('unknown scope', lambda m, f: m.update(scope='everything'), reason='unknown scope')
    variant('unknown platform', lambda m, f: m['exportedBy'].update(platform='web'), reason='exportedBy.platform')
    variant('study scope with two studies', lambda m, f: m['studies'].append(dict(m['studies'][0], id='40000000-0000-4000-8000-0000000000aa')),
            scope='study', reason='exactly one study')
    variant('study scope with membership', lambda m, f: (m['studyPlans'].append(base('plan')[0]['studyPlans'][0]),
                                                         m['studies'][0].update(planID=m['studyPlans'][0]['id'])),
            scope='study', reason='exactly one study and nothing else')
    variant('plan scope with candidate sites', lambda m, f: m['candidateSitePlans'].extend(base('library')[0]['candidateSitePlans']),
            scope='plan', reason='exactly one plan')
    variant('plan scope with a stranger', lambda m, f: m['studies'][1].pop('planID'), scope='plan',
            reason='every study in a plan package belongs to its plan')
    variant('notes although omitted', lambda m, f: m.update(includesNotes=False), scope='plan', reason='notes present')
    variant('membership to a missing plan', lambda m, f: m['studies'][0].update(planID='30000000-0000-4000-8000-0000000000ff'),
            reason='planID must name an existing study plan')
    variant('duplicate study id', lambda m, f: m['studies'][1].update(id=m['studies'][0]['id']), reason='duplicate study id')
    variant('record rules apply', lambda m, f: m['studies'][0].update(name=''), reason='study name must be 1-160 UTF-8 bytes')

    # Results.
    def first_with_result(m):
        return next(s for s in m['studies'] if s.get('resultRef'))

    def no_results(m, f):
        m['includesResults'] = False
    variant('result in a package without results', no_results, reason='a result reference in a package without results')
    variant('missing result entry', lambda m, f: None, reason='is missing',
            entries_change=lambda e: e.pop(1))
    variant('unreferenced result entry', lambda m, f: None, reason='a result entry no study references',
            entries_change=lambda e: e.append(dict(name='results/' + hashlib.sha256(b'x').hexdigest() + '.result', content=b'x')))

    def wrong_bytes(entries):
        entries[1]['content'] = entries[1]['content'][:-1] + b' '
    variant('result sha256 mismatch', lambda m, f: None, reason='sha256 mismatch', entries_change=wrong_bytes)
    variant('result byteLength mismatch', lambda m, f: first_with_result(m)['resultRef'].update(byteLength=1),
            reason='byteLength mismatch')

    def legacy(m, f):
        doc, files = library()
        study = copy.deepcopy(next(s for s in doc['studies'] if s['resultRef'] and s['resultRef']['format'].startswith('legacy.')))
        m['studies'] = [study]
        m['omittedResults'] = []
        f.update(files)
    variant('legacy result', legacy, scope='study', reason='results are not portable')
    variant('omitted and included', lambda m, f: m['omittedResults'].append(dict(studyID=first_with_result(m)['id'], reason='notOnDevice')),
            reason='an omitted result cannot also be included')
    variant('omitted unknown study', lambda m, f: m['omittedResults'].append(dict(studyID='40000000-0000-4000-8000-0000000000ee', reason='notOnDevice')),
            reason='omittedResults must name each package study at most once')
    variant('unknown omission reason', lambda m, f: m['omittedResults'][0].update(reason='lost'), reason='unknown omission reason')

    # Results that can't be imported, in a valid package: inputs-only after acknowledgement.
    def edited_inputs(m, f):
        first_with_result(m)['coverage']['reserveDb'] = 7.0
    m, _ = base('study')
    target = m['studies'][0]['id']
    variant('inputs changed after the result', edited_inputs, scope='study', expect='resultUnusable', unusable=[target])

    def future_result(m, f):
        first_with_result(m)['resultRef']['version'] = 2
    variant('newer result version', future_result, scope='study', expect='resultUnusable', unusable=[target])

    def unreadable_payload(m, f):
        study = first_with_result(m)
        data = b'{"format":"nodescope.coverage-result","version":1}'
        f[hashlib.sha256(data).hexdigest()] = data
        study['resultRef'].update(byteLength=len(data), sha256=hashlib.sha256(data).hexdigest())
    variant('unreadable result payload', unreadable_payload, scope='study', expect='resultUnusable', unusable=[target])
    return out


def _with_raw(base_pair, name, raw_deflate, declared):
    """A package with one extra entry carrying raw deflate data and a declared size (and its reference)."""
    m, files = base_pair
    entries = entries_for(m, files)
    out = bytearray()
    central = bytearray()
    for e in entries + [dict(name=name, content=None)]:
        n = e['name'].encode()
        if e['content'] is None:
            data, crc, usize = raw_deflate, 0, declared
        else:
            c = zlib.compressobj(9, zlib.DEFLATED, -15)
            data, crc, usize = c.compress(e['content']) + c.flush(), zlib.crc32(e['content']), len(e['content'])
        local = len(out)
        out += struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, 0, 8, 0, 0x5B21, crc, len(data), usize, len(n), 0) + n + data
        central += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0, 8, 0, 0x5B21, crc, len(data), usize,
                               len(n), 0, 0, 0, 0, 0, local) + n
    offset = len(out)
    out += central + struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(entries) + 1, len(entries) + 1, len(central), offset, 0)
    return bytes(out)


def outcome(data):
    try:
        _, _, unusable = read_package(data)
    except Future as error:
        return 'future', str(error), []
    except Invalid as error:
        return 'invalid', str(error), []
    return ('resultUnusable' if unusable else 'valid'), None, unusable


def main():
    checked = []
    for case in cases():
        expect, reason, unusable = outcome(base64.b64decode(case['packageBase64']))
        need(expect == case['expect'], f"{case['name']}: expected {case['expect']}, got {expect} ({reason})")
        if case['reason']:
            need(reason is not None and case['reason'] in reason, f"{case['name']}: expected {case['reason']!r}, got {reason!r}")
        need(sorted(unusable) == case['unusableStudyIDs'], f"{case['name']}: unusable {unusable}")
        checked.append(case)
    names = [c['name'] for c in checked]
    need(len(set(names)) == len(names), 'case names must be unique')
    out = os.path.join(HERE, 'packages')
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, 'package-cases-v1.json'), 'w', encoding='utf-8') as handle:
        json.dump({'format': 'nodescope.package-cases', 'version': 1,
                   'rules': 'docs/node-planner/saved-plans-v3-package.md',
                   'limits': dict(packageBytes=MAX_PACKAGE_BYTES, entries=MAX_ENTRIES, centralDirectoryBytes=MAX_CENTRAL_DIRECTORY,
                                  manifestBytes=MAX_MANIFEST_BYTES, expandedBytes=MAX_EXPANDED_TOTAL, resultBytes=RESULT_LIMITS),
                   'cases': checked}, handle, indent=1, ensure_ascii=False)
        handle.write('\n')
    counts = {k: sum(c['expect'] == k for c in checked) for k in ('valid', 'resultUnusable', 'future', 'invalid')}
    size = os.path.getsize(os.path.join(out, 'package-cases-v1.json'))
    print(f'{len(checked)} cases {counts}; {size:,} bytes')


if __name__ == '__main__':
    main()
