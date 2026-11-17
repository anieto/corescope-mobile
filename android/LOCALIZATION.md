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

Phase 3 formatting review (existing number and date formatting behavior retained):

| Area | Display-format sites |
| --- | --- |
| Shared | `Format.kt`: US compact counts, short date/time, duration and distances. |
| Explore | `ExploreModel.kt`: US summary SNR; `MyNodesScreen.kt` and `FavoriteChangesScreen.kt`: activity and review dates. |
| Nodes | `NodeAnalyticsLogic.kt`: US duration and chart dates; `NodeAnalyticsScreen.kt`: US percentages, daily rates and signal data, padded UTC hours; `NodeDetailScreen.kt`: SNR, RSSI, average hops and first/last-seen dates; `NodeBrowser.kt`: last-heard dates. |
| Live map | `MapSheets.kt` and `RouteDetails.kt`: US route SNR/RSSI; `GoogleMapEngine.kt`: cluster count abbreviation. |
| Channels and packets | `ChannelDetailScreen.kt`, `BeaconPackets.kt`, `MessagePacketScreen.kt`, `PacketDetailScreen.kt`, `PacketGroups.kt` and `RoutePicker.kt`: RF figures, received/sent times and history dates. |
| Observers | `ObserverDetailScreen.kt`: US voltage and noise floor, recent-packet RF figures; `BeaconObserverAnalyticsReader.kt`: timeline dates. |
| Coverage | `EstimatedCoverageScreen.kt`: grouped work counts, countdown, US coordinates, radius, height and RF results; `CoverageMap.kt`: US inspection RF figures; `EstimatedCoverageViewModel.kt`: coordinate fallback title and height editor decimal text. Validate the editor parsing contract before changing its display. |
| Link Profile | `LinkProfileScreen.kt` and `LinkProfileUnits.kt`: margin, dB, height, axis distances and calculated time. |
| Saved plans | `SavedPlansScreen.kt` and `SavedPlansImport.kt`: display dates for drafts, updates and exported packages. |
| Beacon / Node Check | `BeaconApi.kt`: retry time; `NodeCheckScreen.kt`: last-heard date and live test timer; `NodeCheck.kt`: fixed RF and relative time that appear in reports. |

Machine-facing uses remain fixed: public key normalization, hex fingerprints, map color
encoding, terrain tile and cache identifiers, and the saved-plans export filename.

Phase 4 defers Node Check rule findings and verdicts, the English glossary and What's
New JSON, and community source subtitles from the registry. Store listing copy is
outside this Android source change.

Residual text audit: the direct check is clean. It does not establish completeness for
text constructed before composition. In particular, some Explore metric details are
also embedded in CSV/JSON exports, planner and Coverage preset designations come from
the preset models, and some legacy exception paths pass through their original
messages. Review those paths before treating the English catalog as translation-complete.
Technical examples, fixed unit abbreviations, chart notation, and persisted draft
names and field keys retain their existing spelling.
