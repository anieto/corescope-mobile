# CoreScope favorites and watched-node notifications

## Release sequence

1. Make the existing CoreScope node-favorite sync discoverable on iOS and Android. Verify the current web-to-app and app-to-web flows against a controlled analyzer.
2. Add watch management on both apps using the same account, watch list, and event rules as CoreScope web. Existing servers with the notification API can support this release and continue delivering email.
3. Add notification destinations to CoreScope, build the NodeScope relay, and connect both apps. Pilot the relay with one managed analyzer and one independently configured analyzer. Pushover can ship independently once the CoreScope delivery interface is ready.

Guest browsing and local favorites remain available. Scope the first mobile push release to watched-node offline/recovery and battery transitions already emitted by CoreScope. Enable additional watched-node events once their wording, routing, and privacy have been reviewed. Widgets and Live Activities can follow independently.

## Existing contracts to build on

- iOS and Android already have CoreScope account clients and account-favorite reconciliation. My Nodes can show account favorites. Sign-in reads account selections, importing device favorites is explicit, and pending account edits are uploaded via Sync now. Keep analyzer and account isolation, conflict handling, and the distinction between favorites and claimed nodes.
- The upstream CoreScope notification API exposes `GET /api/account/notifications` and idempotent `PUT` and `DELETE /api/account/notifications/watches/{pubkey}`. Mutations return the updated account notification state. The API supplies watch limits, supported event types and whether a watched node is still known. The optional bulk endpoint copies claimed My Nodes entries and can partially succeed. Leave it out of the first app release.
- CoreScope evaluates transitions in `cmd/server/notify_eval.go`. The notifier in `cmd/server/notify_service.go` persists evaluation state and then sends email. Reuse the evaluator's baseline, ingest-health checks and recovery semantics. Keep push independent of email-specific delivery gates such as bounce state and mail quotas.
- Verify the contract on the target deployment, including feature-disabled responses, default preference creation on first GET, session and CSRF behavior, watch limits, and the account-backed routes on both clients.

## Phase 1 and 2. Account and watch experience

- Add or surface a CoreScope account entry in My Nodes. Show signed-in analyzer, Sync now, pending edits, and an explicit import action. Confirm existing reconciliation and refresh behavior before changing it. Test sign-in with pre-existing favorites, adding and removing on either client, unsupported hosts, offline edits, and account switching.
- Add a Watch node action on supported node details and a Watched Nodes list in My Nodes. A star saves a favorite. A watch adds the node to the account's notification rules. Explain that the existing CoreScope preferences can cause a watch to generate email. Keep watch state keyed by analyzer and account and node identity keyed by normalized full public key.
- Discover support from a server capability where available. Support older compatible analyzers by probing the authenticated watch API. Show separate states for sign-in required, unsupported, loading and temporary failure. Preserve watch rows for nodes that are no longer in the analyzer and allow removal.
- Reuse account authentication and CSRF handling. Serialize watch mutations per account, replace state with the server response, refetch on return to the list, and discard responses from a previous account or source. Surface the limit and failed writes. Require connectivity for watch changes in the first release.
- Show existing event and email settings with a link to manage them on the web initially. An app-level push setting arrives in Phase 3. Avoid an early replacement of the server's full event list, which may include additional or admin-only events.

## Phase 3. Push architecture and server contract

See [NodeScope push relay v1](push-relay-v1.md) for the staging API contract and provider acceptance gates.

Use the NodeScope-operated relay for the staging pilot and production. CoreScope owns account watches, evaluates each transition once, and dispatches to the destinations both the operator offers and the recipient enables. Email, NodeScope Push, and Pushover are named destinations behind a shared Go delivery interface. Preserve the existing email behavior and isolate provider failures and quotas.

### Build order

1. **Define the contract and start CoreScope.** Specify analyzer enrollment, account-to-installation linking, event payloads, and revocation. Open the upstream discussion while the interface is easy to change. Refactor watch evaluation and delivery with email regression coverage. Favorites and watch management in both apps can proceed in parallel.
2. **Stand up the staging relay and prove both providers.** Deploy the HTTPS API, enroll one test analyzer, and use a script or minimal app builds to register test devices and deliver through real APNs and FCM credentials. The first end-to-end milestone is a real CoreScope watch event reaching both phones through this relay.
3. **Finish the product flows.** Add the CoreScope onboarding and test controls, NodeScope opt-in and token lifecycle on both platforms, and source-aware notification routing. Build Pushover against the shared interface and release it independently if ready.
4. **Pilot two analyzers.** Have the second operator enroll an independently configured analyzer through the product UI. Verify delivery, routing, and revocation with subscriptions on both sources. Finish operator docs and any public relay pages after the pilot. The HTTPS relay API is required for delivery. A public website can follow.

