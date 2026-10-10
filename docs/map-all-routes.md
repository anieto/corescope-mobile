# Map all routes

Status: spec, not started. 2026-10-10. Independent of the Beacon work; it can ship before or with 0.8.0.

## Goal

Show **every route a packet took on one map**, not one at a time. A flood packet spreads from its
sender through repeaters to many observers; seeing all of it at once shows how the mesh actually
carried it, which repeaters did the work, and where coverage branches. (Beacon's web app has a "map all
routes" view; this is NodeScope's take on the idea, built on data the app already computes.)

## What exists today

Both platforms already turn a packet's observations into **distinct routes** with who heard each one:

| | iOS | Android |
|---|---|---|
| Route model | `RouteOption` (`Models/RouteOption.swift`): `keys`, `heardBy` (strongest first), `alongTheWay` | `RouteOption` (`feature/packets`), mirrors iOS |
| Building routes | `RouteOption.make(from:)`, `merge(_:_:)` | same names |
| Replay state | `PacketReplayStore`: `routes`, `selectedRouteIndex` | `RouteReplay(routes, selected, options)` in `LiveRouteAnimation.kt` |
| Choosing a route | `Features/Shared/RoutePicker.swift` | `feature/packets/RoutePicker.kt` |
| Drawing | `MapScreen.replayPacketRoute()` builds animated `ActivePing` segments for **the selected route only**, then fades them | `routeFrame()` produces `RouteLine`s for both map engines (MapLibre, Google) through `MapEngine.setRouteFrame` |
| Entry points | "Replay on map" in `PacketDetailScreen` and `PacketFeedScreen` | `RouteReplay` created in `NodeScopeApp.kt` |

So the data exists; what's missing is a mode that draws all routes at once and keeps them on screen.

## User experience

1. **Entry.** Wherever "Replay on map" appears (packet detail, packet feed, a channel message's packet),
   add **Map all routes**. It's shown only when the packet has two or more routes; with one route the
   existing replay is the same thing.
2. **On the map.** All routes draw at once as a **tree** from the sender: shared hops are drawn once, and
   lines split where routes diverge. Each line ends at the observers that heard that branch.
   - **Line weight** shows how many observers heard the packet through that segment (more observers,
     thicker line, within a small fixed range).
   - **Colour** stays the replay colour; the selected route (below) is highlighted, the rest are dimmed.
   - **Uncertain hops** (unresolved, or ambiguous on Beacon) draw **dashed**, with candidates shown as
     faint alternatives or skipped, but never as a confirmed line (same rule as the Beacon plan's
     route states).
3. **Stays on screen** until dismissed, unlike the timed replay. Optional one-shot animation on open
   (see "Animation").
4. **Interaction.**
   - Tap a line: highlights that route and opens its existing details (hops, "heard by", "along the
     way"), reusing today's route sheet / `RouteDetails`.
   - Tap a node on the tree: its usual node callout, plus how many routes passed through it.
   - The route picker stays available with an extra **All** choice; picking a specific route
     highlights it within the tree, picking **All** clears the highlight.
   - "Replay this route" from the highlighted route runs today's single-route animation.
5. **Summary strip** (in the existing replay controls area): "N routes · M observers · K repeaters",
   and "X hops unresolved" when relevant.
6. **Framing.** Fit the camera to the whole tree (sender, all route nodes, all observers), reusing
   each platform's fit-to-bounds logic and its insets for overlays and the Duo hinge.

## Building the tree

Input: the packet's `RouteOption` list (each a list of node keys, sender first) plus hearings.

1. Treat each route as a path of hops `(key[i], key[i+1])`, then a final hop from the last node to each
   observer in `heardBy`.
2. Merge identical hops across routes into one **segment**, counting the distinct observers downstream
   of it (for line weight) and the routes using it (for tap targets).
3. Skip hops whose nodes have no coordinates; keep the rest of the route (like `resolvedSegments` on
   Android, which already splits a route into drawable sub-chains around gaps).
4. Keys are compared lowercase. Prefix-only keys (shorter than a full key) are never treated as a
   confirmed node; draw that hop as uncertain.
5. Order is stable (sender outward, then by route index) so the drawing doesn't reshuffle when more
   observations merge in while the view is open (both platforms already merge late observations into
   the route list).

Put this logic in a small pure function on each platform (iOS next to `RouteOption`, Android next to
`RouteGeometry.kt`) with unit tests; the renderers only draw segments.

## Limits and clutter

- Busy packets can have 20+ observers. Draw everything up to a budget (e.g. 60 segments); beyond it,
  draw the strongest branches (most observers downstream) and show "Showing N of M routes", with the
  route picker still listing all.
- Co-located nodes: reuse each platform's existing marker spreading (`spreadCoincidentNodes` on
  Android; the iOS equivalent) so overlapping repeaters stay tappable.
- With system animations or Reduce Motion off, draw the final tree immediately.

## Animation

Ship static first. A later option: a single "flood" animation on open, where each segment appears when
its start node is reached (depth-first timing by hop index), then the tree stays. Both platforms'
existing per-hop timing (`ActivePing` travel durations, `RouteTiming` on Android) can drive it. The
flood animation is out of scope for the first version.

## Platform notes

- **Android:** closest already. `RouteReplay` holds every route; `routeFrame` draws whatever
  `LiveRoute`s it gets. Add a "show all" state to `RouteReplay`, build persistent (non-fading)
  `RouteLine`s from the tree with per-segment width and dash, and add width/dash to `RouteLine` for both
  engines. Check MapLibre and Google Maps render dashes the same.
- **iOS:** `MapScreen` draws `ActivePing` segments with timed fades. Add a separate persistent overlay
  for the tree (MapKit `MapPolyline` with stroke width and dash pattern), driven by a new
  `PacketReplayStore` mode, rather than bending `ActivePing` into a non-fading shape. MapScreen is
  large (about 3,200 lines); keep the tree overlay in its own view or file.
- Both: the Beacon phase 2 work (milestone A2) adds confirmed / ambiguous / unresolved hop states. If
  that lands first, use them; if this ships first, use "unresolved" for missing keys and let A2 extend it.

## Accessibility

- VoiceOver / TalkBack: the summary strip is the accessible description ("6 routes to 11 observers
  through 9 repeaters"); the route picker remains the accessible way to step through routes.
- Uncertain hops are distinguishable without colour (dashes).
- Line weight is never the only way to read observer counts (they're in route details).

## Tests

- Tree building (both platforms, same cases): shared prefixes merge; diverging branches split;
  identical routes collapse; missing coordinates split a route without dropping the rest; prefix-only
  keys become uncertain; late-merged observations keep segment order stable; budget trimming keeps the
  strongest branches.
- Use real packet shapes: the CoreScope `packet-detail.json` fixture and the Beacon
  `shared/fixtures/beacon/packet-detail.json` (20 observations, route hops that match their key
  prefixes).
- No UI automation; the user checks the map by hand on phone, Duo and iPad / Android tablet.

## Out of scope (first version)

- The flood animation.
- Mapping all routes for many packets at once (e.g. a whole channel's traffic).
- Live-updating trees from the WebSocket while open (late observations from refetches still merge in).

## Open questions

1. Name: "Map all routes" or "Show all routes"?
2. Default: when a packet has many routes, should "Replay on map" open the tree instead of route 1?
3. Segment budget: 60 is a guess; check against a busy packet on analyzer.meshtexas.org.
