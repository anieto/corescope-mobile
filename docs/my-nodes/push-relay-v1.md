# NodeScope push relay v1

This contract is the staging baseline for the [watched-node notifications plan](watched-node-notifications-plan.md). CoreScope owns accounts, watches, preferences, and event detection. The relay owns device tokens and delivery through APNs and FCM. CoreScope email and Pushover use their own delivery adapters. Use distinct staging and production relay origins, credentials, and data stores. The intended production origin is `https://push.nodescope.app`.

## API conventions

Use HTTPS and JSON. IDs are opaque, case-sensitive strings. Send credentials in the Authorization header as scoped bearer tokens. Generate credentials and one-time proofs using at least 256 bits of entropy, store verifiers for bearer credentials, and scrub tokens, proofs, and provider requests from logs. Reject unrecognized protocol versions and bound request and response sizes. Reject redirects on CoreScope-to-relay requests so sender credentials stay on the configured origin.

Return error responses as `{"error":{"code":"subscription_inactive"}}`. Use 400 for invalid shape, 401 for missing or invalid credentials, 404 for unknown or out-of-scope resources, 409 for an incompatible replay, 410 for a known expired or revoked resource within the caller's scope, 429 with Retry-After for throttling, and 503 for temporary service failure. Return 202 only once a delivery is durably recorded. For a replay, return the existing delivery ID and current queue status.

## Analyzer enrollment

1. For staging, the relay operator issues a single-use enrollment code tied to a named analyzer and its canonical HTTPS origin. The CoreScope admin pastes it into **Connect**. A later hosted enrollment UI can issue the same code. No public self-enrollment is required for staging.
2. CoreScope calls `POST /v1/analyzers/enroll` on the relay with the code and a stable enrollment request ID. The relay returns `analyzer_id` and a sender credential. Codes expire in 15 minutes. Stage-one recovery after a lost enrollment response is to revoke the orphan enrollment and issue a fresh code.
3. CoreScope stores the origin, ID and sender credential in protected server configuration or account storage. It calls `GET /v1/analyzer` with that credential for **Test connection**. A successful response includes `analyzer_id`, `origin`, and `contract_version: 1`. Test connection sends no user alerts. A future test alert requires an explicit active subscription.
4. CoreScope advertises `nodeScopePush` in its authenticated notification settings only while enrolled and enabled. Advertise the relay origin, analyzer ID, contract version, and available platforms. Official app builds use their configured trusted relay origins before any device token is sent. The server never supplies an arbitrary token-registration destination.
5. Relay operators can rotate the sender credential and revoke the previous one. Revoking the analyzer immediately invalidates sender authorization and all of its subscriptions. Unenrolling in CoreScope disables local sends immediately and queues `DELETE /v1/analyzer` with its current credential. CoreScope clears that credential after revocation succeeds or the relay reports it already revoked. An operator can revoke it directly if CoreScope is unavailable.

## Device linking

| Endpoint | Caller | Contract |
| --- | --- | --- |
| `POST /v1/installations` | NodeScope app | Register the current APNs or FCM token. Body provides provider, token and build environment from app configuration. Return installation ID and an installation credential once. Retry only with a new registration if the credential response was lost. Enforce token uniqueness within provider and environment. Validate provider/app configuration on the relay and rate-limit initial registration. |
| `PUT /v1/installations/{id}/token` | Installation credential | Replace the token idempotently and advance its generation only if it changed. A late provider response may retire only the generation it addressed. |
| `POST /v1/installations/{id}/link-proofs` | Installation credential | Create a proof bound to this installation and a specific enrolled analyzer ID. The proof expires in five minutes and can be consumed once. |
| `PUT /api/account/notifications/devices/{installationID}` | Signed-in NodeScope app to CoreScope | Use the existing account session and CSRF checks. Send the proof, derive owner from the authenticated account, and call the relay using the analyzer credential. Return only subscription ID, status, and expiry. |
| `POST /v1/subscriptions` | Analyzer sender credential | Consume the proof and bind the supplied installation ID to the authenticated analyzer and a stable analyzer-local binding ID. The app's account ID, email, and credentials stay on CoreScope. An identical replay from that analyzer returns the same binding. A different binding may not consume a redeemed proof. |
| `DELETE /api/account/notifications/devices/{installationID}` | Signed-in NodeScope app to CoreScope | Disable the binding locally and queue its revocation. Return success after the local change commits. |
| `DELETE /v1/subscriptions/{id}` | Owning analyzer or installation credential | Revoke the specific subscription idempotently. Other analyzer subscriptions on the phone stay active. |

