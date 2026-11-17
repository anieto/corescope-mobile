# Store listings

The text pasted into App Store Connect and the Google Play Console, one file per field, so each release
starts from the current wording. Edit these first, then copy them into the stores.

| Store | Field | File | Limit |
|---|---|---|---|
| App Store | Subtitle (App Information) | `app-store/<lang>/subtitle.txt` | 30 |
| App Store | Promotional text (changeable without a release) | `promotional-text.txt` | 170 |
| App Store | Keywords, comma-separated, no spaces | `keywords.txt` | 100 |
| App Store | Description | `description.txt` | 4000 |
| App Store | What's New in This Version | `whats-new-<version>.txt` | 4000 |
| Google Play | Short description | `google-play/<lang>/short-description.txt` | 80 |
| Google Play | Full description | `full-description.txt` | 4000 |
| Google Play | Release notes | `release-notes-<version>.txt` | 500 |

Languages: App Store `en` (English U.S.), `es` (Spanish (Mexico)), `pt-BR` (Portuguese (Brazil));
Google Play `en` (en-US), `es-419` (Spanish – Latin America), `pt-BR`.

Keep translations in step with the English. Use the app's terms from
[localization-terms.md](../localization-terms.md). Choose keywords per language rather than translating
them; MeshCore, LoRa and CoreScope are left out of the Spanish and Portuguese keywords because the English
keywords already cover them.

The Google Play text describes only what the Android app has. Field Tools is iOS-only for now.
