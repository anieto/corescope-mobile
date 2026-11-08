# NodeScope RF kernel

The unchanged kp1812 common sources and reference fixtures are vendored at
`a911b6ecd58f8c16ac8ae4becf6bdbba1784e5a9`. `vendor/kp1812/manifest.json` records SHA-256
for every source/fixture/license file. Run `python3 shared/rf-engine/verify_sources.py` from
the repository root to check them. The model is GPL-3.0-or-later, copyright Meshtastic LLC.
Upstream distribution permission is assumed granted per the project owner's instruction;
no independent permission verification is claimed. License/source attribution ships in both apps.

NodeScope's separate `CoverageKernel` wrapper validates finite values, array sizes/order,
250 m–100 km paths, 30–6000 MHz, 1–3000 m antennas and supported latitude/elevation bounds.
It owns copied arrays and returns typed failures for expected invalid input/model results.
The per-call profile cap is 100,001 points; larger profiles fail explicitly, never truncate.
It does not catch fatal runtime failures such as out-of-memory errors.

The scenario is explicitly inland, zero clutter, vertical polarization, median atmosphere
(deltaN 45, N0 325), 500 km coast distances, 50% time/location and zero location variability.
These are assumptions, not measured site conditions. Apps must retain/display the scenario and
source revision with results. Callers supply the geodesic midpoint latitude and check cancellation
before/after synchronous calls. This wrapper does not provide a worker or interrupt the kernel.

## Build from a clean checkout

Android compiles these sources directly with its existing Kotlin 2.2.20 / Java 17 configuration.
It also runs the unchanged upstream tests plus the shared boundary tests. No incompatible upstream
JVM binary or compiler metadata bypass is used.

Before opening/building the iOS app, generate its local static XCFramework on an Apple Silicon Mac:

```sh
cd shared/rf-engine
./gradlew macosArm64Test assembleNodeScopeRFReleaseXCFramework --console=plain
```

The checked-in Gradle wrapper pins Gradle 9.8.2 with a distribution checksum. Apple builds use
Kotlin 2.4.20, matching the qualified study. The generated framework contains iOS arm64,
iOS simulator arm64 and macOS arm64 slices. Xcode links the local generated framework;
binaries and toolchain caches are not committed. Rerun the command after shared source changes.
CI must run it before Xcode. This adds Java/Gradle/Kotlin Native bootstrap requirements to iOS.

Android checks, from `android/`:

```sh
./gradlew :app:testDebugUnitTest --tests 'org.meshtastic.kp1812.*' --tests 'org.nodescope.rf.*' :app:assembleDebug
```

Both reference suites contain 14 methods, including 63 end-to-end fixtures. Three additional
wrapper methods check unsafe input, unit conversion/scenario parity and below-sea-level terrain.
Host execution and app builds do not establish physical-device performance or outdoor accuracy.
Prepared studies now default to the production adapter. Native terrain profiles carry the
geodesic midpoint latitude, and published results retain the model revision and scenario alongside
terrain sources. Injected test predictors receive no production-model record. Scenario disclosure,
screen execution and Calculate remain pending.

Swift bridge smoke check (from the repository root after generating the framework):

```sh
xcrun swiftc shared/rf-engine/bridge_probe.swift -module-cache-path /tmp/nodescope-rf-module-cache -F shared/rf-engine/build/bin/macosArm64/releaseFramework -framework NodeScopeRF -o /tmp/nodescope-rf-bridge-probe
/tmp/nodescope-rf-bridge-probe
```
