# NodeScope Privacy Notice

Last updated: September 12, 2026

NodeScope is a read-only client for a CoreScope-compatible analyzer selected by
the user. NodeScope does not operate an account system, advertising network,
analytics service, or application backend.

## Information sent over the network

NodeScope connects directly to the analyzer host selected during onboarding or in
Settings. It requests public analyzer data such as nodes, packets, channels,
observers, regions, and network analytics over HTTPS and receives live traffic
over a secure WebSocket connection.

The analyzer operator may receive information normally included with a network
request, such as the device's IP address, request time, and requested endpoint.
The analyzer operator's own privacy and retention practices apply to that data.
NodeScope does not control those practices.

NodeScope does not send the user's precise location or locally stored channel
keys to the analyzer.

## Location

Location access is optional and requested only when the user asks NodeScope to
center the map on their current position. The location is used on-device for that
map action. NodeScope does not retain it after use or transmit it to the selected
analyzer.

## Information stored on the device

NodeScope stores the following locally:

- The selected analyzer, region, map style, and appearance preference
- Completion of onboarding
- Short-lived in-memory copies of analyzer responses for responsive navigation
- Monitored channel names, channel keys, and local message summaries in the iOS
  Keychain

Channel Keychain records use device-only protection and are available only while
the device is unlocked. NodeScope does not upload these keys to an analyzer or to
the developer.

Deleting the app removes its ordinary local preferences and caches. Keychain
items are managed by iOS and may require removal from within the app before
deletion if the user wants to ensure they are removed immediately.

## Third-party services

NodeScope displays Apple Maps and connects to the analyzer selected by the user.
Apple's terms and privacy policy apply to Apple Maps. Each analyzer is operated
independently and may have its own terms and privacy policy.

## Children

NodeScope is a technical network-monitoring utility and is not directed to
children. It does not knowingly collect personal information from children.

## Changes

Material changes to this notice will be published in this repository with a new
"Last updated" date.

## Contact

Privacy questions and reports can be submitted through the repository's GitHub
issue tracker: <https://github.com/anieto/corescope-mobile/issues>.

