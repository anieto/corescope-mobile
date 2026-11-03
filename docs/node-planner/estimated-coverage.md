# Estimated Coverage — standalone tool and saved planning work

Decision: 2026-11-02. Platforms: iOS and Android. Approved product direction; not yet implemented.
This document supersedes the saved-sites-first release direction and conflicting navigation,
persistence, and delivery-order language in earlier Node Planner plans. Existing RF/terrain
validation requirements still apply. Parent: [Node Planner](plan.md).

## Product structure

Explore → Node Planner presents two equally prominent tools: **Link Profile** and
**Estimated Coverage**. **Saved Plans** is the secondary collection for saved work from either tool.
Candidate sites are proposed installation locations within those plans, not a prerequisite for
running an analysis. Do not require naming, creating or saving a plan before using either tool.

Link Profile answers whether two points might communicate. Estimated Coverage answers what area
one proposed transmitter might serve, and which known nearby nodes meet the predicted reception
threshold. Saving either study is optional. The existing Candidate Sites editor/storage is a
foundation to adapt, not the definition of the primary workflow. Pause further standalone
Candidate Sites polish while completing the coverage tool.

## First complete workflow

1. Open Estimated Coverage directly. Display a map and available known-node markers immediately;
   no terrain download or simulation starts merely by opening the screen.
2. Select one transmitter point by confirmed map placement, manual coordinates, or an explicit
   Use current location action. Reuse the existing picker, unit and permission behavior.
3. Enter transmitter radio/frequency, power, antenna height/gain and cable/system losses. Enter
   the generic receiving antenna height/gain/loss, receiver sensitivity and optional planning
   reserve. Show reviewed preset values explicitly; unknown equipment is not silently zero.
4. Choose analysis radius and detail. Preflight shows terrain download and computation estimates,
   supported limits and actual output spacing. No fixed 200 m grid or unlimited radius promise.
5. Calculate explicitly. Show terrain preparation, coverage and known-node evaluation progress,
   with cancellation and clear missing-data errors. No all-green/all-red provisional result.
6. Display an irregular terrain-aware coverage overlay with known nodes above it. Radius is the
   study boundary, not a predicted circular footprint. Legend names the receiver assumptions,
   threshold, calculation time and prediction status. No guarantee of real packet reception.
7. Tap a node for predicted status, receive level, adjusted margin, distance, last-seen context,
   input assumptions and the reason for an unknown result. Provide an accessible list equivalent.
   Path inspection must use the coverage run's own model and terrain, not silently substitute the
   current Link Profile engine. A separate Open in Link Profile action can prefill the two points,
   but must identify it as a separate calculation with its own assumptions/model.
8. Optionally Save to Saved Plans. Leaving an unsaved calculation allows explicit discard;
   saving is never required to use or leave the tool.

## Known-node colors and meaning

Use the active source's known-node catalog with valid coordinates, source/filter metadata,
last-seen timestamps and completeness. Keep node discovery separate from predicted reception.
Capture node coordinates and assumptions at run start; pan/zoom must not change the evaluated set.
Display available nearby nodes before a run, including context outside the study radius when
available. Bound/paginate catalog work and disclose truncation; never claim all nodes are known.

| State | Marker | Meaning |
|---|---|---|
| Predicted in coverage | Green + check/status label | Valid exact-coordinate prediction with adjusted margin >= 0 dB under the displayed receiver assumptions |
| Predicted below threshold | Red + cross/status label | Valid exact-coordinate prediction with adjusted margin < 0 dB |
| Unknown | Neutral + question mark | Missing terrain, unsupported model path, unresolved computation, or required assumptions absent |
| Not evaluated | Neutral + distinct label | Before calculation, beyond the study radius, new/changed node since the snapshot, or excluded by a disclosed processing cap |
| Stale | Muted + stale indicator | Result belongs to prior transmitter/radio/radius/receiver inputs; rerun required |

Color must be reinforced by symbols, text, legend and VoiceOver/TalkBack descriptions, with
light/dark readable markers distinct from selection, node type and observed-network status.
Use clustering that preserves status counts; do not color a mixed cluster as a single green node.

