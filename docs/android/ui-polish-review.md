# Android UI polish review

Reviewed September 23, 2026. This is a design recommendation, not an implementation change.

## Resume checkpoint — September 24, 2026

The user has put tablet list-detail work on hold. Do not start it until asked to resume. The phone UI polish, adaptive launcher icon, analytics interactions, and large-text/short-landscape fixes described in the progress entries below are implemented. Last validation: build and 134 unit tests passed, along with targeted large-text navigation/Units and short-wide navigation-rail tests.

When resumed, the proposed next work is Observer list-detail panes, followed by Channels. Preserve selection and scroll position during resizing/rotation and retain phone navigation. Then perform an overall visual consistency review. Full tablet/foldable and TalkBack audits are not complete.

Persistent requirements: channel messages alternate left/right when the sender changes; consecutive messages from the same sender stay together; keep an explicit visible “View packet” action and all packet inspection functionality; keep the live packet feed off the main map. Preserve the newer units, source-logo, analyzer-framing, and signing work already in the repository.

Testing note: the connected test runner reset emulator app data. Use an isolated test emulator for future instrumentation when possible, target a specific device for installation, and restore any changed font/rotation settings afterward. The display settings used during this pass were restored to font scale 1.0, user rotation 0, and automatic rotation enabled.

## Scope and direction

Inspected the current Compose implementation and the running Android app: Map, Explore, Channels, Observers, Settings, node and observer details, a channel conversation, message packet details, and node analytics. Checked analytics in light and dark themes and restored the system theme afterward. This was not a complete TalkBack, large-font, tablet, foldable, or performance audit.

The app has a good native foundation: Material navigation, pull-to-refresh, modal filter sheets, segmented controls, system sharing and document export. Keep those. The main opportunity is to make the same kinds of information and actions look and behave consistently.

Recommended direction: a calm, precise network-monitoring tool. Keep the blue identity and neutral surfaces. Give names, messages, network health, and routes prominence. Use color for meaningful state and selection, with less emphasis on decorative containers. Adopt selected Material 3 Expressive improvements rather than enlarging every control or adding more pills.

## Priorities

| Priority | Finding | Recommendation |
| --- | --- | --- |
| First | Detail headers and metadata displace useful information | Compact identity headers; move keys and secondary actions into disclosures and menus |
| First | Different screens independently style headers, metrics, cards, and status | Establish shared tokens and components, then migrate the existing screens |
| First | Channel metadata and actions compete with message content | Preserve alternating alignment when the sender changes; simplify metadata and retain an explicit compact “View packet” action |
| First | Some foreground/background combinations have poor contrast | Introduce accessible semantic color pairs for both themes |
| Next | Browsing screens spend too much space on repeated containers | Use grouped list surfaces, compact metadata, and consistent row anatomy |
| Next | Live state and freshness are inconsistently explained | Separate connection state, cached-data freshness, and node activity |
| Next | Analytics requires substantial scrolling before reaching charts | Compress identity and summary sections; improve chart inspection |
| Later | Large screens mostly receive a rail and stretched phone content | Add adaptive list-detail and supporting-pane layouts |

## Shared design rules

- Use a small spacing scale, such as 4, 8, 12, 16, 24, and 32 dp. Start with 16 dp phone content margins. These are proposed design choices, not platform requirements.
- Define one root-header pattern with predictable search/filter positions, and one compact detail-header pattern. Large root titles can remain if they collapse while scrolling.
- Define one metric presentation: value, label, optional unit and meaningful state. Use consistent sizes across Explore, Observers, node details, and analytics.
- Use cards to group related content, not automatically around every row and every sub-section. Prefer neutral grouped surfaces with dividers for dense browsing.
- Reserve pill styling mainly for interactive filters and distinct states. Ordinary packet counts, timestamps, and radio measurements can be a plain secondary line.
- Start with 14–16 sp body text and 12–14 sp metadata; validate with system font scaling. Do not rely on tiny labels to make dense content fit.
- Provide at least 48 dp interactive targets and explicit accessible labels. Compact visual controls can retain generous touch areas.
- Keep domain colors stable: node roles, signal quality, and route series should retain their meaning. Offer wallpaper-derived dynamic color only as an optional UI theme.

Primary implementation points: `core/design/Theme.kt`, `core/design/BrowseComponents.kt`, and the repeated local metric components.

## Screen recommendations

### Map

Keep the full map canvas and keep the live packet feed off this page. Align region, filter, zoom, and replay controls into a consistent family with matching sizes, shapes, and elevation. Avoid permanent dashboard cards over the map.

Use a compact selected-node bottom sheet with name, activity, role, and a clear details action. Let it expand for more information. Preserve route-node visibility and existing outlier handling. During replay, show a compact, contextual progress/control surface. Animate selection and sheet changes without re-animating the entire map on incoming data.

