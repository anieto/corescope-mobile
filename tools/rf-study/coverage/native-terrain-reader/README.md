# Native leased HGT reader study

Swift/Foundation/CryptoKit and Kotlin/JVM file readers for the terrain repository contract.
These are isolated source candidates, not app integrations or complete download/cache adapters.
They replace the assumption of reading an arbitrary unchecked HGT path with an explicit transferred
repository pin, streamed checksum validation, bounded random reads and deterministic closure.

## Ownership and data contract

- One background worker owns each reader. Neither implementation supports concurrent reads/close;
  the eventual repository actor/worker must serialize them. Swift deliberately is not Sendable.
- `open` takes ownership of one pin immediately, including invalid descriptor/open failures.
  Its nonthrowing release callback must run on that worker and must not reenter the reader.
  Close the file before releasing the pin, exactly once. Kotlin callers must use `use`/`finally`;
  Swift also closes on deinit, but explicit close is preferred. No GC finalizer is used.
- The caller supplies a trusted decoded SHA-256 from validated repository metadata. Exact HGT
  dimensions and checksum are verified using at most 64 KiB per hash read. No whole-file mapping
  or heap copy. Validation checks cancellation before/between chunks and before returning.
- Reads return 1–32,768 signed big-endian samples, north-first and row-major; runs may cross rows.
  Nodata remains −32768, negative heights remain negative. The sampler must treat nodata as unknown.
  Bounds are checked before offset multiplication, including maximum integer inputs.
- Cancellation, invalid descriptors/ranges, corruption/truncation, I/O and closed readers are
  distinct failures. A cancelled/failed read keeps its pin until close. No terrain error becomes 0.
- Repository-owned pinned files must remain immutable. Hashing on open does not detect arbitrary
  later in-place edits; truncation during a subsequent requested read is tested. An OS file read
  is not asynchronously interruptible here; no maximum cancellation latency is claimed.
- The 64 KiB limit is encoded I/O size, not total process memory. Decoded output and temporary data
  also consume memory. Geometry selection, seam ownership/interpolation and reader-to-scheduler
  adaptation are still separate work. These readers accept raw HGT, not the study mosaic header.

## Reproduce

The preparation harness intentionally decodes whole source tiles to independently compute expected
values; its memory use is not evidence about reader memory. It verifies the existing eight source
gzip pins, performs no network requests, and writes about 208 MB of temporary decoded fixtures.

```sh
python3 tools/rf-study/coverage/native-terrain-reader/prepare_fixtures.py /tmp/nodescope-coverage-wide/data /tmp/nodescope-native-reader-fixtures
TERRAIN_READER_FIXTURES=/tmp/nodescope-native-reader-fixtures swift test --package-path tools/rf-study/coverage/native-terrain-reader --scratch-path /tmp/nodescope-native-reader-swift-build
TERRAIN_READER_FIXTURES=/tmp/nodescope-native-reader-fixtures gradle -p tools/rf-study/coverage/native-terrain-reader/kotlin test
```

For a restricted build environment, Swift module caches can be redirected with
`CLANG_MODULE_CACHE_PATH` and `SWIFT_MODULECACHE_PATH` to writable temporary directories.
The recorded run used SwiftPM's `--disable-sandbox` because the outer workspace sandbox already
restricted filesystem writes. Kotlin was compiled/tested offline with cached Kotlin 2.2.20 jars
and Java 17, avoiding writes to the user Gradle cache:

```sh
python3 tools/rf-study/coverage/native-terrain-reader/run_kotlin_tests.py /tmp/nodescope-native-reader-fixtures /tmp/nodescope-native-reader-kotlin-build --java /path/to/jdk17/bin/java
```

The fallback runner explicitly checks required cached dependencies and never downloads them.
The Gradle build definition is supplied for normal environments but was not exercised successfully
in this restricted run. Missing `TERRAIN_READER_FIXTURES` fails the real-data tests instead of skipping.

## Evidence

13 Swift Testing tests and 12 Kotlin/JUnit tests passed on the host. Parameterized Swift tests cover
additional cases inside those function counts; Kotlin loops cover the same cases, with Swift's
deinit lifecycle check accounting for the extra test. Each platform checked 848 reads / 576,505
samples against Python's independent signed decoder across all eight pinned tiles, including
corners, row boundaries, maximum 32,768-sample reads and deterministic random runs.

See [study report](../../../../docs/node-planner/native-terrain-reader-study.md). No iOS simulator,
Android emulator, physical-device execution, network adapter or app UI validation is claimed.