RX = TX power + TX gain + RX gain − TX loss − RX loss − modeled path loss.
Adjusted margin = RX − receiver sensitivity − optional planning reserve. Zero is included in
green; classify unrounded values. These are threshold predictions, not reliability probabilities.
Red means below threshold within the evaluated area, not proof that a node is unreachable.
A node outside the analysis radius is not evaluated and must not become red simply because the
map overlay ends there. Similarly, transparent missing data is not below-threshold coverage.

Initially use the same explicitly displayed generic receiver assumptions for both the footprint
and known-node predictions, labeled as assumed rather than known node equipment. Evaluate nodes
at their exact coordinates, not by sampling the displayed heatmap pixel. Near terrain edges a
node can differ from the coarse cell; detail explains the exact-path result and output resolution.
Per-node receiver overrides are later work, unless clearly disclosed in the UI and result.
Forward reception does not establish reverse reception, compatible modem/channel, online status,
routing, or successful delivery. Show these limits alongside node details.

A refreshed catalog never silently reuses a prediction for moved nodes. Explicit refresh/rerun
updates the snapshot; new or moved nodes are neutral until evaluated. Zero known nodes must not
block the area calculation. Filters affect visibility without reclassifying stored results.

## Saving and reopening

Target library name: **Saved Plans**. Store typed Link Profile and Estimated Coverage study records;
optionally group them in a named deployment plan with named candidate locations. A saved Link
Profile captures both endpoint coordinates and endpoint assumptions; a coverage study captures
one transmitter, receiver assumptions, radius/detail and the known-node snapshot used by the run.
An A/B profile is not squeezed into a single CandidateSite record.

Reopen in the original tool with the saved input snapshot, without fetching terrain or calculating
automatically. If results are cached, retain engine/version, terrain provenance, inputs fingerprint,
calculation date and node snapshot. Clearly distinguish historical, stale and missing results.
Saved studies remain usable if a remote node disappears or changes location. Results may be
recomputed explicitly; deleting terrain cache must not delete saved inputs.

The implemented version-1 site-only library remains readable and untouched until a tested migration
exists. Design a versioned typed-study schema, shared fixtures and atomic migration/recovery before
changing stored data. Preserve site IDs, names and explicit/unknown assumptions; do not invent
missing receiver data or fabricate analyses for old records. Unsupported versions/corrupt files
remain protected. One shared library replaces the deferred separate Saved Link Profiles proposal.

## Delivery order and acceptance

1. Freeze this product/result contract and revise roadmap/docs (this change).
2. Resume the bounded terrain/engine integration: native download/cache ownership, tiled mosaic
   and sampler integration, pinned model packaging/license review and reference replay. Reuse the
   existing studies; don't restart feasibility or mistake host prototypes for production adapters.
3. Define shared run inputs/results and exact-node status fixtures. Test negative/zero/positive
   margins, unrounded boundaries, missing terrain, out-of-radius nodes, cancellation, stale inputs,
   moved nodes, incomplete catalogs and an empty catalog on both platforms.
4. Deliver one complete unsaved Estimated Coverage calculation on iOS and Android, including
   known-node markers and accessible status list, cancellation, overlay and explicit assumptions.
   Qualify bounded resource use and geographical alignment on iOS and both Android map providers.
5. Extend persistence and connect Save/Open from both tools to Saved Plans, including migration
   and saved-input/result provenance tests. Keep compare/export and multi-transmitter simulation
   deferred. A standalone saved-profile library is not a separate feature or prerequisite.
6. User device validation: real terrain/edge cases, permissions, light/dark, large text,
   compact/wide/fold layouts, and resource budgets. No simulator/UI tests without user direction.

Estimated Coverage is now the primary Node Planner target for 0.10.3, replacing the site-library-only
scope. Existing build numbers are unchanged. Engine/data/distribution and device qualification
remain release gates; revise timing explicitly if they cannot be met. The separately planned iPad
two-pane map work remains recorded but must not displace the coverage-first implementation sequence.

## Implementation progress — 2026-11-02

First native slice: leased HGT readers and coordinate-to-tile/bilinear mosaics now compile in both
apps, with 18 shared geographic fixtures and seven tests per platform. No coverage UI or RF run is
connected yet. [Validation and next integration tasks](native-terrain-integration.md).

Second native slice: validated cache identities and complete-set capacity/download preflight now
match the Python repository contract on both platforms. Nine identity vectors and thirteen
preflight fixtures pass; disk-cache ownership and the cancellable downloader remain next.

