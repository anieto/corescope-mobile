# NodeScope 0.9.0 release notes

Status: complete, marked by user direction on 2026-10-20. Store submission/approval is not recorded.
Unchecked follow-ups below preserve the actual validation and distribution record; completion
does not imply those checks were executed.
Scope: Favorites, Activity, optional CoreScope account node sync. Deferred analytics dashboard,
Changes UI, exports and live Activity updates are not advertised in this release.

## App Store — What's New

Keep your saved nodes together in Favorites and follow their recent activity. Activity shows
when a node was last heard and when its information was checked, including saved or incomplete data.

Connect an optional CoreScope account to see account favorites and claimed nodes alongside device
favorites. Node names and membership labels help you distinguish them. Import device favorites or
copy account nodes explicitly, and sync account edits when you're ready. Device favorites remain
available when you sign out. Browsing does not require an account.

## Google Play — What's New

Follow saved nodes in Favorites and Activity, with last-heard times and clear data freshness.
Optionally connect a CoreScope account to see account favorites and claimed nodes alongside device
favorites. Import, copy and sync selections explicitly. Includes improved account layouts and
accessibility labels. Browsing remains available without signing in.

## Review notes to finalize

Public network browsing remains read-only. Optional CoreScope sign-in adds writes to the user's
personal account favorites on their selected server. It does not manage node ownership or network
configuration. Registration, account recovery and claimed-node management remain on the server website.
Supply approved reviewer access separately if needed; never place credentials in repository docs.

## Recorded validation and submission follow-ups

- [ ] Complete [controlled-server account acceptance](account-acceptance.md) on both platforms.
- [ ] Record user-owned simulator/device smoke tests, including Import/Sign out popup placement.
- [ ] Check maximum text size, VoiceOver/TalkBack actions and reading order, narrow/wide layouts.
- [ ] Measure representative large Favorites collections and scrolling. Android bounded-loading
      evidence is recorded in [performance validation](performance.md); device measurements remain open.
- [ ] Complete CoreScope and Beacon beta smoke tests: guest browsing, source switching, detail
      navigation, offline/partial Activity, cancellation and cooldown behavior.
- [ ] Run final iOS release regression after fixes; prior focused account tests are separate evidence.
- [ ] Review privacy/store declarations against optional credentials, session storage and account sync.
- [ ] Capture screenshots with synthetic names/data: Favorites memberships, Activity freshness,
      signed-in account controls, and a guest/Beacon flow. No personal account details.
- [ ] Finalize store copy, version/build numbers, signing, archives and distribution artifacts.

Source versions are iOS 0.9.0 (build 4) and Android 0.9.0 (code 14), prepared for the
0.9.0 repository checkpoint. Store uploads and unchecked follow-ups remain unrecorded.

## Verification evidence

- 2026-10-19: iOS build passed after focused accessibility/layout fixes; Android assembly and lint passed.
- 2026-10-20: four contract-tool tests passed. Full Android regression initially found one
  date-sensitive Beacon test: fixed observations fell outside the real-time retention window.
  The backend now accepts a clock with the existing system-clock default; the fixture test supplies
  a fixed time derived from its observations. Production retention behavior is unchanged.

Build/unit-test success does not close controlled-server, device, performance or submission gates.

Final rerun: 329 Android tests, 328 passed and one skipped; zero failures/errors. Android debug assembly and lint pass.
