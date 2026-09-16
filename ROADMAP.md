# NodeScope Roadmap

NodeScope should remain a fast, read-only mesh observability tool. New work
should reinforce that identity while preserving responsive map and live-feed
performance.

## 0.2.2 — Post-launch hardening

- Review App Store and TestFlight crash, hang, and performance reports.
- Improve analyzer-unreachable, authentication, malformed-response, and stale-cache states.
- Show clear last-updated and cached-data indicators.
- Allow retrying without discarding already loaded content.
- Audit VoiceOver, Dynamic Type, contrast, and reduced-motion support.
- Stress-test large regions and long-running live-feed sessions.

## 0.3.0 — Search and favorites

- Add global search for nodes, observers, channels, public keys, and packet hashes.
- Support favorite nodes, observers, and channels.
- Add a compact Favorites dashboard or section.
- Track recent searches and recently viewed items.
- Add quick actions such as Show on Map, Open Observer, and Copy Public Key.
- Investigate local notifications for favorite-node activity or staleness where the available data permits it.

## 0.4.0 — Network exploration

- Add map filters for node role, activity age, and observer.
- Add an activity-density or heatmap layer.
- Add a historical time-window selector.
- Support comparing two nodes or observers.
- Expand route analysis with alternate paths, route frequency, hop stability, and common relays.
- Allow route hops to open their corresponding node details.
- Share routes as images or structured text.

## 0.5.0 — Personal dashboards

- Add configurable dashboard cards.
- Save analyzer and regional views.
- Support configurable health thresholds.
- Show observer uptime and activity trends.
- Add network summaries such as active nodes, stale nodes, packet rate, and average SNR.
- Export selected statistics as CSV or JSON.

## Supporting improvements

- Analyzer connection diagnostics and capability detection.
- Secure configuration import and export without exposing monitored-channel keys.
- Cache controls and storage-usage reporting.
- Deep links into nodes, channels, observers, and packets.
- App Shortcuts and Spotlight integration.
- Localization infrastructure.
- Android client after the iOS data model and navigation patterns stabilize.

## Current priority

The next major feature should be global search plus favorites. After that,
prioritize map filters and richer route analysis.