### Explore

This should be the place to find something and return to saved items. Give search a prominent, consistent entry point. Reduce the visual weight of the nested “Network at a Glance” container and tiles; a compact summary should leave room for favorites on the first screen.

Use the same saved-item anatomy for nodes, observers, channels, and packets. Put destructive removal or reordering into an edit mode or overflow where practical, instead of making removal compete with opening each item. Preserve existing customization and export features.

Keep the distinction between the global network summary and region-filtered screens, but label the scope explicitly.

### Channels

Use compact rows with channel name, last activity, a useful preview, and one secondary metadata line. Keep monitored channels distinct from discoverable channels without oversized section headings. A floating add action is worth considering only if starting monitoring is a frequent primary task.

For conversations, preserve the existing alternating left/right alignment when the sender changes. This is an intentional design choice that helps distinguish speakers; consecutive messages from the same sender should remain grouped on the same side.

Let message text dominate and put timestamp and essential radio information in a quiet line. Retain an explicit, visible “View packet ›” text action at the bottom of every message that currently supports packet inspection. Reduce its surrounding padding and visual weight while preserving a generous touch target and the existing packet-detail functionality. Tapping the message may be an additional shortcut, but must not replace the labeled action. Add day separators where useful.

Preserve scroll position when reading older messages and provide a visible “new messages” control. Do not add a composer unless sending is actually supported.

### Observers

Use the same browsing header and row structure as Channels. Keep active state, last heard, and the most useful rate visible; move secondary hardware/radio metrics into detail. Allow metadata to wrap sensibly at large font sizes.

On details, avoid repeating the full observer name in both the toolbar and a large identity card. Bring health and recent activity forward. Keep the existing compact favorite and overflow actions. Verify whether values such as `0 mV` mean a measured zero or unavailable data before changing their presentation.

### Node details

This is a high-impact redesign. The current first card contains identity, a full key, timestamps, and four prominent buttons, followed by a separate analytics action card. Health and connectivity arrive too late.

Suggested order: compact identity and favorite action; last heard and health summary; “Show on map” primary action; links/reach and recent activity; analytics entry; technical identity disclosure. Move Copy key and Share into overflow, with the full key available on demand. Keep secondary actions discoverable without giving them equal visual weight.

### Packets and routes

Use the same route component in packet details, message packet details, and route inspection: ordered hop names, role/status indicators, and clear handling of unresolved hops. Long hashes and raw packet data belong in expandable technical sections with copy actions.

In the feed, use a persistent overlay or anchored control for new packets when the reader has scrolled away. The current new-packets control is a list item near the top and can itself be offscreen. Avoid live updates moving the content under the reader.

### Analytics

Compress identity and summary metrics so the first chart appears sooner. Give time-range selection a consistent location and consider keeping it visible while scrolling. Use consistent chart heights, axis treatment, units, legends, and section spacing.

Add tap/scrub inspection where useful, plus an accessible textual summary or data view. The existing Canvas charts have descriptive semantics, but are not a substitute for inspecting individual values. Explain unavailable data and the time basis clearly. Only show trend comparisons when the necessary comparison data exists.

### Settings

Use grouped native list rows with restrained section labels. Combine source identity and connection state rather than repeating them in multiple panels. Retain the native appearance selector and existing system export/share flows.

Use a shared SnackbarHost for brief confirmations, with Undo for reversible removal where appropriate. Keep actionable failures visible inline rather than disappearing in a transient message.

## Contrast and status fixes

Calculated from source colors against white: repeater orange `#FFAA44` is approximately 1.89:1, activity amber `#FFA833` is 1.93:1, and companion green `#45C99D` is 2.08:1. These combinations are used for role text/icons or replay button styling. They need different foreground/background pairings, especially for small text.

Use a darker role-text token on light surfaces, or a tinted container with an appropriate foreground. The replay buttons currently use `surface` as their foreground on amber; use a deliberate contrasting foreground instead. Check both themes rather than assuming the same literal color works everywhere.

`BrowseHeader` and the packet summary map non-live connection states to “Reconnecting.” Give connecting, paused, offline, and reconnecting states accurate language. Separately show when cached data was updated and when a node was last heard. A live analyzer connection does not imply every node is online.

## Android-specific opportunities

