# Meshtastic Site Planner native-core feasibility

Study date: 2026-10-29 (America/Chicago). Decision: **NO-GO for unchanged production adoption;
retain as a reference, evaluate kp1812 next before funding a fork.** This is a bounded engine study,
not completion of Candidate Sites or physical-device qualification.

## Pinned source and distribution audit

- [Planner](https://github.com/meshtastic/meshtastic-site-planner/tree/481e5a482f10fc6956a98aed39aacdb59c3fb6ec):
  `481e5a482f10fc6956a98aed39aacdb59c3fb6ec`.
- [SPLAT submodule](https://github.com/jmcmellen/splat/tree/08f06f56470a8361a5609503a6b1cb02f07f48dc):
  `08f06f56470a8361a5609503a6b1cb02f07f48dc`.
- Planner root LICENSE is GPLv3; driver.cpp explicitly describes adaptation from SPLAT.
  SPLAT's splat.cpp header specifies GPLv2-or-later and its COPYING contains GPLv2.
  itwom3.0.cpp carries its own NTIA/ITM and later contributor provenance; do not treat that modified
  file as equivalent to downloading the separate NTIA implementation under NTIA's release terms.
- NodeScope's App Store exception and the recorded MeshCore One permission do not establish an
  exception for these contributors. No additional app-store permission was found in the inspected
  root license or engine headers. Distribution suitability remains unresolved, not declared illegal
  or approved by this engineering review. Review the exact files/notices and obtain any necessary
  permissions before production import. No upstream authors were contacted.
- Source and terrain fixtures stayed in a temporary checkout. Only our probe scripts, measurements
  and this audit were added to NodeScope; no upstream code is linked into either app.

## Experiment

Compiled unmodified driver.cpp plus splat/itwom3.0.cpp with optimization level O2:

| Target | Outcome |
|---|---|
| macOS arm64 | Shared library and upstream native CLI built; four fixtures executed |
| iOS device arm64 | Static archive built with the iPhoneOS SDK; no physical-device execution |
| iOS simulator arm64 | Upstream CLI built and ran on iPhone 18 Pro / iOS 27.0 simulator |
| Android arm64 API 26 target | Shared library and CLI built with NDK r27d; CLI ran on Pixel 10 API 37.2 emulator |

Android's initial CLI exited 139 on the 16 KiB-page emulator. Relinking the CLI with
`-Wl,-z,max-page-size=16384` allowed fixture execution; retain that alignment requirement in any
integration. The initial shared-library artifact was only compile-checked, not dynamically loaded
by an Android app. JNI/Swift wrappers, application lifecycle and packaged app builds were not tested.
The NDK was downloaded into temporary storage, not installed into the app or committed.

All runs use upstream's four real-terrain fixtures and **legacy golden parameter conversions**,
including the historic TX-height-as-feet behavior and rounded ERP. This reproduces reference data;
it does not endorse those conversions as production settings. Every required fixture terrain page
was present. No network elevation acquisition was included in the timing.

## Numerical results

| Fixture | Host and iOS signal/mask mismatches | Android signal mismatches | Android mask mismatches | Android max signal difference |
|---|---|---|---|---|
| Calgary 30 km | 0 / 0 | 1,260 | 0 | 3 dB |
| Cape Town 20 km | 0 / 0 | 349 | 0 | 3 dB |
| London 15 km | 0 / 0 | 100 | 0 | 3 dB |
| Monterey 25 km | 0 / 0 | 103 | 0 | 5 dB |

These are comparisons against upstream's committed golden rasters. Geographic region metadata also
matched on both mobile runtimes. Android has respectively 15, 6, 10 and 6 computed cells differing
by more than 1 dB, among 524,799 / 176,553 / 132,518 / 285,046 computed cells. Most pixels agree, but
bit-level cross-platform signal parity is not established. The uint8 output cannot verify our
proposed 0.01 dB unrounded tolerance.

A separate Android build with `-ffp-contract=off` still differed (1,352 / 630 / 470 / 721 signal
bytes). Compiler contraction alone does not explain the issue. Exact causes remain unproven;
libm/compiler differences and branch-sensitive terrain/kernel math require investigation before
setting a justified tolerance. Do not label the whole model inaccurate based solely on this test.

## Timing, memory and cancellation evidence

| Fixture | Host kernel-only seconds | iOS simulator CLI wall seconds | Android emulator CLI wall seconds |
|---|---|---|---|
| Calgary | 1.575 | 32.720 | 11.616 |
| Cape Town | 0.488 | 6.568 | 2.924 |
| London | 0.429 | 4.125 | 4.495 |
| Monterey | 0.813 | 2.852 | 5.422 |

Simulator/emulator results include launch and local file I/O, and shared-host startup/contention.
They are not directly comparable kernel benchmarks, not a platform speed ranking, and not evidence
of acceptable battery/thermal or minimum-device behavior. Subsequent runs were faster.

Host process peak RSS reached 133,382,144 bytes (~127.2 MiB), including Python, all unpacked fixture
terrain, comparison buffers and retained allocator memory. This is not engine-only memory or a
mobile-app measurement. Each standard page alone holds int16 terrain plus byte signal/mask, roughly
5.76 MB before rasters/path buffers. Whole-degree pages allocate millions of cells even for a small
study. HD and multi-handle memory still need profiling.

The host probe schedules 16-radial chunks; the largest observed real-fixture call was 5.55 ms.
A separate absent-terrain probe stopped after one chunk and destroyed the handle in ~0.014 ms.
This validates cooperative stopping between calls on that host only. No mid-call cancellation API
exists; mobile cancellation latency, terrain-download cancellation and UI responsiveness remain
unmeasured. Full host execution with smaller chunks still exactly matched the golden fixtures.

## Concrete incompatibilities and required changes

1. **Missing terrain creates predictions.** A controlled 10 km London run with no pages loaded
   produced 58,990 computed cells. The native API documents unloaded pages as sea level. NodeScope
   requires explicit nodata/path validity; it must not present this as usable coverage.
2. **Model warning/error codes do not gate colored output.** The driver counts errnum but still
   stores received power. The host London fixture had 131,845 code-3 and 673 code-4 evaluations,
   with zero code-0 evaluations. The kernel's own comments describe code 3 and higher as probably
   invalid. These are legacy fixture inputs, not an assessment of every real-world configuration.
   Production requires per-cell reasons, model-domain handling and tested defaults, not only a
   global histogram. Paths below 1 km explicitly generate code 4 in the inspected kernel.
3. **Native reentrancy is not established.** driver.cpp has a global `g_engines` vector; the ITM
   routines contain writable static cached values. Browser workers isolate module instances; native
   threads do not. Serialize the entire interface initially or refactor kernel state with tests.
   A handle per worker alone is insufficient. No concurrent race test was performed.
4. **Lossy scientific output.** Coverage is encoded as clamped uint8 `200 + rounded dBm`; retaining
   floating path loss/RX and validity arrays requires changes, especially around classification
   boundaries and exact-node comparisons.
5. **Power and unit mapping need independent correction.** The wrapper feeds TX+gain−loss watts as
   ERP while the driver adds 2.14 dB in conversion to EIRP. Under our explicit dBi convention that
   must be reconciled. Receiver gain is not an engine input in this coverage interface. Historical
   TX feet behavior is intentionally reproduced by fixtures; application heights must be converted
   correctly. Test TX, RX gains/losses and watts/dBm rather than copying legacy mappings.
6. **Input validation is incomplete at the ABI.** A negative TX antenna height was accepted by
   splat_create. Add finite/range checks and typed errors before calculating; do not rely solely on
   frontend input constraints. NaN fuzzing/sanitizer coverage was not part of this bounded run.
7. **Terrain semantics and work size differ from our contract.** Audit the browser terrain decoder's
   below-zero/nodata handling, whole-degree page allocation and west-positive coordinates. Need
   versioned nodata-aware tiles, correct vertical datum, antimeridian tests, and bounded working sets.
8. **Useful point-to-point support exists**, but its functions are implemented beyond the public
   header's declarations. Expand a reviewed ABI and make path-detail calculations use the same
   model/terrain as the grid, with explicit receiver assumptions.

## Decision and next experiment

Do not integrate this driver into the app now. Native portability and upstream compatibility are
promising, but unchanged adoption fails NodeScope's missing-terrain/validity contract, and signal
parity, distribution permission and app-level resource limits are unresolved. A hardened fork could
work; it is not yet demonstrated to be less work than a smaller shared engine.

Next: run a similarly bounded kp1812 study on the same two runtimes, using its own published
reference fixtures first, then common terrain/radio scenarios. Verify distribution terms, actual
Swift packaging, supported domain, missing-data policy, native concurrency and per-path throughput.
Do not require different propagation models to reproduce identical signal values. If kp1812 passes,
build NodeScope's typed terrain/grid pipeline around it; keep this project as a workflow and
comparison reference. NTIA ITM remains the alternative if kp1812 fails a requirement.

Still required before any engine is selected: physical iOS/Android memory/energy/thermal and
cancellation measurements, cold terrain downloads, model-valid production scenarios, independent
reference validation, app wrappers/ABI lifecycle testing and distribution clearance.

## Reproducibility

See [probe instructions](../../tools/rf-study/README.md). Raw output is in
[feasibility/site-planner](feasibility/site-planner): host, iOS, Android, Android contraction-disabled,
boundary checks and environment metadata. Timings are illustrative single-run measurements.
