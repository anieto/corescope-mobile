# Changelog

## 0.3.0 - 2026-09-17

Release focused on making frequently used mesh data faster to find, save, and
revisit.

### Added

- Explore hub for analyzer-scoped favorite channels, nodes, and observers
- Unified search across nodes, observers, channels, public keys, and packet hashes
- Recent searches and recently viewed network items
- Reorderable favorites with node-role icons and direct favorite management
- Quick actions to show nodes on the map and copy public keys, observer IDs,
  channel names, and packet hashes
- Direct navigation from a node's Heard By list to observer details

### Improved

- Channel filtering and sorting
- Empty-state guidance for discovering and saving network items
- Dark-mode separation for recently viewed rows
- VoiceOver access to node quick actions and clearer search-field semantics
- Dynamic Type behavior in the floating tab dock

## 0.2.1 - 2026-09-16

Patch release improving live-feed reliability and finalizing BetweenThieves
Development branding.

### Improved

- Accept WebSocket heartbeat envelopes that do not contain packet data
- Ignore heartbeat frames in channel, packet-feed, and live-map presentation
- Updated the app bundle identifier to `com.btdev.nodescope`
- Updated in-app copyright attribution to BetweenThieves Development

## 0.2.0 - 2026-09-13

Release candidate with the complete NodeScope interface refresh and native
iPhone and iPad experience.

### Added

- Adaptive iPad layouts for full-screen, resizable, and multitasking windows
- Observer analytics charts and expanded node, packet, and mesh-reach details
- Locally monitored channel status and redesigned channel conversations
- Automatic web-link detection with an in-app browser
- Animated mesh-route loading indicators across network-backed screens
- Persistent, analyzer-scoped caches for map, region, observer, node, and packet data
- Privacy manifest covering the app's settings storage

### Improved

- Floating tab dock with repeat-tap navigation reset behavior
- Live map controls, region loading feedback, clustering, and packet replay
- Route deduplication and accurate route endpoints through clustered nodes
- Cached-first loading with shared in-flight requests and background refreshes
- Full-window backgrounds, safe header placement, and dock clearance on iPad
- App product and process naming now consistently use NodeScope

### Fixed

- Main-thread map clustering that could trigger an iPadOS watchdog termination
- Content hidden behind the floating dock on detail and conversation screens
- Region labels, loading states, and map controls lost during the UI redesign

## 0.1.1 - 2026-09-12

Licensing correction for the private prerelease.

- Replaced the MIT license with GNU GPL version 3
- Added an application-store distribution exception for Apple App Store and
  Google Play distribution
- Added required copyright attribution and in-app legal notices
- Added native iPad support with adaptive content widths, multitasking-friendly
  layouts, and portrait and landscape orientations

## 0.1.0 - 2026-09-12

First NodeScope prerelease for iOS.

### Included

- Live regional mesh map with node clustering and packet activity
- Packet route inspection and map replay
- Public and locally monitored channel conversations
- Observer dashboard, analytics, and detail views
- Node health, reach, link, and path details
- Configurable CoreScope-compatible analyzer sources
- Native NodeScope visual system, progressive loading states, and floating dock
- Light and dark appearance support

### Known limitations

- Prerelease software intended for testing
- iOS 18.0 or later is required
- Android client is not yet included
- Analyzer capabilities and available data vary by configured server