- Material 3 app bars with scroll behavior can retain expressive titles while reclaiming browsing space. [Android app bars](https://developer.android.com/develop/ui/compose/components/app-bars)
- Material search can provide a consistent expanded search experience; verify API availability before adopting it. [SearchBar](https://developer.android.com/develop/ui/compose/components/search-bar)
- Optional dynamic color is supported on Android 12 and later. Keep brand defaults and domain colors deliberate. Expressive API stability should be checked individually against the project's dependencies. [Material 3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3)
- NavigationSuiteScaffold and adaptive content layouts can improve tablet/foldable use. The current 600 dp rail switch is a start; pair it with channel/observer list-detail panes and map supporting content. [Adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- Verify predictive back behavior with the existing Navigation Compose setup rather than implementing a custom back gesture. [Predictive back](https://developer.android.com/develop/ui/compose/system/predictive-back)
- Standard snackbars provide consistent brief feedback and optional actions. [Snackbars](https://developer.android.com/develop/ui/compose/components/snackbar)
- Validate touch targets, reading order, and scalable text as part of the redesign. [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps)

The current Compose BOM is 2025.09.01. Treat dependency updates as a deliberate enabling step where needed; a navigation framework rewrite is not required for this polish work.

## Suggested delivery sequence

### Implementation progress — September 24, 2026

Fifth pass: checked the running app at 200% text size and found cramped Settings choices and wrapped tab labels. At font scales of 1.5 and above, Appearance and Units use full-width radio rows, phone navigation uses a labeled destination menu, and browse headers place actions below the title. Standard controls remain at normal font sizes. Landscape rails are scrollable and no longer apply system-bar insets twice. Build and 134 unit tests passed; targeted emulator tests passed for large-text navigation/Units and selecting Settings in a 900 × 300 dp constrained viewport. Original emulator font scale and rotation settings were restored. The UI test runner reset emulator app data, so the installed build opens at source setup. Full tablet list-detail panes remain pending.

Fourth pass: time-range controls stay outside the scrolling analytics content; analytics has a centered maximum width of 840 dp; activity plots support tap and horizontal-drag inspection with a cursor and exact timestamp/value readout. The text-value dialog remains available. Build and 127 unit tests passed. Two targeted emulator UI tests passed for exact point selection/dialog dismissal and range controls remaining visible and selectable after scrolling. Full tablet list-detail panes and large-font/landscape validation remain pending.

Third pass: analytics initially shows availability and signal grade, with an explicit expansion for all six metrics; role-icon contrast improved; activity time charts now offer a scrollable native dialog containing exact values and UTC timestamps. Map replay surfaces use the same corner treatment and restrained elevation as zoom controls. Debug build and 127 unit tests passed; a targeted emulator test verified exact chart counts, timestamps, and dialog dismissal. Interactive chart scrubbing, pinned range controls, and adaptive panes remain pending.

Second pass: simplified Observer summaries and rows, with battery/noise still available in details; shared readable active/inactive accents; removed nested colored metric tiles from Explore while retaining dashboard actions and customization; improved saved-item icon contrast; neutral active-filter badges; larger Settings navigation targets with wrapping subtitles; stacked packet-detail labels and values. Debug build and 127 unit tests passed. Visually inspected Explore and Settings in light mode and Observers in light/dark mode on the emulator; restored System appearance. Large-font and tablet checks remain pending.

First pass implemented: shared heading/metadata typography; accurate browse/feed connection labels; theme-paired replay button colors; compact channel metadata with alternating speakers and explicit packet actions preserved; node favorite and overflow actions, expandable technical identity, and health/reach ahead of analytics. This is the initial Channels/Node Details pass, not completion of the broader review. Adaptive layouts, chart inspection, and the remaining screen refinements are still pending.

Validation: debug build and 127 unit tests passed. Eight of nine foundation UI tests passed initially; the deep-link test exposed an existing cold-start wait on a non-observable navigation property. Switching to the navigation destination flow fixed that issue, and the targeted deep-link rerun passed with its original timeout. Existing identity/favorite UI assertions now account for the disclosure and icon action. Full visual, accessibility, and adaptive-device acceptance checks remain pending.

1. Establish shared typography, spacing, semantic colors, row anatomy, metrics, headers, and feedback. Fix contrast and misleading connection labels immediately.
2. Pilot the visual direction on Channels and Node Details. These expose both browsing and detail patterns and offer the largest visible improvement.
3. Apply the proven components to Observers, Explore, Packets, and Settings; refine map controls and selection sheets.
4. Add chart inspection, adaptive content panes, and restrained motion. Avoid repeatedly rebuilding the same screens before the shared patterns are settled.

Acceptance checks: light and dark themes; narrow phone widths and enlarged fonts; tablet/window resizing; TalkBack order and labels; touch targets; cached/offline/loading/error states; long names and keys; and stable reading position during live updates. For channels, verify that alignment alternates on sender changes, consecutive messages from one sender stay on the same side, and every previously inspectable message retains a visible, accessible “View packet” action. Measure route animation performance separately from visual redesign.
