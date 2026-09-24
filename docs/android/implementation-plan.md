# Android implementation and acceptance plan

Proposal based on iOS `cd98f8e`, 2026-09-21. Functional scope is defined by the
[parity matrix](parity-matrix.md), presentation by the [experience plan](android-experience.md).

## Decisions and architecture

| Decision | Proposed choice / status | Reason |
|---|---|---|
| Target devices | Google Play devices are enough — confirmed by user | Google-free distribution is not a requirement |
| UI | Kotlin + Compose + Material 3 | Native Android controls with shared product behavior |
| Map | MapLibre Native + CARTO vector basemaps (2026-09-22) | User supplied a CARTO key; native rendering, clustering and route playback; satellite/hybrid provider remains open |
| Minimum OS | API 26 / Android 8.0 — proposed | Starting compatibility target, subject to dependency/device checks |
| Release target SDK | Current required level at release | Recheck at foundation and store submission |
| Navigation | Jetpack navigation with typed destinations and saved state | Back behavior, deep links and adaptive detail routes; choose stable API/version in Phase 1 |
| State | ViewModels, immutable UI state, StateFlow, lifecycle-aware collection | Separate rendering from request/socket/storage lifetimes |
| HTTP/live | OkHttp, Kotlin serialization, coroutines | One analyzer session, cancellable requests and one shared socket |
| Persistence | DataStore for preferences; Room for queryable saved entities and response cache metadata | Source-scoped storage, schema migration and testable clearing |
| Secrets | Android Keystore-protected encryption key wrapping local channel records | PSKs remain encrypted at rest; no keys in ordinary preferences, database plaintext or backups |
| Charts | Select a Compose-compatible library or small custom charts in Phase 5 | Prototype interaction, text scaling, accessibility and licensing before committing |
| Dependency injection | Explicit app container initially | Testable repositories without unnecessary module/framework overhead |
| Repository | Independent `android/` build; existing iOS paths remain | Avoid unrelated Xcode migration |
| Shared assets | Community registry, test fixtures, API documentation, icon/brand assets | Share contracts and data rather than introduce shared runtime code |

