# NodeVision plan

NodeVision uses the rear camera to show the approximate direction and distance to mesh nodes with reported coordinates. Ship both free roam and pinned guidance on iOS and Android.

- **Explore → NodeVision** opens in free roam. Use the subtitle “Use your camera to find nearby nodes”.
- **Open in NodeVision** opens a focused view directly from node details on both platforms. In discovery, **Focus on node** keeps one node highlighted while panning and gives it edge arrows. **Clear focus** returns to browsing. Keep legacy iOS Guide to and Guide Me behind the internal flag.
- **Nearby** lists every eligible node within the discovery radius, sorted by distance. The provisional five-marker cap applies only to the camera view. Hide decommissioned nodes in discovery, while preserving direct access from node details.

## Camera and marker design

- Stack the blue node name above the distance, such as **150 ft away**. Allow two lines for the name before truncating it. Use Monaspace Neon for titles, node names, and numeric values, with system type for labels, controls, and explanatory text.
- Give the marker a short, centered teardrop tip. Fill the body and tip as one shape, with a continuous bright orange outline for the pinned target in both themes. Keep distance and status text neutral and readable over the camera. Use **Approx. elevation** for uncertain estimates and **Elevation Uncertain** when the vertical position is only a bearing guide.
- Give free-roam markers three subtle distance tiers. Nearby markers are a little larger and more prominent, and farther markers are smaller and quieter with legible text. Prioritize the pinned marker when labels overlap, then the nearer node. Keep omitted markers accessible through Nearby.
- Use one themed surface for the status area, title, and compass, with the camera showing faintly through it. Use prominent major and minor ticks and a fixed orange center mark. Apply the same blue gradient as the marker and bottom card, at lower intensity in the header.
- In light mode, use a subtle top-to-bottom blue fade with a faint tint at both ends, stronger text contrast, and a slightly thicker pinned outline. Keep the existing diagonal blue fade in dark mode. The marker and bottom card use a soft shadow. The bottom card has a blue border. Keep decorative mesh out of the camera overlays, and use the app’s existing styles for sheets and panels.
- Present distance once. Show it in the marker while on screen, and center the name and distance together in the bottom card when the marker is offscreen. When the marker already shows the name and distance, the card can show reported install height as a separate value without repeating them. Keep the full name available if the marker truncates it.
- Carry these preferences into Nearby, free roam, and wider layouts on both platforms. Check legibility over bright and dark camera scenes in both themes.

## Direction confidence and navigation

- Hide directional markers and the edge arrow when heading is unreliable, the camera points nearly straight up or down, or location is stale. Keep Nearby available and show distances only while the location fix supports them. When the target is within the current location uncertainty, show “Near reported location” and suppress the arrow.
- Use the visible camera preview’s horizontal angle scale for both marker tips and compass ticks. Account for crop and device rotation. Use a small buffer at the edge so the pinned marker and offscreen arrow do not flicker between states.
- Tapping a marker or Nearby row opens a compact preview. Expand it for observed activity and separately labeled owner-reported antenna and power details. **Focus on node** is a separate action, and **Open node details** exposes more. If camera access is denied or the preview is unavailable, keep Nearby accessible.

## Tablets, foldables, and resizable windows

- Choose the layout from the available window space and any separating hinge. On phones and folded cover screens, keep the camera full screen, place the pinned target card at the bottom, and open **Nearby** as a sheet.
- On tablets and unfolded devices with sufficient space, keep the camera dominant and show **Nearby** and target details in a side panel. In split-screen, return to the compact layout when space is limited. If a hinge separates the window, fit the preview within one usable area and place controls in the other when they fit.
- Align compass ticks, marker tips, and the offscreen arrow to the visible camera pane. Recompute the angle mapping when the pane resizes or the side panel opens or closes. Preserve the pinned target through rotation, resizing, and fold or unfold transitions.
- Keep the stacked markers and existing blue/orange theme readable in both light and dark appearance.
- Include a wide camera pane in the one-target prototype. Check portrait, landscape, and a resized window, plus fold and unfold on available hardware. Build the finished side panel in the discovery milestone.

## First field test

On a real iPhone and Android phone, choose a known target with clear line of sight. Check it from two starting points, turn through north, rotate and tilt the phone, and walk toward the target. Record observed pointing error on both devices before agreeing on a release tolerance. Confirm that distance decreases as expected and that unreliable direction suppresses the marker and arrow. Use this test to validate one target before tuning marker density or distance tiers.

## Implementation milestones

1. Prototype one target on each platform with AVFoundation and Core Location/Core Motion on iOS, and CameraX and native location and sensor APIs on Android. Reuse existing field geometry. Verify the rear camera’s bearing, turning through north, tilt, offscreen arrows, declining distance as the user approaches, and unreliable heading on real phones.
2. Add both entry points, discovery, pinning, and Nearby. Reuse the active analyzer’s node catalog and cache. Start with up to five visible labels within 10 km and tune on hardware. A pinned target can remain outside that range.
3. Add optional owner-reported maintenance status and hide decommissioned repeaters from free roam by default. Keep metadata and observed activity distinct. Put antenna details, power source, and notes in selected node details.
4. Verify permissions, foreground camera and sensor lifecycle, fallback to Nearby or the guide, shared geometry cases, VoiceOver and TalkBack, and compact and wide layouts. Use a live preview only, with no recording, frame uploads, or location trail.

## Running the one-target prototype

- **iOS:** Open a node with coordinates and tap **Open in NodeVision** on its detail page.
- **Android:** Open a node with coordinates and tap **Open in NodeVision** on its detail page.
- Choose a node whose reported position can be checked from a public spot. Use a reference bearing from each starting point, then record the difference between the camera's center heading and that bearing when the marker is centered. Repeat while panning across north and after rotation or resizing. Verify that the offscreen arrow replaces the marker, that measured distance changes as expected over a meaningful walk, and that aiming the camera nearly vertical hides the direction.
- Check foreground recovery by closing and reopening NodeVision and by briefly switching apps. Deny camera and location separately and confirm that Settings and the exit remain reachable. Check light and dark appearance over bright and shaded scenes.
- Run the wide preview checks on a tablet, resizable Android window, or unfolded device where available. The iOS host currently requires full-screen presentation on iPad and restricts iPhone to portrait, so iPad landscape is the applicable iOS prototype check.

## Estimated vertical placement

For a claimed repeater with a reported antenna height, use the same above-ground interpretation as Node Planner. The CoreScope v3.18 API describes the value in meters and does not specify a vertical reference. Add the reported height to the existing Copernicus elevation estimate for the repeater coordinates. Only those coordinates are used for the terrain lookup. Use recent phone altitude referenced to mean sea level and the rear camera pitch to estimate the vertical marker position. Android uses its mean sea level altitude APIs on Android 14 and later. If height inputs are missing or too uncertain, use a bearing guide that responds to camera tilt and starts at 43% of the preview when held level. The fallback has no measured target elevation. Check the result outdoors against a known installation and verify the fallback on a repeater without a reported height.

## Follow-up: Check link from here

After the discovery flow is verified, add an on-demand **Check link from here** action to the expanded node card. Use the current device location and the node’s reported location as endpoints. Reuse Link Profile and prefill supported owner-reported antenna inputs when available. Show modeled terrain and Fresnel clearance separately from estimated RF link margin. The latter needs radio assumptions such as frequency, power, and antenna settings. Offer the full Link Profile for details and input changes. Reserve observed reachability for actual radio evidence, and evaluate links only when the user asks.
