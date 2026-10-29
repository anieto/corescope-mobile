# 0.9.0 account acceptance

Status: not yet run end-to-end. Synthetic model/transport tests are separate evidence.
Run on iOS and Android using a disposable account on a controlled CoreScope server.
The two-account, reset and conflict checks need a second test account and the server website.
Do not put passwords, cookies, email addresses or raw settings payloads in the results.

## Setup

Record app build, platform/device/OS, server release and test date. Use three distinct test nodes:
D (device favorite), A (account favorite), and C (claimed on the website). Record aliases only.
Before testing, note the existing device favorites so they can be checked afterwards.

## Combined Favorites and Activity

1. Signed out: save D on the device. Favorites and Activity show D without a login requirement.
2. On the test website, favorite A and claim C. Sign in in the app. Favorites and Activity show
   D, A and C without pressing Copy. Labels distinguish Device, Account and Claimed. No device
   favorite is uploaded merely by signing in or refreshing.
3. Add D to the account on the website, then Refresh from account. D appears once and shows both
   memberships. Its original device ordering is retained; account-only rows follow deterministically.
4. Remove D from device in Favorites. D remains through account membership. Remove D from account,
   choose Sync now, and verify it disappears from the account while unrelated memberships remain.
5. Favorite C on the website as well as claiming it. Refresh, remove its account-favorite membership,
   and sync. C remains claimed, including on the website. Unclaiming is managed on the website.
6. Remove A on the website and refresh. A disappears unless it also has device membership. A must
   not reappear on a subsequent sync without an intentional addition.
7. Quit and reopen the app. Separate device/account memberships and pending edits survive. Switch
   source and return: no nodes from another source appear.

## Explicit transfer and logout

1. Import device favorites; before Sync now the website must remain unchanged. Sync once and verify
   only the intended account favorites changed. Channel and observer favorites stay local.
2. Copy an account node to device. It now has both memberships. Copying again creates no duplicate.
3. Sign out without copying: account-only rows disappear; original device favorites and explicit
   copies remain. Sign back into the same account and verify its current server selections return.
4. Sign into the second account: the first account's nodes and pending edits must not appear or upload.
5. Test Copy selections and sign out separately. Copies survive logout, but signing into another
   account must not upload them without explicit import and sync.

## Failure and concurrency cases

1. Stage an account removal, lose connectivity and try sync. The edit remains pending. Reconnect,
   refresh and sync: the intended edit is preserved; unrelated website additions are preserved.
2. Expire/revoke the test session on the server. The app requests sign-in and must not upload to a
   different account. Reauthenticate as the same account to reconcile saved pending edits.
3. With pending edits, reset the account settings document on the controlled server. Sync pauses.
   Test Use server selections and Restore saved account favorites separately. Neither choice should
   silently upload data; restoration requires Sync now.
4. Make concurrent website edits during sync to force conflict. Changes remain pending; there is no
   immediate write-retry loop. Sync again after the one-minute throttle interval and check the result.
5. Configure controlled 429 responses. Respect Retry-After and verify no repeated settings writes
   occur during cooldown, including after leaving/reopening the account page.
6. Start a slow sync, then leave/background/change source. Ensure no stale UI update or cross-source
   write occurs. On iPad/multiple windows, sign out in one window; an old window must not upload.
7. Sign out offline. Local account credentials and personal state are removed; the app explains
   that server revocation could not be confirmed. Device favorites remain.

## Presentation checks

Verify Account nodes shows names with shortened public keys; unknown names retain stable IDs.
Check the Import popup anchor, both sign-out choices, all controls above the tab bar, maximum text
size, VoiceOver/TalkBack labels, and narrow/wide layouts. Verify removal names the membership.
Check Activity uses last-heard and checked timestamps correctly while offline/partially loaded.

## Results

| Platform/build/device | Server release | Scenario | Result | Evidence / follow-up |
|---|---|---|---|---|
| Pending | Pending | Pending | Not run | |

## Additional concurrent-edit check

With two app windows open, start a slow settings refresh/sync in one and remove account membership
from Favorites in the other. Test once during the settings read and once during the write. The
removal must remain pending after completion, the newer-edits message must appear when appropriate,
and the next eligible sync must upload it. A sync with no membership changes must make no settings
PUT merely to rewrite JSON formatting.