The storage split follows Android guidance that DataStore serves smaller settings
and Room suits complex datasets. Keystore protects key material; it is not itself
a general-purpose database of arbitrary channel records.
[DataStore guidance](https://developer.android.com/topic/libraries/architecture/datastore),
[Keystore documentation](https://developer.android.com/privacy-and-security/keystore).

The map provider was changed to CARTO with MapLibre Native on 2026-09-22.
The native MapView is hosted in Compose with lifecycle forwarding and saved camera
position. CARTO mobile restrictions use the app package and signing SHA-1 headers.
The key is local/build-environment configuration; no Google billing is required.
Standard/light/dark vector maps replace the initial Google map modes. Satellite
and hybrid remain unfulfilled parity requirements until a provider or explicit
scope change is agreed. Keep domain geometry independent of renderer types.
[MapLibre Native](https://maplibre.org/projects/native/),
[CARTO basemaps](https://docs.carto.com/faqs/carto-basemaps).

## Proposed source organization

```text
android/app/src/main/.../
  app/                       application container, navigation, analyzer session
  core/model/                transport DTOs and domain models
  core/network/              REST, shared socket, capability handling
  core/storage/              DataStore, Room, secure channel storage
  core/design/               Material theme, shared state components
  core/map/                  map adapter, geometry, route resolution
  feature/onboarding/
  feature/map/
  feature/explore/
  feature/nodes/
  feature/packets/
  feature/channels/
  feature/observers/
  feature/settings/
shared/fixtures/             sanitized protocol and crypto fixtures, added in Phase 0
```

Start with one app module and package boundaries. Split Gradle modules only if
build time or clear ownership boundaries justify them. Repositories own network
and cache operations. UI never directly opens sockets or reads private keys.
Domain calculations, route resolution and crypto parsing should be plain Kotlin
so they can be verified without an emulator.

## Cross-cutting implementation rules

- Model an analyzer session with normalized host and generation. Source switching
  cancels the old session; late results cannot overwrite the new one.
- Cache keys include source, endpoint, entity, region, range and filters as
  applicable. Keep user-owned favorites/recents/preferences separate from
  disposable downloaded data. Clearing cache cancels or invalidates pending writes.
- Preserve nullable/unknown fields, ISO timestamps, unknown enum fallbacks and
  reserved-character path encoding. Never convert missing measurements into zero.
- One bounded live stream fans out to map/feed/channels. Reconcile snapshots and
  live updates; distinguish packet identity from observation identity. Reconnect
  with bounded backoff; stop work while backgrounded. No foreground service or
  background alerting is required for parity.
- Port protocol crypto exactly, verified by Swift/Kotlin known-answer tests:
  SHA-256-derived 16-byte hashtag key; key-derived channel hash; ciphertext MAC;
  AES-128 ECB protocol decryption and payload parsing. Storage encryption is a
  separate design using authenticated encryption and a Keystore-held key.
- Keep private keys and generated channel secrets off logs, analytics, export
  metadata and network requests. Exclude encrypted records from backup/transfer;
  handle key loss with a clear re-import flow. User-requested key copy must be explicit.
- Do not promise entire-network search or unlimited channel history when the
  source endpoints are bounded. Preserve the observable iOS behavior and label
  known result limits.
- Existing source-less deep links use the selected analyzer. A future source-aware
  link format would be coordinated across both apps, outside initial parity.

## Delivery phases

Phases are dependency gates, not calendar estimates. Each feature is reviewed
against the iOS reference before the next phase is called complete.

| Phase | Work and dependencies | Exit criteria |
|---|---|---|
| 0 — Reference and feasibility | Capture sanitized API/socket fixtures, crypto vectors, iOS screen recordings; Android layout review; MapLibre/CARTO validation. No dependency on prototype architecture | Contract cases cover missing/partial data and reserved IDs; street/satellite/hybrid, marker clustering, tap selection and route animation demonstrated; provider setup/cost accepted; screen layouts reviewed |
| 1 — Foundation and shell | Replace/reuse prototype deliberately; theme, five destinations, source onboarding, registry fallback, session, region, storage, build/CI, basic link routing | Clean install/relaunch, rotate/process recreation, custom source validation, offline registry fallback, explicit appearance and rapid source-switch tests pass; fixtures usable without a live server |
| 2 — Map and node workflow | Map/config/region, node search and filters, clusters, location action, node detail/health/reach/paths, favorite persistence; requires 0–1 | Locate denial is safe; no false coordinates/routes; filter/camera restoration correct; optional section failures isolated; matching node can be found and inspected on both apps |
| 3 — Live packets and replay | Shared WebSocket, recent snapshot, route resolution/animation, feed/grouping/detail, packet links, replay; requires 2 | Fixture replay matches expected routes and observation counts; gaps/ambiguous hashes are handled; duplicate/reordered frames and reconnect do not multiply rows; background/foreground and replay-to-live work |
| 4 — Channels and observer browsing | Channel lists/conversations/filtering, local monitoring/key generation/import/decryption; observer list/filter/telemetry; requires 1 and 3 | Same known packets decrypt identically; invalid MAC/key rejected; hash names encoded correctly; keys absent from outgoing traffic/logs/backups; server vs local monitoring clear; observer filters match |
| 5 — Explore, analytics and utilities | Global search/history, favorite management/reordering, recents, dashboard customization, node/observer charts, exports, diagnostics, storage, help; requires 2–4 | Every matrix workflow available; metric/exports match fixtures; 404 analytics unavailable state; partial charts; clear cache preserves personal items and secrets; all link routes work cold/warm |
| 6 — Parity and release candidate | Device/accessibility/performance/offline compatibility pass, privacy/license review, final ID/signing/store setup; requires 1–5 | All matrix rows verified or explicitly accepted as deviations; emulator and physical device evidence; no release-blocking crashes, data mixing or secret exposure; signed build and release checklist complete |

## Test and review gates

| Layer | Required evidence |
|---|---|
| Unit/contract | JSON compatibility; timestamp and unknown enum handling; exact URL construction; route geometry/prefix collision behavior; deduplication; metric calculations; crypto known-answer/error vectors |
| Repository | Mock HTTP/socket tests for timeout/404/401/403/malformed responses, cancellation, retries, out-of-order results, source switching, cache freshness and clearing |
| Compose/navigation | Primary flows, filtering, back, deep links after onboarding, saved-state restoration, error/empty states, accessible labels |
| Device | Compact phone and expanded tablet/foldable; minimum supported and current Android; one representative midrange physical device; rotation/resizing, font scaling, TalkBack, location denied/approximate |
| Performance | Proposed stress fixture: 5,000 nodes and sustained/burst packet events; profile pan/zoom and route animation. Agree a representative packet rate during Phase 0; inspect frame timing, memory stability and background CPU rather than assume the prototype is adequate |
| Parity walkthrough | Same sanitized fixture/analyzer snapshot on iOS and Android; compare values, supported actions and outcomes, allowing platform-appropriate layout differences |
| CI | Compile, unit/contract tests and lint on Android changes; instrumentation for core journeys once emulator tests exist; retain reports and debug APK |

Acceptance is evidence-based: add status, linked tests/screenshots and any approved
deviation to each matrix row. Unit-test success alone does not establish parity.
Network-access tests must not depend on one public analyzer being up in CI.

## Prototype handling

The user subsequently authorized deleting the uncommitted prototype. Its app
implementation has been replaced against this plan; see [progress](progress.md). Reuse build plumbing, icon assets and useful tests
only after review. Reassess dependency versions, package name, native library
compatibility, CI permissions and backup behavior. The current node list is a
reference experiment, not a reason to reorder the product around Explore.

## Remaining review items

- Confirm minimum OS/API 26 and proposed branded Material appearance.
- Complete map feasibility and verify CARTO key restrictions/provider budget and decide satellite coverage.
- Review phone/expanded screen layouts before production UI work.
- Choose the publishing application ID and signing owner before first release.
- Record a baseline fixture set and performance device/event rate.

Only the map-specific integration depends on provider credentials. Protocol,
navigation, storage and fixture work can proceed independently after planning review.
