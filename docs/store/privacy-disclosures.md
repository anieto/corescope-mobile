# Store privacy disclosures

The answers for App Store Connect (App Privacy) and the Google Play Console (Data safety), kept here so
each release can check them against what the app does. They follow the
[privacy policy](https://nodescope.app/privacy/). Updated for 0.11.1, which adds watched-node alerts
through the NodeScope push relay (push.nodescope.app).

## What leaves the device, and who receives it

| Data | Receiver | Counts as collected by us? |
|---|---|---|
| Analyzer API requests, sign-in credentials, synced favorites, repeater claims and edits | The analyzer the user picks | No. The user chooses the server, and NodeScope sends nothing from it to us. |
| Push token, random installation ID, which analyzers the device gets alerts from, short alert records | NodeScope push relay (ours) | Yes, only while alerts are turned on. |
| Firebase installation ID and device information (Android only) | Google, through Firebase Cloud Messaging | Yes, on Android, as a service provider delivering alerts. |
| Two map points, possibly the current position | Open-Meteo (Link Profile) | Unchanged from 0.11.0. Sent on request for real-time servicing and not stored by us. |
| Terrain tile requests | Amazon Web Services | Unchanged; no user data. |
| Map tiles; Google Maps SDK data when Google Maps is chosen (Android) | Apple, CARTO, Google | Unchanged from 0.11.0. |

The relay stores no account details, email addresses, locations or messages, and isn't linked to the
user's identity: it never learns who the account is. NodeScope has no in-app account creation, so no
in-app account deletion is required.

## App Store Connect: App Privacy

Change from 0.11.0 ("Data Not Collected"): **Data Collected**, one type.

- **Identifiers → Device ID**
  - Purpose: **App Functionality**
  - Linked to the user's identity: **No**
  - Used for tracking: **No**

Nothing else is collected. Firebase isn't in the iOS app. Credentials go only to the analyzer the user
picks, so they aren't declared.

## Google Play Console: Data safety

Change from 0.11.0: add one data type. Leave the existing location and Google Maps answers as they are.

- Does the app collect or share user data? **Yes**
- Is all user data encrypted in transit? **Yes**
- Can users request that their data be deleted? **Yes** (turning alerts off ends the link at once; the
  rest on request, see the policy)
- **Device or other IDs**
  - Collected: **Yes**. Shared: **No** (Firebase Cloud Messaging acts as a service provider)
  - Processed ephemerally: **No**
  - Required or optional: **Optional** (only when watched-node alerts are on)
  - Purpose: **App functionality**
