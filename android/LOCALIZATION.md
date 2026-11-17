# Android localization handoff

Phase 2 keeps the default English resources in `app/src/main/res/values/strings.xml`.
Debug builds include `en-XA` and `ar-XB`; release builds do not generate these locales.
Run `python3 android/check-compose-strings.py` from the repository root to check for
direct Kotlin literals at Compose text argument sites. This check supplements review
of text passed through variables, models, formatters and callbacks.

Keep externally supplied names, keys, packet payloads and region codes verbatim. Use
`UiText.Resource` or `UiText.Plural` for deferred app copy, and use `UiText.Raw` when
passing through text supplied by a data source. Resolve the value in the UI. Preserve
the existing wire and persistent identifiers when introducing resource labels for
enum values or fields. Some English count labels intentionally use the same text in
`one` and `other` because the original UI did so.

Phase 3 uses Android ICU's measurement system for the device locale when the distance
setting is absent. The US system uses miles and feet, the UK system uses miles and metres,
and the metric system uses kilometres and metres. DataStore stores an enum name only when
the user chooses a unit.

| Unit call site | Behavior |
| --- | --- |
| `Preferences.kt`, `MainActivity.kt`, `Format.kt` | Absent choices resolve from the current region. Settings names the three enum values explicitly. |
| `Format.kt` | `usesMiles` chooses the long-distance threshold and unit. `usesFeet` chooses the unit for short distances. |
| `EstimatedCoverageViewModel.kt`, `EstimatedCoverageScreen.kt` | `usesMiles` controls the initial radius and spacing, radius slider, and spacing choices and labels. `usesFeet` sets the height editor unit for the study. |
| `NodeScopeApp.kt`, `CandidateSitesScreen.kt` | Coverage receives the distance unit. Candidate site height editing and conversion use `usesFeet`. |
| `LinkProfileUnits.kt`, `LinkProfileViewModel.kt` | `usesFeet` controls height conversions, slider bounds, and the 20 ft or 6 m initial mast. |
| `LinkProfileScreen.kt` | The distance axis uses `usesMiles`. Elevation axis labels and height controls use `usesFeet`. Link distances use `formatDistance`. |
| `CoverageMap.kt`, `NodeDetailScreen.kt` | Map grid spacing and neighbour distances use `formatDistance`. |

The current formatting locale now supplies decimals for the following display calls.

| File | Changed display formats |
| --- | --- |
| `Format.kt` | Long distances and scaled compact counts. |
| `RouteDetails.kt`, `MapSheets.kt` | Route SNR and RSSI. |
| `ObserverDetailScreen.kt` | Battery voltage and noise floor in charts and telemetry rows. |
| `NodeAnalyticsScreen.kt`, `NodeAnalyticsLogic.kt` | Percentages, daily rates, SNR typical ranges and limits, median RSSI, signal axis labels, and duration abbreviations. |
| `LinkProfileScreen.kt`, `LinkProfileUnits.kt` | Link margin, RF measurements, antenna height, and distance axis decimals. |
| `EstimatedCoverageScreen.kt`, `CoverageMap.kt`, `CoverageRadioPresets.kt` | Display coordinates, radius, antenna and modem preset numbers, radio summaries, predicted receive levels, and margins. |
| `ExploreModel.kt`, `ExploreScreen.kt` | The average SNR tile formats the same value separately from exports. |
| `GoogleMapEngine.kt` | Cluster badges trim an optional decimal using the current locale. |

Existing default-locale RF formatting in channel and packet views, node details, and Node
Check uses the locale already. The decimal editors in Coverage, Link Profile, and Candidate
Sites accept commas and dots. Existing date patterns are unchanged.

Fixed locale uses include the US decimal in Explore CSV and JSON and the US date in the
saved-plans filename. Coverage draft coordinate titles and programmatic height editor values
remain fixed because they may be stored or exported. The Skadi terrain filename, map color
hex, packet hop hex, certificate fingerprints, channel keys, terrain digests, portable input
fingerprints, deployment revisions, and response cache digests use a fixed locale. Public key
normalization, API and URL parameters, and structured JSON values also remain locale-neutral.

Phase 4 defers Node Check rule findings and verdicts, the English glossary and What's
New JSON, and community source subtitles from the registry. Store listing copy is
outside this Android source change.

Explore exports preserve English metric details and their existing CSV columns and JSON keys.
Preset IDs and raw compass abbreviations remain stable. UI code can resolve each compass point
through its display resource. The Android app currently has no screen that renders these points.

Known app-authored errors resolve through Android resources. Unclassified analyzer exceptions
use a localized fallback for the operation. Raw map SDK diagnostics remain visible when they
help with support, and log-only messages retain their original wording. Technical examples,
fixed unit abbreviations, chart notation, and persisted draft names and field keys retain
their existing spelling.
