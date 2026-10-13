# Beacon phase 3 tasks

Goal: fill the remaining Beacon-specific capability gaps in NodeScope on iOS and Android: neighbor
insight, node analytics, observer activity and telemetry, server-decrypted channels, and expanded
diagnostics. CoreScope behavior must remain unchanged.

Phase 3 does **not** include monitored channels decrypted on the phone. Beacon exposes `rawPayload`
only through one packet-detail request per packet, which cannot meet the request budget. That feature
remains blocked on a list-level payload or channel-hash filter from Beacon.

Read first, in this order. Where they disagree, the later document wins.

1. [plan.md](plan.md): architecture and feature mapping
2. [phase0-findings.md](phase0-findings.md): binding identity, paging, budget, and channel rules
3. [phase2-implementation.md](phase2-implementation.md): the backend and UI foundation now in production
4. This document: binding Phase 3 scope and acceptance criteria

Status key: `todo` · `doing` · `review` · `done`. Update the status column as work lands.

---

## Product and UI parity rule

The CoreScope experience is the reference design for every Phase 3 feature. Beacon implementations
must reuse the same screens, navigation, cards, chart types, ordering, labels, loading behavior,
empty states, accessibility behavior, and interaction patterns as closely as the available data
allows.

A Beacon screen must not become a separate or visibly second-class design merely because its data
comes from different endpoints. Make a concession only when matching CoreScope would be inaccurate,
unsafe, too expensive for the measured request budget, or impossible with Beacon's API. Every
concession must:

- be recorded in this plan and `phase3-implementation.md` with the exact API or semantic limitation;
- preserve unknown versus zero, incomplete versus empty, and unsupported versus unavailable;
- use concise explanatory copy instead of silently omitting or inventing data;
- leave the equivalent CoreScope UI and requests unchanged; and
- be implemented consistently on iOS and Android unless a platform convention requires otherwise.

Do not rename a shared concept just because Beacon uses different wire terminology. Translate at the
backend boundary and keep the established NodeScope/CoreScope presentation wherever the semantics
match. Beacon-only telemetry may add cards after the shared CoreScope-equivalent content; it must not
rearrange the common content without a documented reason.

## Working rules

- **Branch:** integrate on `feature/beacon-phase-3`. Use milestone branches when a change is large
  enough to review independently, especially channels.
- **Platform order:** settle each contract and UI on iOS, port it to Android immediately, then verify
  parity before beginning the next large feature. Do not defer the entire Android port to the end.
- **Commits:** stage explicit paths; preserve unrelated edits; no AI attribution lines.
- **CoreScope regression:** existing CoreScope requests, response projections, screen structure, and
  behavior remain pinned by tests. Shared UI refactors require before/after CoreScope verification.
- **Fixtures:** capture new endpoints from supported Beacon 2.0.3 and 2.0.5 servers, sanitize with
  `make_fixtures.py`, and require `check_leaks.py` to report zero before committing them. Swift and
  Kotlin tests use the same fixtures.
- **Live checks:** use `beacon.meshtexas.org`, `map.meshcore.coloradomesh.org`, and `dev.meshcore.ca`.
  Respect 300 requests/minute and shared-IP throttling.
- **Budget:** target no more than 20 requests when opening any Phase 3 screen. Never fetch packet
  detail per list row. Record requests, bytes, time to first content, and whether displayed history is
  complete.
- **History:** Beacon analytics cover at most seven days unless a verified aggregate endpoint says
  otherwise. Match CoreScope time controls where valid; cap or relabel unavailable ranges rather than
  returning misleading empty charts.
- **Source lifecycle:** all caches, identities, requests, and persisted references remain scoped by
  source/backend and reject late results from an obsolete source session.
- **Accessibility:** preserve Dynamic Type, VoiceOver/TalkBack labels, contrast, Reduce Motion, and
  platform navigation behavior when matching the CoreScope UI.

---

## Milestone A: Phase 3 contracts and budgets

No product UI changes. This milestone gates implementation.