### NodeScope relay setup and event flow

1. NodeScope operates the relay at a stable HTTPS URL and keeps APNs and FCM sending credentials there.
2. The analyzer admin selects **Enable → Connect → Test**. Ship the public relay URL prefilled. One-time enrollment issues a revocable credential scoped to that analyzer and verifies the connection. CoreScope stores the credential on the server. Enrollment and authorization are required even when the URL is bundled.
3. The user signs in to that analyzer in NodeScope, enables push, and grants OS permission. The app registers its APNs or FCM token directly with the relay. Use a short-lived, single-use link tied to the authenticated CoreScope session and relay installation to authorize that analyzer's subscription. CoreScope stores an opaque subscription reference. The relay verifies the binding and retains the device token. One installation can subscribe to multiple analyzers independently.
4. CoreScope detects a transition and sends an authenticated, idempotent delivery request with an event ID and subscription reference. The relay verifies the analyzer owns that subscription, applies quotas, and submits the alert through the appropriate provider. Test connection traffic only targets an authorized test subscription or checks relay connectivity without sending to a user.
5. Tapping the alert opens the corresponding node on the analyzer that sent it. Opt-out revokes the device subscription for that account and source. Analyzer revocation blocks all delivery from that analyzer.

### Pushover setup

The admin enables Pushover and configures the instance's Pushover application token. Each user supplies their Pushover user key and enables the destination. CoreScope validates the recipient and sends to Pushover directly. Alerts appear in the Pushover app. A shared operations group could be a later destination with explicit group-level watches. Keep personal watches scoped to their individual recipients.

### Coverage and alternative considered

A source must run a compatible CoreScope release and enroll with the relay to offer NodeScope Push. Existing favorites, watch controls, and email continue where the source supports them. NodeScope-run monitoring could later cover publicly reachable sources without admin enrollment using read APIs. It would have its own watch rules, delay, and operating costs, and would need evidence that sparse node reports and stale analyzer ingest can be distinguished reliably. Keep it outside this release.

- Expose push availability and supported contract version per analyzer and platform. Keep sending behind a server flag and advertise each platform only when configured. Keep the watch API and email available on deployments without push.
- Model three controls. The watch list and selected event types belong to the account. Email is a delivery channel. Mobile push is opt-in per installation and account. The existing notification `enabled` flag gates emitted changes through the evaluator's `mailing` variable even though it still records state. Make transition emission transport-neutral and apply the existing flag at email delivery. Preserve every existing email opt-out and keep the email unsubscribe route scoped to email. Before release, verify the web labels and behavior so users can distinguish email settings from device push.
- Proposed endpoints under `/api/account/notifications` include `PUT /devices/{installationID}` for idempotent registration or renewal and `DELETE /devices/{installationID}` for opt-out. Use existing account auth and CSRF. Derive ownership from the session, verify a relay-issued linking proof, cap registrations, and prevent cross-account reads or writes. Return status and an opaque subscription reference without exposing provider tokens. Use an installation-scoped revocation mechanism or link the registration to a revocable session with documented offline logout behavior. Set a finite registration lease, renew it during app use, and expire abandoned records.
- Validate provider, app identity, and environment at the relay. Store provider tokens, environment, and token generation there. Store subscription bindings per analyzer, account, and installation, with opt-in state, generation, last renewal, and expiration. Encrypt tokens at rest where supported and omit them from logs and account exports. Handle token replacement atomically. A delayed invalid-token response should only retire the token generation that was actually sent. Make disabling, account deletion, account switching and token reassignment invalidate queued sends for the old binding.
- Persist push outbox entries in the same transaction as notification state changes so a restart cannot lose the transition between evaluation and enqueue. Send asynchronously with bounded concurrency. Give jobs stable transition and installation IDs, enforce uniqueness, respect expiration, and recheck watch membership, event preferences and device eligibility before sending. Group bursts per account/installation, enforce separate push quotas, and keep retries bounded with provider-aware backoff. Treat ambiguous provider outcomes as potentially duplicate rather than promising exactly-once delivery.
- Start with a versioned payload containing an analyzer identifier, event ID, event type, occurrence time and node public key for a single-node alert. A grouped alert opens Watched Nodes. Default to generic lock-screen copy and avoid embedding credentials, recipient identifiers, or arbitrary URLs. On tap, resolve the source through app configuration, confirm the active account matches the local registration, and validate the node key before routing. Provide a safe fallback for removed sources, changed accounts, and missing nodes. Notifications should describe what the analyzer observed, such as a node no longer being heard.

