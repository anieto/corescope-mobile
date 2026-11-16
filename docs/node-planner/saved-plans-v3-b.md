# Saved Plans v3, deliverable B: portable results

Status: implemented November 16, 2026 on the local branch `feature/saved-plans-v3` (fixtures `c920dd3`, engine values `347cdba`, coverage `6dcf89e`, Link Profile `113c476`, limits in the commit that adds this note). Not released and not pushed. Formats: [saved-plans-v3-result-fields.md](saved-plans-v3-result-fields.md).

## What changed

- **Shared formats.** Coverage and Link Profile results are saved as `nodescope.coverage-result` and `nodescope.link-profile-result` version 1, identical on both platforms, so a study saved on one opens with its result on the other. Reading is strict, and a result is shown only for the inputs it was calculated from (its fingerprint must match the study's).
- **Coverage.** New results are saved in the portable format. Results saved earlier keep their legacy format, still open on the platform that wrote them, and move to the portable format when their study is saved again. Each result records when its calculation finished.
- **Link Profile.** A calculated Link Profile now saves its result. Reopening shows it without fetching terrain, with "Saved result · calculated …", until an edit clears it and Calculate runs again. iOS keeps the sample distances and bearing it calculated; Android keeps its sample coordinates.
- **Messages.** A result that can't be shown says why: not on this device, couldn't be verified, or needs another version.
- **Limits.** Measured from the real encoders: coverage worst case 3.1 MB (limit 8 MiB), Link Profile 28.8 KB (limit 64 KiB).

## Known limitation

Android's Link Profile has no refraction or sample-count controls (unchanged). An iOS study using them opens on Android with its result, but saving it again on Android resets both to the defaults and drops the result.

## Tester checklist

1. iOS: calculate an Estimated Coverage study and save it. On Android, open the same library (device transfer or a copied file): the result shows, without calculating. Repeat Android → iOS.
2. Same for a Link Profile: the verdict, chart and route show with "Saved result · calculated …"; nothing loads.
3. Edit a reopened Link Profile's height: the result clears and Calculate appears; nothing is fetched until it's tapped.
4. A study saved before B (legacy coverage result) still opens with its result on the platform that saved it; save it again and it opens on the other platform too.
5. Library row: a study with a result reads "Includes saved results" for both kinds.
6. A Link Profile whose result file is missing (restore without results) opens with "Saved result not on this device".

## Automated coverage

68 shared cases (`shared/fixtures/saved-plans/results/result-cases-v1.json`, from `make-result-fixtures.py`) pass on both platforms, and every valid case re-encodes to the same content: `PortableCoverageResultTests`/`PortableCoverageResultTest` (38) and `PortableLinkProfileResultTests`/`PortableLinkProfileResultTest` (30). End-to-end save and reopen: `SavedStudyRestorationTests` (iOS) and `SavedStudiesTest` (Android). Worst-case sizes: Android `PortableResultSizeTest`. Engine values: `NodePlannerLinkAnalyzerTests` (iOS), `CoverageTerrainMosaicTest` (Android).