| ID | Task | Done when | Status |
|---|---|---|---|
| A1 | **Capture contracts.** Record and sanitize representative responses for node observations, neighbors, observer activity, observer telemetry, decryptable channel pages, channel messages, relevant errors, empty histories, and paging cursors from Beacon 2.0.3 and 2.0.5. | Both versions decode from shared fixtures; leak scan reports zero; differences are documented | done |
| A2 | **Measure budgets.** Measure request count, bytes, response time, paging depth, time ranges, and maximum practical row counts for every Phase 3 endpoint on all three live servers. | A table in `phase3-implementation.md` defines hard per-screen ceilings and cache lifetimes; no proposed screen exceeds about 20 requests | doing |
| A3 | **Map semantics to the current UI.** Inventory every field/card/chart on the CoreScope node analytics, observer analytics, reach/neighbors, channels, messages, and diagnostics screens. Map each to Beacon as direct, derived, unavailable, or Beacon-only. | A parity matrix names the mapper and UI behavior for every existing element; every proposed concession has a reason | doing |
| A4 | **Pin channel identity.** Confirm `(source session identity, Beacon integer id)` as the primary identity, with stored name and `isHashtag`. On 404 after a database reset, exact-name rematching is allowed only for hashtag channels and `Public`; all other references are dropped with an explanation. Never match by hash or across sources. | Collision, rename, reset, 404, paging, and cross-source tests are specified before channel implementation starts | todo |
| A5 | **Confirm analytics derivations.** Define formulas and units for activity, packet types, hop counts, SNR, heatmaps, neighbor strength, telemetry summaries, and missing samples. Distinguish packets from observations. | The same fixture produces identical neutral analytics values in Swift and Kotlin tests | doing |

## Milestone B: neutral backend contracts

Add only the shared semantics needed by Phase 3. CoreScope adapters must continue producing their
current values and requests.

| ID | Task | Done when | Status |
|---|---|---|---|
| B1 | **Neutral analytics models.** Represent bounded time ranges, completeness, optional series, packet-versus-observation counts, unknown values, and provenance for derived Beacon analytics. | CoreScope decoding is unchanged; synthetic tests cover empty, partial, unsupported, and seven-day-capped histories | done |
| B2 | **Neighbor model.** Add neutral neighbor identity, last-heard time, signal values, directionality, and completeness without treating key prefixes as full identities. | CoreScope reach data maps without visible change; Beacon full keys are linkable and prefixes are display-only | done |
| B3 | **Observer telemetry model.** Add optional battery, noise floor, airtime, activity, and sample-time fields with explicit units and missing-value behavior. | Unit conversions and missing/null semantics are fixture-tested on both platforms | todo |
| B4 | **Source-scoped channel model.** Replace assumptions that channel hash or name is globally unique. Persist a source-scoped reference while preserving CoreScope favorites, recents, monitored channels, and links. | Upgrade tests preserve existing CoreScope data; collisions and identical names never merge Beacon channels | todo |
| B5 | **Capabilities.** Split broad Phase 2 gates into the Phase 3 capabilities required for neighbors, node analytics, observer activity, observer telemetry, server channels, and channel messages. | Each screen can independently distinguish supported, empty, partial, and unavailable; CoreScope still advertises its current capabilities | todo |

## Milestone C: neighbors and reach parity

| ID | Task | Done when | Status |
|---|---|---|---|
| C1 | Implement Beacon `/nodes/{id}/neighbors` behind the backend, including caching, cancellation, source-session rejection, errors, and identity safety. | Fixture and request tests pass; opening detail stays within the Phase 3 budget | doing |
| C2 | Populate the existing CoreScope reach/neighbors area with Beacon data using the same card, row ordering, navigation, signal formatting, loading state, and empty state wherever semantics match. | Side-by-side review shows the same UI structure; every absent CoreScope field has documented explanatory behavior | todo |
| C3 | Port C1–C2 to Android and update the parity matrix. | iOS and Android show equivalent values and interactions from the same fixture | todo |

## Milestone D: node analytics parity

| ID | Task | Done when | Status |
|---|---|---|---|
| D1 | Read paged `/nodes/{id}/observations` and compute the neutral analytics defined in A5 without packet-detail fan-out. Cache by source, node, filter, and time range. | Paging, cancellation, repeated cursor, partial failure, seven-day cap, and stale-session tests pass | todo |
| D2 | Fill the existing node analytics screen with Beacon activity, heatmap, packet-type, hop, and SNR data. Reuse CoreScope charts, cards, order, colors, legends, filters, and accessibility labels. | CoreScope and Beacon screenshots have the same information architecture; differences correspond only to documented data limitations | todo |
| D3 | Handle analytics that would require per-packet detail, including per-observer coverage. Prefer an explicit unavailable/partial explanation over an expensive approximation. | Zero per-row packet-detail requests; completeness is visible and tested | todo |
| D4 | Port D1–D3 to Android and update the parity matrix. | Shared fixtures produce matching aggregates and equivalent UI states on both platforms | todo |

## Milestone E: observer activity and telemetry parity

