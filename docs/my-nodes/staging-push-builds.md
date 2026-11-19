# NodeScope watched-node push pilot

The pilot uses the same identities as the public apps. Use dedicated test
phones or replace the existing installation on each test device with a
compatible signed staging build. Android requires the same signing key to
upgrade an existing installation. Keep both builds pointed at
`https://push-staging.nodescope.app`.

## iPhone

Use the CoreScopeViewer scheme with the **Debug** configuration, which
enables `NODESCOPE_PUSH_STAGING` and the APNs development entitlement. The
bundle ID is `com.btdev.nodescope`. The staging build number is 3. Install a development-signed
build on an enrolled physical iPhone with a provisioning profile for the
app ID that includes Push Notifications.

The project links the shared RF XCFramework. For a fresh worktree, build it
using the repository's RF engine instructions or link the matching build from
another checkout at `shared/rf-engine/build/XCFrameworks/release/NodeScopeRF.xcframework`.

## Android

Build from the Android directory with `./gradlew :app:assembleStaging`. The
package is `org.nodescope.android`, version name 0.11.1 and staging version
code 30. The Staging build uses the staging Firebase project.

Register an Android app with this package in the dedicated Firebase staging
project. Put these client configuration values in the local Android
`local.properties` or supply them as build environment variables. They can
be read from that app entry in Firebase's Android configuration.

| Setting | Firebase Android config field |
| --- | --- |
| `STAGING_FIREBASE_APP_ID` | `client_info.mobilesdk_app_id` |
| `STAGING_FIREBASE_API_KEY` | `api_key[].current_key` |
| `STAGING_FIREBASE_SENDER` | `project_info.project_number` |
| `STAGING_FIREBASE_PROJECT` | `project_info.project_id` |

Use the entry with the matching package name. Keep relay service-account
credentials and Apple provider keys on the relay. They never belong in the
app or the repository. A Staging APK built with missing client values lets
you test the app shell and leaves push controls unavailable.

## Device test

1. Select the enrolled staging analyzer and sign into **CoreScope Account**.
   Add a test watch through the staging analyzer's existing web controls.
2. Choose **Enable watched-node alerts**, then allow OS notifications.
   Each app registers its device token directly with the staging relay and
   sends a short-lived link proof to the signed-in analyzer.
3. Lock both phones and trigger a watched-node change on the staging feed.
   Open the notification on each device. The app verifies that the active
   analyzer and signed-in account still own the subscription before opening
   the node. Leave the staging analyzer selected during the pilot.
4. Disable alerts, trigger another change and confirm it is suppressed.
   Repeat after signing out. Relay revocations that fail offline stay queued
   locally and retry when the app returns to the foreground.

The CoreScope account screen renews a linked device when its lease nears
expiry. System permission changes and provider token changes are checked on
foreground entry. A notification accepted by a provider before opting out
can still appear.

## Production release builds

The 0.11.1 Release builds use `https://push.nodescope.app`
and keep their relay installation credentials separate from staging. The
bundle ID and application ID are unchanged.

**iOS** — Open `CoreScopeViewer.xcodeproj`, select the **CoreScopeViewer**
scheme and choose **Product → Archive** for a generic iOS device. That
scheme archives the **Release** configuration with the production APNs
entitlement. Use App Store Connect distribution in Xcode Organizer. Install
from TestFlight to verify production push. The scheme's normal Run action
still uses Debug and sandbox APNs.

**Android** — Put the `nodescope-production` Firebase Android app
configuration at `android/app/src/release/google-services.json`. This file is
git-ignored. Set the usual Play upload signing properties in
`android/local.properties`, then run from `android/`:

```sh
./gradlew :app:bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab` to the Play closed testing
track. The Release build registers as `fcm/production`. Install it from Play to
verify production push.

Check that the proposed build number and version code are unused in App
Store Connect and Play Console before uploading. With both store-installed
builds, sign into the public MeshTexas analyzer, enable watched-node alerts,
and verify delivery and tap-through on each phone.
