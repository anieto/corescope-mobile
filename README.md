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
an Android client is planned for this repository.

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

The current source tree contains the native iOS client. When Android development
begins, platform code will be separated into `ios/` and `android/` directories in
a dedicated migration checkpoint.

## Privacy

NodeScope has no analytics service or application backend of its own. See
[PRIVACY.md](PRIVACY.md) for what is stored locally and what is sent to the
analyzer you select.

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
