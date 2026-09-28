# iOS Tablet Split-View Plan

## Goal

Bring the Android tablet and foldable interaction model to iOS using native iPad and SwiftUI conventions. Preserve feature selection and nested navigation while the window rotates, resizes, or moves between compact and regular layouts.

## Design Direction

- Use `NavigationSplitView` for native iPad list/detail navigation.
- Allow the same views to collapse into push navigation on iPhone and narrow iPad windows.
- Keep selection in feature-owned state rather than deriving it from the current window size.
- Use native sidebars, dividers, materials, toolbars, and existing Liquid Glass treatments.
- Do not reproduce Android's rounded-pane card scaffold on iOS.
- Keep the existing floating tab dock initially. Revisit it only if the split layouts expose a specific usability problem.

## Milestone 1: Channels

- Replace the current `NavigationStack` with a two-column `NavigationSplitView`.
- Keep the channel list, search, filters, monitoring controls, and add-channel action in the leading column.
- Show the selected `ChannelDetailScreen` in the detail column.
- Show a native `ContentUnavailableView` when no channel is selected.
- Preserve the selected channel across rotation, window resizing, Split View, and Stage Manager changes.
- Display selection in the channel list when both columns are visible.
- Keep packet navigation nested inside the channel detail column rather than introducing a permanent third column.
- Preserve current compact-width push navigation on iPhone.

## Milestone 1: Observers

- Apply the same two-column structure to observers.
- Keep the observer list, summary, search, and filters in the leading column.
- Show the selected `ObserverDetailScreen` in the detail column.
- Show an empty-detail prompt when no observer is selected.
- Make deep links select the observer and reveal its detail.
- Preserve selection through size changes.
- Keep export, favorite, and sharing controls in the detail toolbar.

## Shared List/Detail Structure

- Create a small shared adaptive navigation container for Channels and Observers.
- Let it coordinate selection, preferred compact column, empty-detail presentation, and reset behavior.
- Keep feature-specific lists and details as separate view types.
- Avoid turning the container into a broad generic UI framework.
- Use stable model IDs for selection and list identity.
- Keep nested detail navigation paths separate from sidebar selection.
- Clear selection only for an explicit reset, analyzer change, deleted item, or direct user action—not because the window size changes.

## Milestone 2: Map and Live Packets

On sufficiently wide iPad windows:

- Show the live packet feed in a leading column and the map in the detail area.
- Selecting a packet highlights it and replays its route on the map.
- Selecting the active packet again ends its replay.
- Keep the map visible when viewing packet details, using navigation within the packet column or an appropriate native inspector-style presentation.
- Allow the packet column to be hidden with the native sidebar toggle.
- Remember the person's packet-column visibility preference.
- Prefer the prominent-detail split-view style to preserve map width.
- Preserve map camera state when the packet column opens or closes.

On iPhone and narrow iPad windows:

- Keep the map full-screen.
- Keep Live Packets available through Explore instead of forcing a cramped split.

## Live Map Marker Polish

### Node indicators

- Remove role icons from ordinary node markers and communicate node type through the existing role colors, matching Android and the CoreScope site.
- Use a smaller filled dot with a high-contrast outline for normal nodes.
- Keep route-hop and highlighted-node states visibly distinct through outline width, scale, glow, or an outer ring rather than restoring an icon.
- Keep an invisible, comfortably tappable hit area around the smaller visible dot.
- Preserve accessible labels and hints so VoiceOver announces the node name, role, and available action without relying on color.
- Keep role icons in filters, search results, and other labeled UI where they improve scanning; this change applies only to dense map annotations.

### Node-role filter legend

- Make the map filter's Node Roles section double as the legend for node-marker colors.
- Show a small filled color dot beside each written role name, matching the map marker's fill and outline treatment.
- Remove role icons from this filter section so the filter and map use one consistent visual language.
- Represent filter selection separately with a checkmark or native row selection state; marker color identifies the role and must not also carry selected/unselected meaning.
- Keep every role labeled in text so the legend never depends on color alone.
- Consider a short “Marker colors” caption only if testing shows that the map-to-filter relationship is not sufficiently clear.
- Keep role icons in search results and detail screens where they provide useful context outside the dense map presentation.

