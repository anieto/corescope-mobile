# kp1812 feasibility for Candidate Sites

Study date: 2026-10-30. **Prefer kp1812 for the next bounded terrain/coverage prototype,
subject to distribution review and a validated wrapper. Do not ship the raw API.**
This completes the initial engine comparison, not Gate A or the Candidate Sites feature.

## Source and scope

Pinned [kp1812 source](https://github.com/meshtastic/kp1812/tree/a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9):
`a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9`. Production sources were left unchanged in
`/tmp/nodescope-kp1812-study`; our additional boundary test was copied into its commonTest tree.
No third-party source, library binary or dependency was added to either app.

The kernel takes one terrain path and returns floating-point losses. It does not acquire terrain,
generate coverage rasters, manage cancellation, persist candidates or draw maps. Its common Kotlin
sources provide one implementation for JVM and Kotlin/Native. The examined calculation state is
local to each call; unlike the earlier Site Planner driver, no mutable global engine registry was
found. This is a source-review finding, not a concurrency stress-test result. TerrainPath retains
mutable arrays supplied by its caller: the integration must own/copy them and prohibit mutation
while a job is using them.

## Measured checks

| Check | Result |
|---|---|
| JVM upstream reference suite | 14 methods passed, including 63 end-to-end cases |
| macOS arm64 upstream reference suite | Same 14 methods passed |
| iOS arm64 simulator upstream reference suite | Same 14 methods passed |
| Android Pixel 10 arm64/API 37.2 emulator | Same 14 methods passed through D8 + app_process/JUnit |
| iOS device and simulator release frameworks | Both built |
| Swift consumer on iPhone 18 Pro/iOS 27 simulator | Compiled, linked and called the static framework successfully |
| Existing Android Kotlin 2.2.20 consumer of prebuilt JVM jar | Failed: incompatible Kotlin 2.4 metadata |
| Unchanged source rebuilt with Kotlin 2.2.20 / Java 17 | Consumer compiled; 14 reference methods and our boundary probe passed on JVM |

All four runtime reference runs reported maximum absolute basic-loss error
`3.289329697508947e-9 dB`, against upstream's committed Py1812-derived fixtures. The end-to-end
assertion tolerance is `1e-6 dB`; component tests have their own tolerances. Equal maxima do not
prove bit-identical arrays, but the reference bounds are far tighter than the proposed 0.01 dB
cross-platform tolerance. This is numerical conformance, **not measured outdoor RF accuracy**.
The fixtures were not regenerated or independently recaptured during this study.

Android execution used build-tools 36.0.0 D8 with min-api 26 and Kotlin stdlib 2.4.20.
It establishes that the dexed computation executes on this emulator, not that our current app can
consume the prebuilt artifact. The upstream build uses Kotlin 2.4.20, Gradle 9.8.2 and JDK 21;
NodeScope currently uses Kotlin 2.2.20 and JVM target 17. The separate consumer probe deliberately
matched those app settings and exposed the metadata incompatibility. Do not bypass metadata checks.
As a follow-up, copying the unchanged three commonMain source files and commonTest fixtures into
the temporary consumer and building with Kotlin 2.2.20/Java 17 succeeded. This provides a candidate
compatible build route without an app-wide compiler upgrade. A reproducible maintained artifact,
its Kotlin/Native counterpart and actual APK packaging still need qualification; this source
rebuild was tested on host JVM, not rerun as a packaged Android app.

The Swift smoke test executed 10,000 calls on one synthetic, flat 10 km, 101-point profile in
0.149 seconds, returning 137.09304306564067 dB. That loop reuses already-created arrays: it excludes
terrain I/O, sampling, cell construction, map work and device thermal conditions. It is not a
coverage-job benchmark or a speed comparison with Site Planner. Static framework binary sizes
were 1,423,288 bytes (device) and 1,405,528 bytes (simulator), before app linking; these are not
installed app size increases. Peak memory, cancellation latency and physical-device performance
remain unmeasured.

## Validation and bridging requirements

Our seven-case boundary probe ran on JVM and macOS Native with matching behavior:

| Input | Observed behavior |
|---|---|
| Valid 3-point flat profile | Finite loss |
| Negative transmitter height | NaN, no rejection |
| NaN terrain sample | NaN, no rejection |
| Unknown zone 99 | Finite loss, no rejection |
| Negative clutter height | Finite loss, no rejection |
| Latitude 1000 degrees | Finite loss, no rejection |
| Refractivity gradient 157 | NaN, no rejection |

Upstream checks array lengths, ascending distance from zero, frequency, percentages and
nonnegative location variability. That is insufficient for our input contract. Require a single
shared validation boundary with typed outcomes before calling the kernel:

- Validate finite values, geographic/model bounds, profile spacing, supported zone IDs,
  nonnegative clutter, atmospheric inputs and antenna heights. Negative terrain elevation is
  valid below sea level and must not be confused with negative height above ground.
- Treat missing terrain as Unknown before calculation; never zero-fill it. Reject nonfinite
  outputs and retain the reason, instead of coloring a cell or serializing NaN.
- Audit the full model domain against [ITU-R P.1812-8](https://www.itu.int/rec/R-REC-P.1812-8-202509-I/en).
  Its documented range starts at approximately 250 m, with antenna heights 1–3000 m. Mask nearer
  cells unless a separately validated short-range model is explicitly adopted. The raw API does
  not enforce these limits.
- Swift's generated API has no NSError/throwing result for Kotlin `require` failures. Add a Kotlin
  wrapper that catches expected validation failures and exports typed success/error results;
  do not depend on Swift `do/catch` around the raw API. Test failure cases through the bridge.
- Batch bounded work off the UI thread; check cancellation between calls/batches. There is no
  asynchronous cancellation API inside a prediction. Limit sample counts as well as cell counts.
- Apply transmit power, antenna gains and feed losses in explicitly named units outside basic
  transmission loss. Use the same kernel and terrain for each known-node link explanation.

## Distribution and environmental assumptions

The inspected source headers specify GPL-3.0-or-later. No additional App Store permission was
found in the inspected license/header material. NodeScope's own App Store exception does not
automatically grant permissions for kp1812. Distribution suitability remains unresolved;
this engineering study neither approves distribution nor declares it prohibited. Review the
exact source/attribution and any necessary permissions before importing a production dependency.
No upstream authors were contacted.

Atmospheric maps are not bundled. Upstream supplies median defaults for refractivity inputs;
these must be recorded as assumptions rather than local measured weather. Coast distances,
land/sea zones and clutter must come from documented inputs or visibly labeled approximations.
Zero clutter everywhere is a terrain-only scenario, not modeled building/tree attenuation.
The [upstream README](https://github.com/meshtastic/kp1812/blob/a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9/README.md)
describes the fixture provenance and omitted atmospheric maps. Published package/release artifacts
were not qualified; this study built the pinned checkout.

## Decision and next deliverable

Prefer this shared Kotlin kernel over funding an immediate Site Planner fork. The earlier study
found a stateful legacy driver, quantized results, terrain-validity problems and Android differences
up to 5 dB. kp1812 has much stronger measured reference agreement and a usable native Swift bridge.
Its missing validation is real, but fits a bounded wrapper rather than a rewrite of propagation math.
This is an integration-risk comparison, not evidence that P.1812 is more accurate than ITM outdoors.

Follow-up completed: the [real-terrain prototype](coverage-prototype.md) runs the shared wrapper on
both mobile runtimes with matched scientific outputs, missing-data masks and cancellation checks.
Its report records a sampling defect found/fixed and the remaining production gates.

The follow-up scope was one isolated end-to-end coverage run over a small real terrain tile on both
platforms, using a validated shared wrapper, explicit terrain/atmosphere/clutter provenance,
missing-data and near-site masks, cancellation and exact known-node paths. Compare unrounded
losses, measure total latency/memory, and check the proposed cell/sample budget. Select a terrain
provider and resolve distribution before moving that prototype into production app targets.
Do not expand into Candidate Sites UI until those gates pass; keep Saved Link Profiles deferred.

Evidence: [reference and boundary results](feasibility/kp1812/reference-tests.json),
[Android runtime result](feasibility/kp1812/android-emulator.json),
[Swift consumer result](feasibility/kp1812/swift-consumer.txt), and
[prebuilt consumer failure](feasibility/kp1812/android-consumer-prebuilt.log).
[Compatible source-build results](feasibility/kp1812/compatible-source-tests.json) record the fallback.
Reproduction instructions and our probes are in [tools/rf-study](../../tools/rf-study/README.md).
