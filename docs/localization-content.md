# Translating bundled content

Interface strings live in the iOS String Catalog (`CoreScopeViewer/Resources/Localizable.xcstrings`)
and Android `strings.xml` (see `android/LOCALIZATION.md`). This page covers the content that ships as
JSON and is shared by both apps: the glossary, What's New and community source subtitles.

English is the source language. Translations are overlays: they name only what they translate, and
anything missing, empty or unknown falls back to English. A broken translation file is ignored, so
it can never hide the English content.

## Which language is used

Content follows the language the app's interface is running in, not just the device setting, so a
translated glossary never appears beside English screens:

- iOS: `Bundle.main.preferredLocalizations.first`, which only offers languages the String Catalog has.
- Android: the `content_language` string resource (`en` in `values/`, set in each translated
  `values-<lang>/strings.xml`, e.g. `pt-BR` in `values-pt-rBR/`).

For a tag like `pt-BR`, the apps try `pt-BR` first, then `pt`. Tags compare case-insensitively.
English (`en`, `en-GB`, …) never loads an overlay.

## Glossary — `glossary.<lang>.json`

Next to `glossary.json` (iOS `CoreScopeViewer/Resources/`, Android `app/src/main/assets/`; the two
copies must be identical). Sections are keyed by their English title, entries by id:

```json
{
  "version": 1,
  "language": "pt-BR",
  "sections": { "The mesh": "A malha" },
  "entries": {
    "node": { "term": "Nó", "summary": "…", "detail": "…" }
  }
}
```

Each field falls back to English on its own, so `{"node": {"summary": "…"}}` keeps the English term
and detail.

## What's New — `whats-new.<lang>.json`

Next to `whats-new.json`, both copies identical. Items are keyed by id; only `title`, `detail` and
`location` are translatable. Icons, destinations, platforms and versions always come from the
English file.

```json
{
  "version": 1,
  "language": "pt-BR",
  "items": {
    "saved-plans": { "title": "Planos salvos", "detail": "…", "location": "Explorar → …" }
  }
}
```

## Community source subtitles — `sources-v2.json`

A source can add an optional `subtitles` map beside its English `subtitle`. Keep `subtitle`: older
app versions read only that, and both apps ignore keys they don't know.

```json
{
  "id": "meshsorocaba",
  "subtitle": "Sorocaba, Brazil",
  "subtitles": { "pt-BR": "Sorocaba, Brasil" }
}
```

Update all three copies together: `CommunitySources/sources-v2.json` (served to the apps),
`CoreScopeViewer/Resources/sources-v2.json` and `android/app/src/main/assets/sources-v2.json`.

## Adding a language

Start with the term choices in [localization-terms.md](localization-terms.md), which also lists
how other MeshCore apps translate the same words.

1. Translate the String Catalog and add `values-<lang>/strings.xml`, including `content_language`.
2. Add `glossary.<lang>.json` and `whats-new.<lang>.json` to both apps. On iOS, add each file to the
   app target's Resources in `project.pbxproj` by hand (don't run XcodeGen).
3. When a release adds What's New items, add their translations in the same change.