## Phase 3. Platform delivery

| Area | iOS | Android |
| --- | --- | --- |
| Provider setup | Enable Push Notifications for `com.btdev.nodescope`, update signing/profiles, and configure APNs credentials on the relay. Keep development and production APNs separate. | Register `org.nodescope.android` in Firebase, configure the Android app and FCM SDK, and use HTTP v1 with IAM credentials on the relay. Use a separate test project or build configuration. |
| Opt-in and registration | Ask for notification authorization when the user enables push. Register with APNs and reconcile the device token whenever registration returns it. Recheck authorization when the app becomes active. | Create a Watched Nodes notification channel. Request `POST_NOTIFICATIONS` on Android 13+ when the user enables push. Check channel/app settings and synchronize current and rotated FCM tokens, including `onNewToken`. |
| Presentation and taps | Use a visible APNs alert. Handle foreground presentation and taps through the notification center delegate. Extend the existing navigation store for source-aware node links and cold launches. | Use an FCM notification plus data payload for system presentation in the background and routing metadata. Handle foreground delivery through `FirebaseMessagingService`. Route notification taps through the activity for cold and warm starts. |
| Unsupported states | Show OS permission state with a route to system settings when needed. Reconcile after settings changes and revoke or expire server registration. | Show disabled permission/channel and unavailable Google Play services states. Keep watch and email controls usable on devices without FCM support. |

Registration requires app opt-in plus OS permission. Turning push off should revoke that installation's subscription without deleting its watches. Define source removal and signed-out behavior explicitly, including instant local cleanup, best-effort revocation when connectivity is unavailable, and lease expiration. Alerts already handed to a provider may still appear. Keep provider secrets out of the app and repository. Record provider acceptance separately from device receipt.

## Verification and rollout

1. **Contract tests** on a controlled CoreScope server for both apps. Cover supported and older servers, auth and CSRF failures, repeat watch/unwatch, unknown nodes, quotas, web/app parity, account isolation, and favorites import and conflict flows.
2. **Backend tests** with a fake clock and APNs/FCM adapters. Cover fresh-watch baseline, offline/recovery and battery transitions, stale ingest, email-only and push-only users, quota isolation, provider rejection and throttling, retries, restart recovery, token rotation, logout/account deletion and concurrent registration changes. Verify migration on an existing database with opted-out email users.
3. **Navigation and device tests** for foreground, background, locked screen, app not running, denied permission, notification channels, disabled push, logout, changed analyzer and missing nodes. Verify one account receiving on multiple opted-in devices and no delivery to a second account. Check iOS sandbox plus TestFlight production APNs, and Android internal testing on a Play-enabled device. Simulated payloads supplement the real provider tests.
4. **End-to-end acceptance.** On staging, star a node on the web and see it in both apps. Watch a test node from one app and see it on web and the other app. Generate a real analyzer transition, confirm delivery through APNs and FCM while both phones are locked, and tap through to the correct node and analyzer. Confirm email follows its own preference. Unwatch or disable a device and generate another transition to verify suppression. Repeat for recovery and after a backend restart.
5. **Release order.** Deploy the backward-compatible migration and capability flags with push off, ship watch management, enable staging delivery, then run an opt-in pilot with both analyzers before widening access. Track queue age, retries, expirations, provider acceptance and invalid-token rates without recording token or payload contents. Document credential rotation, diagnosis and a kill switch that stops queued push delivery without affecting watch management or mail.

## Release decisions and dependencies

- Confirm the managed and independent pilot analyzers, their operators, and the upstream contribution path. App releases need Apple push capability and signing access, Firebase project access, and a relay deployment with egress to both providers.
- Confirm email preference compatibility and the offline logout/registration lease policy before implementing delivery. Verify that Firebase configuration agrees with Android distribution targets.
- Assign ownership for the relay hostname, deployment, monitoring, and operator support. Verify cross-analyzer installation binding and isolation before advertising self-hosted push support.

## Platform references

- [Apple APNs registration](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns)
- [Apple APNs provider responses](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns)
- [Firebase Android client setup](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send/v1-api)
- [Pushover Message API](https://pushover.net/api)