| ID | Task | Done when | Status |
|---|---|---|---|
| E1 | Implement `/observers/{id}/activity` and telemetry endpoints behind the backend using Beacon UUID identity and the Phase 2 learned-key rules. | No observer-detail fan-out; paging/range/errors/cache/source switching are tested | todo |
| E2 | Populate the existing observer analytics screen with the closest Beacon equivalents, preserving the CoreScope layout, card order, charts, time controls, and navigation. | Shared sections visually match CoreScope; unsupported metrics are explained instead of shown as zero | todo |
| E3 | Add genuinely Beacon-only telemetry—such as battery, noise floor, and airtime—after the shared sections using established NodeScope card and chart components. | Units, timestamps, sparse samples, and empty telemetry have reviewed copy and accessibility labels | todo |
| E4 | Port E1–E3 to Android and update the parity matrix. | Both platforms show equivalent values and platform-appropriate but structurally matching UI | todo |

## Milestone F: server-decrypted channels

This milestone covers channels whose keys are known by the Beacon server. It does not enable locally
monitored/on-phone decryption.

| ID | Task | Done when | Status |
|---|---|---|---|
| F1 | Implement paged `/channels?keyKnown=true` and `/channels/{id}/messages` operations, cursor handling, caching, errors, source sessions, and request budgets. | Fixtures cover multiple pages, empty channels, collisions, duplicate names, renamed channels, reset IDs, and 404s | todo |
| F2 | Apply the A4 identity and recovery rules to favorites, recents, navigation, and persisted references. Keep CoreScope migration behavior unchanged. | No hash-only lookup exists; allowed exact-name recovery is tested; dropped references produce recoverable user-facing state | todo |
| F3 | Reuse the existing channels list, channel detail, message rows, timestamps, loading states, refresh behavior, navigation, and empty states. Hide or explain only actions that require local keys or payloads Beacon does not expose. | CoreScope and Beacon channel screens match visually and behaviorally wherever the server data permits | todo |
| F4 | Keep monitored channels capability-gated on Beacon with clear copy explaining that server-decrypted channels are available but on-phone monitored channels are not. | The two concepts cannot be confused in labels, navigation, deep links, or accessibility output | todo |
| F5 | Port F1–F4 to Android and update the parity matrix. | Collision, reset, migration, and UI-parity tests pass on both platforms | todo |

## Milestone G: expanded diagnostics

| ID | Task | Done when | Status |
|---|---|---|---|
| G1 | Add bounded, non-destructive probes for the Phase 3 capabilities and report availability, compatibility, throttling, and partial-data conditions through the backend. | Diagnostics do not fetch bulk histories or mutate server state; CoreScope probes remain unchanged | todo |
| G2 | Extend the existing diagnostics UI using the same sections and status rows as CoreScope. Add Beacon-only facts only where they help explain capability or health. | Both platforms present the same diagnostic conclusions with no false failures for unsupported features | todo |
| G3 | Port and verify on Android. | iOS and Android reports agree against all three live servers | todo |

## Milestone H: cross-platform release gate

| ID | Task | Done when | Status |
|---|---|---|---|
| H1 | Automated regression and fixture suites pass on both platforms, including all existing Phase 2 CoreScope and Beacon tests. | Zero failures; CoreScope request-contract expectations remain unchanged | todo |
| H2 | The user completes side-by-side CoreScope/Beacon UI review for every Phase 3 screen on iOS and Android, including Dynamic Type and dark mode. | UI concessions are accepted and recorded; no undocumented divergence remains | todo |
| H3 | Live acceptance passes on MeshTexas Beacon, Colorado, and dev.meshcore.ca, including empty/sparse states, throttling, source switching, and histories near the seven-day boundary. | No screen exceeds its request budget or presents partial history as complete | todo |
| H4 | Channel upgrade and rollback drills pass: old CoreScope state survives, a Beacon database reset is recoverable, a channel can be delisted without cross-source matching, and users can return to CoreScope. | Persistence and rollback outcomes are documented on both platforms | todo |
| H5 | Update `phase3-implementation.md`, the Android parity matrix, store text, screenshots, review notes, version/build numbers, and beta checklist. | TestFlight and Play beta builds are ready for the user’s smoke test | todo |

---

## Suggested implementation order

1. A1–A5: contracts, budgets, UI mapping, and binding identity decisions.
2. B1–B5: neutral models and capabilities.
3. C1–C3: neighbors, iOS then Android.
4. D1–D4: node analytics, iOS then Android.
5. E1–E4: observer activity and telemetry, iOS then Android.
6. F1–F5: server-decrypted channels on a dedicated milestone branch, iOS then Android.
7. G1–G3: expanded diagnostics.
8. H1–H5: regression, side-by-side UI acceptance, live checks, and beta release gate.

After each milestone, add a dated section to `phase3-implementation.md` covering what changed,
measured budgets, UI-parity decisions, unavoidable concessions, test results, and remaining work.
