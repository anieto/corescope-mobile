# Feature-preserving whole-map scheduler

Isolated follow-up to [feature profiles](../feature-profile/README.md). It reuses the feature
builder and bounded local terrain cache, adding a scheduler that accounts for both geometry and
sampling work. No production app code or earlier evidence is changed.

## Contracts

- Preflight validates grid bounds, at most 60,000 rectangular cells, the existing uniform-pass
  sampling lower bound, and at least four geometry coordinate evaluations per eligible receiver.
  These are necessary lower bounds, not a prediction that the job will finish. Feature discovery
  and retained points make actual work larger; conservative upper bounds use per-path caps.
- Each path receives the smaller of the remaining job allowance and its per-path allowance for
  **each** counter. Normal runs allow 150 million sampling positions and 150 million geometry
  evaluations per job; paths retain the candidate's 250,000 / 200,000 caps.
- Work is charged even if cancellation discards an in-flight result. If a failed path was
  constrained by either remaining job allowance, the scheduler stops conservatively. This can
  stop sooner than a hypothetical retry with another allocation policy. It never silently
  coarsens profiles, retries without features or publishes a diagnostic estimate.
- `WORK_LIMIT`, `CANCELLED` and `COMPLETE` jobs cannot resume. Unstarted cells stay `NOT_COMPUTED`;
  all nonqualified cells have no loss. Cancellation may leave charged work absent from output rows.
- `FeaturePublicationGate` rejects captured generations that have been superseded and refuses
  cancelled jobs. Its owner must serialize access; this is a coordinator contract/test, not a
  thread-safe app view model. Eventual app callbacks must carry their captured generation.
- Existing terrain-cache payload accounting remains unchanged. Sampling/geometry counters do
  not establish wall-clock, physical memory, I/O, energy or UI-frame limits.

## Reproduce

Prepare the pinned engine and terrain described in the [wide study](../wide/README.md), and start
the mobile runtimes listed in the [bounded study](../bounded/README.md). Run sequentially:

```sh
python3 tools/rf-study/coverage/feature-grid/prepare_build.py /tmp/nodescope-kp1812-study /tmp/nodescope-coverage-feature-grid
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-feature-grid/jvm test jar --console=plain
/tmp/nodescope-kp1812-study/gradlew -p /tmp/nodescope-coverage-feature-grid/native linkReleaseFrameworkIosSimulatorArm64 linkReleaseFrameworkIosArm64 --console=plain
python3 tools/rf-study/coverage/feature-grid/run_android.py /tmp/nodescope-coverage-feature-grid /tmp/nodescope-coverage-wide/data/terrain.bin emulator-5554 /tmp/nodescope-coverage-feature-grid/results/android
xcrun --sdk iphonesimulator swiftc -O -module-cache-path /tmp/nodescope-coverage-swift-cache -target arm64-apple-ios17.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F /tmp/nodescope-coverage-feature-grid/native/build/bin/iosSimulatorArm64/releaseFramework -framework CoverageStudy /tmp/nodescope-coverage-feature-grid/FeatureGridRunner.swift -o /tmp/nodescope-coverage-feature-grid/grid-ios
xcrun simctl spawn SIMULATOR_UUID /tmp/nodescope-coverage-feature-grid/grid-ios /tmp/nodescope-coverage-wide/data/terrain.bin /tmp/nodescope-coverage-feature-grid/results/ios
python3 tools/rf-study/coverage/feature-grid/analyze.py /tmp/nodescope-coverage-feature-grid/results docs/node-planner/feasibility/feature-profile docs/node-planner/feasibility/feature-grid
python3 tools/rf-study/coverage/feature-grid/preview.py /tmp/nodescope-coverage-feature-grid/results/android /tmp/nodescope-coverage-wide/data/terrain.bin docs/node-planner/feasibility/feature-grid
swift -module-cache-path /tmp/nodescope-swift-preview-cache tools/rf-study/coverage/feature-grid/render_preview.swift docs/node-planner/feasibility/feature-grid/coverage-preview.svg docs/node-planner/feasibility/feature-grid/coverage-preview.png
```

Eight scenarios cover a 20 km / 1 km scan, 100 m selected refinement, 200 m refinement at two cache
sizes, a coarse 100 km workload fixture, separate sample/geometry exhaustion, and an independently
computed larger 100 m grid. Six new unit tests cover preflight, both limits, charged cancellation,
terminal states and stale publication. Drivers also test live cancellation and stale publication.

The analyzer requires exact within-platform cache/refinement matches, platform states and work
counts, losses within 0.01 dB, and agreement with 441 earlier single-path feature results. This
validates scheduler integration, not a new independent propagation model. Prior sampling-accuracy
limitations still apply.

The self-contained HTML/SVG preview uses Android output over relief from the same DEM. It makes no
network requests. Its opacity slider changes display only; it never recomputes predictions. Unknown
cells are hatched; a dashed circle marks the study boundary. PNG is a convenience rendering of the
SVG. Terrain relief is not a street basemap or an app-screen design.