CoreScope stages the local binding before the relay call and activates it after the response is persisted. Retry the same binding ID after an ambiguous result. The relay gives orphaned subscriptions a finite lease. Use a uniqueness constraint for one current account binding per analyzer and installation. A relink to another account uses a fresh proof, replaces the old binding, and revokes its queued deliveries. Use a 30-day sliding lease for the pilot, renewed by authenticated foreground app use. CoreScope can renew a current binding using a fresh link proof.

Opt-out and sign-out immediately remove the local route and queue revocation through either trusted endpoint that remains reachable. The app persists pending installation-authenticated revocations across restart. Revocation prevents new delivery once acknowledged. Alerts already submitted to a provider can still appear. An offline app cannot guarantee immediate remote revocation. A revoked or inactive account may not enqueue alerts, and a fully unreachable binding expires.

## Delivery

CoreScope persists watch state and eligible push outbox rows in one transaction in `users.db`, through `internal/users`. Keep writes to the measurement database in the ingestor. The dispatcher rechecks the current watch, event preference, account status, and binding before sending. Email remains subject to its existing eligibility, quota, and unsubscribe behavior. Pushover has independent provider configuration and recipient consent.

Send `POST /v1/deliveries` with the analyzer credential:

```json
{
  "event_id": "0b163208-15db-4b65-882c-a11aad93dac7",
  "subscription_id": "sub_example",
  "kind": "node.heard_again",
  "node_pubkey": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "occurred_at": "2026-11-18T20:00:00Z",
  "expires_at": "2026-11-18T21:00:00Z"
}
```

Allow `node.not_heard`, `node.heard_again`, `node.battery_low`, and `node.battery_recovered` initially. Map CoreScope's existing offline and battery transitions into these kinds. Validate the lowercase full 64-hex key, timestamps and expiry. Use a one-hour maximum delivery age for the pilot. The relay derives the analyzer from the credential and resolves provider and environment from the authenticated subscription. Payloads accept no custom titles, URLs, APNs topics, or FCM project identifiers.

Use `(analyzer_id, subscription_id, event_id)` for durable deduplication. An identical replay returns the original delivery ID. A request with different contents at the same key returns 409. Keep deduplication records beyond the maximum retry window. Treat provider acceptance as accepted for delivery, with no assertion of device receipt. Stop on permanent failures, retire invalid provider tokens with a generation check, and retry transient failures with bounded backoff and Retry-After when present. Recheck expiry, revocation, quotas and the kill switch immediately before provider submission. Provider timeouts can lead to duplicate presentation if the provider accepted before the connection failed.

Provider payloads contain a short generic visible alert and versioned route metadata with analyzer ID, subscription ID, event ID, event kind, and node public key. The app resolves the analyzer through its saved local binding. Tapping opens the node only when the binding still matches the signed-in account and known source. If it does not, open a neutral notifications or source screen. Keep device tokens, node names, account identities, and user-generated content out of visible alerts.

## Implementation and staging gates

1. **CoreScope destination seam.** Make transition generation independent of the email-enabled flag while retaining baseline and stale-ingest rules. Keep filtering for email at its adapter, including unsubscribe and bounce behavior. Cover opted-out email with opted-in push and existing email regression cases. Follow the upstream repo's Go test requirements.
2. **Staging relay.** Implement manual enrollment, installation registration, signed-in account linking, revocation, and a durable delivery queue against fake providers. Verify idempotent retries, wrong-analyzer rejection, token rotation, expired leases, and source revocation before enabling provider credentials.
3. **Real providers.** Provision an HTTPS staging origin, APNs development and production credentials as appropriate for test builds, and a Firebase test configuration for the Android build. Enable staging-only app registration and source-aware notification taps. Use one controlled CoreScope analyzer first.
4. **Acceptance.** Enable an explicit watch and generate a real heard/not-heard transition on staging. Verify delivery to a locked iPhone and Android phone, and tap through to the correct analyzer and node. Then test opt-out, sign-out, and retry after a backend restart. Repeat enrollment on a second analyzer with the same phone and verify isolated watches and routing.

Keep CoreScope and the relay separately deployable. The contract does not require a public relay website. Deployment ownership, hosting runtime, staging provider credentials, and access to both test devices must be arranged before the real-provider gate.
