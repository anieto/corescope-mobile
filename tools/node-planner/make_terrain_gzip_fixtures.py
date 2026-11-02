#!/usr/bin/env python3
"""Generate deterministic gzip validation fixtures independently of the native decoders."""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import random
import struct
import zlib

root = Path(__file__).resolve().parents[2]
raw = struct.pack('>9h', -32768, -1, 0, 1, 2, 3, 4, 5, 6)
packed = gzip.compress(raw, mtime=0)
cases = []

def add(name, payload, valid=False, decoded=raw, side=3, size=None, digest=None):
    cases.append(dict(name=name, side=side, valid=valid, packed_bytes=len(payload) if size is None else size,
        source_sha256=hashlib.sha256(payload).hexdigest() if digest is None else digest,
        decoded_sha256=hashlib.sha256(decoded).hexdigest(), packed_base64=base64.b64encode(payload).decode()))

add('signed_and_nodata', packed, True)
header = b'\x1f\x8b\x08\x1e' + b'\0' * 4 + b'\0\xff' + struct.pack('<H', 3) + b'xyz' + b'terrain.hgt\0' + b'fixture\0'
header += struct.pack('<H', zlib.crc32(header) & 65535)
optional = header + zlib.compress(raw, wbits=-15) + struct.pack('<II', zlib.crc32(raw), len(raw))
add('optional_header_and_crc', optional, True)
random_bytes = random.Random(1234).randbytes(241 * 241 * 2)
add('multiple_input_output_chunks', gzip.compress(random_bytes, mtime=0), True, random_bytes, 241)
add('source_hash_mismatch', packed, digest='0' * 64)
add('source_length_short', packed, size=len(packed) - 1)
add('source_length_long', packed, size=len(packed) + 1)
add('truncated_header', packed[:7])
add('truncated_deflate', packed[:14])
add('truncated_trailer', packed[:-1])
add('invalid_crc', packed[:-8] + bytes([packed[-8] ^ 1]) + packed[-7:])
add('invalid_isize', packed[:-1] + bytes([packed[-1] ^ 1]))
add('invalid_header_crc', optional[:len(header)-1] + bytes([optional[len(header)-1] ^ 1]) + optional[len(header):])
add('invalid_method', packed[:2] + b'\x09' + packed[3:])
add('reserved_flags', packed[:3] + b'\xe0' + packed[4:])
add('zlib_not_gzip', zlib.compress(raw))
add('extra_member', packed + gzip.compress(b'', mtime=0))
add('trailing_junk', packed + b'junk')
add('trailing_zero', packed + b'\0')
add('short_decoded', gzip.compress(raw[:-2], mtime=0))
add('long_decoded', gzip.compress(raw + b'\0\0', mtime=0))
add('expansion_beyond_dimensions', gzip.compress(b'\0' * 1000000, mtime=0))
long_header = b'\x1f\x8b\x08\x08' + b'\0' * 6 + b'A' * 65536 + b'\0'
add('overlong_optional_header', long_header + packed[10:])
(root/'shared/fixtures/coverage/terrain-gzip.json').write_text(json.dumps(cases, indent=2)+'\n')