### Clustering density

- Treat marker size and clustering as separate controls: making the dot smaller does not automatically change the geographic clustering algorithm.
- After reducing the visual marker size, tighten the clustering grid so more nearby nodes remain individually visible before becoming a cluster.
- Re-evaluate the current close-zoom cutoff, grid cell divisor, and minimum cluster count using dense real-world regions.
- Continue spreading nodes that share identical coordinates at close zoom while adjusting the spread radius for the smaller markers.
- Validate that route nodes remain individually visible even when surrounding nodes are clustered.

### Map status indicator

- Do not replace the stable “updated ago” status with a loading indicator for routine marker recomputation after a pan or zoom.
- Reserve the prominent loading indicator for initial data loading, analyzer or region changes, explicit refreshes, and location acquisition.
- Treat viewport marker recomputation as an internal map operation; keep the previous markers and status visible until the new display result is ready.
- If recomputation ever becomes slow enough to need feedback, use delayed, subtle progress that does not cause the status pill to flash during normal gestures.

## State and View Architecture

- Replace untyped `NavigationPath` as the primary list/detail state with typed selected models or stable IDs.
- Preserve nested detail navigation independently of list selection.
- Extract reusable packet-feed content from `PacketFeedScreen` before embedding it beside the map.
- Split sidebar, empty-detail, and detail regions into separate `View` types with narrow inputs.
- Ensure live data updates do not reset selection, scrolling, or row state.

## Mac Roadmap

The app remains a Designed-for-iPad app on Apple silicon Macs. Basic window resizing is enabled by supporting iPad multitasking and not requiring full-screen presentation.

Future Mac usability work:

- Test wide, narrow, tall, and short Mac windows and define a practical minimum window size if needed.
- Tune the floating tab dock for short and narrow windows.
- Prevent map controls, replay controls, and packet controls from colliding as the window resizes.
- Verify smooth transitions between split-view and compact navigation at intermediate widths.
- Constrain sheets and detail content that become excessively wide on large displays.
- Audit iPad-specific status-bar and window-control spacing when running on macOS.
- Add keyboard shortcuts, pointer hover states, and menu commands where they improve desktop workflows.
- Consider multiple-window support only after the single-window experience is polished.
- Reconsider Mac Catalyst or a native macOS destination only if the Designed-for-iPad experience becomes too limiting.

## Implementation Order

1. Create the shared adaptive list/detail structure.
2. Convert Channels and verify compact and regular navigation.
3. Convert Observers and verify deep-link behavior.
4. Extract reusable packet-feed content from `PacketFeedScreen`.
5. Add the map/live-packets split and shared replay selection.
6. Simplify node indicators, tune clustering density, and stabilize the map status indicator.
7. Polish column widths, toolbars, empty states, Dynamic Type, and VoiceOver.
8. Complete build, behavioral, and accessibility validation.

## Validation Matrix

- iPhone portrait and landscape.
- iPad portrait and landscape.
- iPad one-third, half, and two-thirds Split View widths.
- Stage Manager window resizing.
- Designed-for-iPad app on Apple silicon Mac at minimum, intermediate, maximized, and full-screen window sizes.
- Rotation or resizing while a detail is selected.
- Analyzer changes and explicit tab resets.
- Channel and observer deep links.
- Live list updates while an item is selected.
- Map camera preservation while toggling the packet column.
- Dense map regions at multiple zoom levels, including coincident nodes and route hops.
- Repeated map pans and zooms without status-pill flashing or marker flicker.
- Node-marker differentiation in light mode, dark mode, increased contrast, and color-filter accessibility settings.
- Map-filter legend consistency, selection clarity, and usability without color perception.
- Large accessibility text sizes.
- VoiceOver navigation and selected-row announcements.

## Recommended Delivery

Deliver Channels and Observers as the first milestone. Treat the map/live-packets workspace as the second milestone because it requires additional coordination among the packet feed, replay store, map selection, and camera state.
