# Custom Home Layout Design

## Problem

Voyager's Home screen has a fixed sequence of storage access, active sessions, quick access, remote connections, bookmarks, and folders. Issue #19 asks users to be able to remove or move Home sections, or alternatively to replace Home with a chosen startup directory. The reporter did not identify a preferred outcome, so this design implements complete section visibility and ordering while preserving the existing layout by default.

## Requirements

- Let users show or hide every logical Home section from Settings.
- Let users move every section up or down with controls that work with touch and accessibility services.
- Preserve the current Home sequence for existing installations until a user changes it.
- Persist visibility and ordering across process restarts.
- Recover safely from unknown, duplicate, removed, or newly added persisted section names.
- Keep Settings reachable regardless of which sections are hidden.
- Preserve existing permission gating and dynamic-content rules inside each section.

## Section Model

`HomeSection` defines six stable persisted identifiers: Storage, Active Sessions, Quick Access, Remote Connections, Bookmarks, and Folders. Storage contains the current storage-volume or limited-access card, Document Tree card, and Trash card. The remaining sections map to their existing Home content.

`HomeLayout` contains the complete ordered section list and a hidden-section set. Its persistence parser drops unknown and duplicate names, then appends any known section missing from stored order. This means a section introduced by a future release appears at the end instead of disappearing. Unknown hidden names are ignored. Pure operations return a new normalized layout when a section is moved or its visibility changes.

## Persistence and State

Preferences DataStore stores the section order and hidden set as comma-separated enum names. `PreferencesManager.homeLayout` decodes both keys together. Visibility and move updates use DataStore's atomic `edit` block and decode the latest stored layout inside that block, preventing rapid controls from overwriting an earlier change. `FileBrowserViewModel` exposes the layout as an eager StateFlow and delegates Settings actions to these atomic updates.

## Settings UI

Settings adds a Home layout section with one row per section in its current order. Each row has a visibility switch and Move up and Move down icon buttons. The first row's up button and last row's down button are disabled. Hidden rows remain in the editor and can still be reordered or restored. Content descriptions include the section label and action.

## Home Rendering

HomeScreen collects `homeLayout`, iterates its visible ordered sections inside the existing LazyColumn, and emits the current content for each section. Hiding a section removes its entire group, including its header. Reordering only changes group placement. A chosen section can still render no content when its existing conditions are unmet, such as Active Sessions with no sessions or Bookmarks with no bookmarks. The Settings icon remains in the fixed top app bar.

## Testing

- Unit-test default order, malformed persistence normalization, visibility changes, move boundaries, and round-trip serialization.
- Add a Settings Compose test that hides a section, reorders another section, verifies accessibility controls, and confirms persistence in a new view model.
- Add a Home Compose test that persists a custom layout, verifies a hidden section is absent, and compares rendered bounds to confirm section ordering.
- Run the full unit, lint, debug assembly, release assembly, and connected-device gates.
- Manually inspect the Home layout editor and a customized Home screen on the connected Android 16 device.

## Non-goals

- Replacing Home with a startup folder is not included because the issue explicitly offered section customization as an alternative outcome.
- Drag-and-drop gestures are not included; explicit move buttons provide deterministic and accessible ordering.
- Content inside a section is not individually customizable.
