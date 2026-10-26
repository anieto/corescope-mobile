# iOS page backgrounds

When creating or changing SwiftUI screens in `CoreScopeViewer`, follow the shared mesh background convention:

- Apply `.meshPageBackground()` to the main content container of ordinary pages, detail screens, and sheets. This provides the subdued mesh in light and dark mode and hides the default List/Form scroll background.
- For adaptive sidebar lists, use `.adaptiveSidebarListStyle(meshBackground: true)` instead of adding another mesh background.
- Keep the background attached to the page viewport, outside its scrolling content, so the pattern stays still while scrolling.
- Cards, list rows, and reusable controls should not add their own mesh. Preserve their readable surfaces over the page background.
- Keep actual maps and dedicated chart workspaces, including Node Analytics, free of decorative mesh. Link Profile uses subdued mesh behind its editor and compact controls panel, with an opaque result-card surface protecting the terrain chart. Sheets opened from these screens can use the standard subdued mesh.
- Reserve the full-strength `MeshBackground()` treatment for About and Settings unless the user requests otherwise. Use the subdued page helper for new screens by default.
- Reuse `Features/Shared/MeshBackground.swift`; do not duplicate its drawing code or introduce separate per-screen palettes.
- Respect Settings → Appearance → Background Texture. The shared mesh view observes this persisted preference (enabled by default), removing only the decorative lines and nodes when disabled and keeping the gradient.
- When reviewing new screens, check light and dark mode, nested navigation, sheets, and compact and wide layouts. Verify that default scroll backgrounds do not conceal the mesh and that text and charts remain readable. Report any visual checks that could not be performed.

Example:

```swift
List {
    // Page content
}
.meshPageBackground()
.navigationTitle("New Page")
```