Third native slice: decoded-tile disk caches now provide exclusive ownership, staged verified
publication, persistent LRU, reader pinning and offline reuse. Ten new tests per platform pass.
The compressed decoder/downloader and whole-job workspace reservations remain next; the low-level
cache does not yet download terrain or recover damaged committed metadata automatically.

Fourth native slice: bounded gzip decoding now validates pinned compressed hashes/lengths, gzip
integrity and exact decoded dimensions on both platforms. Twenty-two shared fixtures and a local
decode/cache/reopen integration test pass. Cancellable HTTP downloads and complete-pipeline disk
reservations remain next; RF calculation and coverage UI are not yet connected.

### Native HTTP progress

Single-attempt cancellable terrain downloads are implemented on both platforms with bounded
application-byte accounting, source hash/length checks, owned temporary files and failure cleanup.
The full-pipeline disk estimate now includes both decoded copies during cache publication.
These pieces are not yet connected to the coverage screen: terrain orchestration, retry/repair
policy, enforced disk reservations, RF execution and known-node classification remain next.

### Terrain preparation progress

Both platforms now connect downloads, gzip decoding and cache publication into an exclusive
preparation operation. It returns a complete set of verified pinned readers, supports offline
reuse, enforces the logical pipeline quota, checks free disk space, retries selected transient
failures, and releases partial reader sets on failure. Cache repair, workspace crash recovery,
geographic tile selection, RF execution and the standalone map UI remain outstanding.

### Terrain recovery progress

Online preparation now repairs confirmed corrupt, unpinned payloads after recalculating budgets.
Offline corruption remains explicit. Download/decode files now live in an owned cache workspace
with restart cleanup; Android cancellation waits for callback cleanup before releasing ownership.
Invalid metadata still fails without resetting data. Geographic tile selection and propagation
integration remain the next step toward the standalone coverage map.

### Geographic selection progress

Radius plus sampling halo now selects a conservative set of terrain tiles on iOS and Android,
including date-line crossings. Source resolution fails explicitly for gaps or incompatible data;
the complete selected set can be prepared as a geographic sampler. Oversized footprints report a
limit instead of silently shortening the requested range. This fetch footprint does not define
the eventual terrain-shaped coverage overlay. Provider-manifest discovery and RF sampling remain next.

### Profile-building progress

Native Swift/Kotlin profile builders now retain supplied cell crossings and fitted interior peaks
before merging regular samples, with explicit fit/data/work/cancellation failures. Geographic
crossing discovery and RF convergence remain unconnected. The initial port retains the study's
100 km per-profile qualification domain; this does not change the planned adjustable study radius,
and longer-range RF support still requires qualification.

### Geographic profile integration progress

Cell-crossing discovery now feeds the profile builder directly from prepared terrain mosaics on
both platforms. It handles date-line wrapping and latitude turning points, counts geometric work,
and preserves reader ownership through errors. The initial adapter supports paths up to 100 km
within ±85° latitude; longer-range/polar qualification remains outstanding. RF convergence,
scheduler integration, provider-manifest discovery and the standalone coverage UI remain next.

### RF convergence progress

Both platforms now refine terrain profiles under cumulative per-path work budgets and require
stable loss estimates outside the 0.25 dB threshold guard before exposing an unrounded margin.
Unstable or threshold-adjacent results expose no usable estimate, preventing later node coloring
from treating them as confirmed coverage. Eight shared decision fixtures and terrain integration
tests cover the policy. The model remains injected: production RF engine packaging/distribution,
full-study scheduling, provider discovery and map UI are still outstanding. These tests validate
policy behavior, not propagation accuracy. See the [integration report](native-terrain-integration.md).

### Radio budget progress

Explicit radio/receiver assumptions now produce matching allowable loss, receive level and
unrounded margin calculations on both platforms, verified by five shared fixtures. No defaults
are inferred for unknown equipment. These calculations are ready for the RF adapter; they do not
yet run the model or color known nodes. Run snapshots and node-status handling remain next.

### Known-node status progress

A native resolver now checks node source/identity/coordinates and run tokens before exposing
predicted status. Outside-radius, cancelled and capped nodes stay not evaluated; missing or
unresolved predictions stay unknown; obsolete inputs become stale. Eighteen shared fixtures
verify parity and unrounded sign handling. This is status logic only: full run snapshots,
convergence-result adaptation, catalog integration and map markers remain unconnected.
