# Tablet and foldable manual checklist

Implementation: September 26, 2026. Debug APK compiled successfully; runtime checks below have not been performed.

## Install

Use Android Studio's Run action on your chosen device, or install `android/app/build/outputs/apk/debug/app-debug.apk`. Update the existing app without uninstalling or clearing data so saved sources, monitored channels, and favorites remain available.

## Tablet browsing

- Open Observers and Channels on a wide tablet window. Expect a list on the left and a selection prompt on the right.
- Select several rows. Expect one highlighted row and matching details on the right; the list should stay visible and should not jump back to the top.
- Scroll the list and details independently. Expect neither pane to move the other.
- Close details. Expect the prompt to return and the list to keep its position, search, and filters.
- Search, sort, filter by region, and refresh. Expect existing behavior to remain available. A filter may hide the selected row without closing its details; channel messages must follow the chosen region.

## Folding, rotation, and resizing

- Open an observer, scroll down, and expand Technical details. Rotate or resize to a narrow window and back. Expect the same observer, scroll position, and disclosure state to remain.
- Open a channel and scroll to older messages. Fold/unfold or resize across the pane boundary. Expect the same conversation and reading position to remain, with no duplicate detail screens.
- On a foldable outer screen, expect phone navigation. On a sufficiently wide inner screen, expect both panes. The threshold is 720 dp of **content width after the navigation rail and insets**, so some smaller inner screens remain single-pane.
- On a device with a separating vertical hinge, expect neither pane's text nor actions to be obscured by it. Dedicated stacked tabletop mode is not part of this change.
- Try split-screen or a resizable desktop window. Expect a smooth switch to single-pane before the list/detail become too cramped.

## Navigation and retained actions

- On a phone/narrow window, select a row and use Back. Expect the original list with its search, filters, and scroll position.
- In Channels, open **View packet**. On a wide window, expect it to replace only the conversation pane while the channel list remains visible and usable. Use the toolbar Back arrow and system Back separately; both should restore the same conversation position.
- While a packet is open, fold/unfold or resize. Expect that packet to remain open, filling the content area on narrow screens. Select another channel on a wide window; expect its conversation and no stale packet. Also try replay from packet details and return using the Channels destination.
- Confirm messages still alternate left/right when the sender changes and consecutive messages from one sender stay grouped. Every inspectable message must retain **View packet**.
- Favorite/unfavorite an observer and a channel. Expect Explore favorites to update. Open both from Explore and confirm Back returns to Explore.
- Open an observer from node details and via a shared `nodescope://observer/...` link; open a shared channel link. Expect the selected detail on narrow screens and the selected detail alongside its list on wide screens. Back must return to the originating screen/list.
- Add a monitored channel. Expect to return to Channels with the new channel available. Stop monitoring a test channel and confirm the old detail closes and the list updates; a server-provided channel can remain in the server section.
- Switch tabs and return. Expect selection to remain. Change analyzer source and confirm no selection or data from the previous analyzer is shown.
- From Explore, open active observers, change that filter manually, open a detail, and return. Expect the manual filter to stay applied.

## Presentation and failure states

- Try light/dark appearance and 200% font size. Expect readable selected rows and reachable header/actions. At large text sizes two-pane mode requires 900 dp of content width; single-pane fallback is intentional.
- Check long observer/channel names and long messages. Expect wrapping or sensible toolbar truncation without overlapping controls.
- With TalkBack, check list row selection, pane transitions, Close details/Back, favorite, overflow, and View packet labels.
- Disconnect the network and refresh. Expect existing loading/error/cached-data feedback and functional navigation in either pane.
- Open a shared link to a missing observer/channel. Expect an explanatory empty/error state, with Back/Close still usable.
