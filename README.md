# NodeScope

NodeScope is an unofficial, read-only native client for
[CoreScope](https://github.com/Kpa-clawbot/CoreScope)-compatible mesh network
analyzers. It presents live MeshCore nodes, packet routes, channel traffic, and
observer telemetry in an adaptive interface designed for iPhone and iPad.

> [!IMPORTANT]
> NodeScope is an independent project. It is not affiliated with or endorsed by
> the CoreScope project or the MeshCore project.

## Status

NodeScope is currently prerelease software. The iOS app is under active testing;
an initial native Android client is now available in [`android/`](android/).

## Features

- Edge-to-edge live node map with region filtering and animated traffic
- Packet route inspection and replay on the map
- Public and locally monitored channel conversations
- Hashtag-derived and private channel keys stored in the device Keychain
- Observer status, packet metrics, and analytics charts
- Node health, mesh reach, links, paths, and observer details
- Configurable CoreScope analyzer source
- Light, dark, and system appearance modes

NodeScope does not transmit MeshCore radio traffic. It reads data exposed by the
configured analyzer and performs monitored-channel decryption locally.

## Requirements

- iOS or iPadOS 18.0 or later
- A Mac with Xcode and an Apple development team for device installation
- Network access to a CoreScope-compatible analyzer over HTTPS and secure WebSocket

## Building the iOS app

1. Clone this repository.
2. Open `CoreScopeViewer.xcodeproj` in Xcode.
3. Select the `CoreScopeViewer` scheme.
4. Choose a simulator, or select your development team to run on a device.
5. Build and run.

The default analyzer is `analyzer.meshtexas.org`. You can select another bundled
community analyzer or enter a custom compatible host during onboarding or in
Settings.

## Repository layout

- `CoreScopeViewer/`, `CoreScopeViewerTests/`, and `CoreScopeViewer.xcodeproj`: native iOS client.
- `android/`: native Kotlin / Jetpack Compose Android client.
- `CommunitySources/`: community analyzer source data.

The iOS project stays at its existing path so existing Xcode workflows continue
to work. Android builds independently from its own directory.

## Building the Android app

Open `android/` in Android Studio, sync Gradle, and run the `app` configuration on
an Android 8.0+ emulator or device. See [Android setup and roadmap](android/README.md)
for build commands and current scope. The new foundation includes onboarding,
native navigation, analyzer/region settings, node browsing and a MapLibre/CARTO
integration awaiting key-based device validation. Channels, observers, live
packets and the remaining iOS workflows are tracked in the
[Android parity plan](docs/android/README.md).

## Privacy

NodeScope has no analytics service or application backend of its own. See
[PRIVACY.md](PRIVACY.md) for what is stored locally and what is sent to the
analyzer you select.

## AI disclosure

It's mixed. I use AI a lot at work. Side projects are a way for me to keep my
programming skills alive. This project contains a mix of manually written and
AI generated (but manually reviewed) code. I used it more heavily in
brainstorming ideas and for managing the GitHub deployment. Feel free to not use this if
you're a purist.

## Related project

[CoreScope](https://github.com/Kpa-clawbot/CoreScope) is a separate GPL-3.0
project that provides the analyzer server and web application. NodeScope connects
to CoreScope's public HTTP and WebSocket interfaces; CoreScope is not included in
this repository.

## License

NodeScope is free and open-source software licensed under the
[GNU General Public License version 3](LICENSE), with a narrow
[Application Store Distribution Exception](LICENSE-APP-STORE-EXCEPTION.md).
Commercial use is allowed, but distributed modifications must remain under
GPLv3 and their corresponding source must be made available to recipients.
See [NOTICE](NOTICE) for the required attribution.
