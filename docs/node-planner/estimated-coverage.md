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
