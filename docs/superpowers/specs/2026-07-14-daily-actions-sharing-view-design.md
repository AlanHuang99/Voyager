# Voyager Daily Actions, Sharing, and View Options Design

## Purpose

This milestone makes Voyager's most common selected-item actions easier to find and safer to use. It adds per-operation Trash versus permanent deletion, exposes Android sharing for eligible local and document-tree files, promotes rename when one item is selected, adds a compact list presentation alongside the existing list and grid modes, and provides basic file details without changing Voyager's established Compose, Material 3, ViewModel, provider, or DataStore architecture.

The user explicitly requested uninterrupted autonomous work and named direct deletion, Trash choice, viewing modes, sharing, and renaming as priorities. That instruction supplies approval to choose the conservative implementation described here without pausing for another review gate.

## Audit scope

The audit covered the Home screen, local browser, current-folder search, list mode, grid mode, single selection, the selection overflow menu, and the delete confirmation flow on a K60 running Android 16. Fresh screenshots and UI hierarchy captures were inspected in portrait and landscape. Source inspection covered `BrowserScreen`, `FileListItem`, `FileGridItem`, `FileUtils`, `FileBrowserViewModel`, `PreferencesManager`, the FileProvider manifest configuration, and existing JVM and instrumentation coverage.

## User goal and accessibility target

A daily file-manager user should be able to select an item and immediately understand how to share, rename, copy, move, inspect, or delete it. Destructive choices must clearly distinguish recoverable Trash from irreversible deletion. View controls must state the destination mode instead of presenting an ambiguous toggle. Changed controls should retain at least 48dp touch targets, meaningful TalkBack labels, readable narrow-phone layouts, and stable state through configuration changes.

## Confirmed strengths

- Voyager already provides persistent list and grid modes, current-folder search and type filters, sorting, multi-selection, copy and cut, responsive browser layouts, clear loading and empty states, and per-volume Trash.
- The selected row has a strong visual state and an explicit checkbox, while the contextual top bar clearly reports the selection count.
- File rows show useful size and modification metadata, and grid items use familiar file-type icons and two-line names.
- The existing `FileProvider` configuration can grant read access to direct local files, while SAF item paths are already content URIs.
- Rename validates names before provider calls, deletion requires confirmation, and local deletion defaults to recoverable Trash.

## UX risks

- `FileUtils.shareFile` exists but is unused, handles only one direct local file, and cannot share SAF or multiple selections. Users therefore have no visible Share action.
- Rename is available only in the overflow menu for a single selection, while copy and cut occupy two scarce primary action slots. This makes a frequent single-item action unnecessarily hard to discover.
- When Trash is enabled, the delete dialog offers only Move to Trash. Permanently deleting the current selection requires leaving the browser, changing a global setting, returning, deleting, and optionally restoring the setting.
- The view control is labeled only `Toggle view`. It does not announce the destination mode and cannot offer a denser list for large folders.
- The browser has no concise Details surface for path, type, size, and modified time, so users must infer or leave the app for basic information.

## Accessibility risks

- The generic `Toggle view` description does not tell assistive-technology users what will happen.
- Contextual action ordering prioritizes implementation concepts such as copy and cut over common user outcomes such as share and rename.
- Share eligibility and permanent-delete consequences are not currently representable because the actions are absent.
- This audit did not run a complete TalkBack traversal, external keyboard pass, switch-access pass, or formal contrast measurement, so it does not claim full accessibility compliance.

## Considered approaches

### Approach A: Context-aware top bar plus focused dialogs and sheets

This is the selected approach. Keep the existing contextual top bar, but derive its three primary actions from selection count and share eligibility. Add a per-operation delete choice, a small view-options menu, a focused details sheet, and a reusable share intent builder. This preserves Voyager's current design and architecture, requires no runtime dependency, and supports multi-selection.

### Approach B: Replace selection mode with a modal action sheet

A full action sheet could expose every operation with text labels, but it would add a navigation layer to every selection, displace the current fast actions, and require a broader interaction rewrite. It also makes repeated multi-select operations slower.

### Approach C: Add an overflow button to every file row and grid tile

Per-item menus are discoverable for single files but add visual clutter, complicate grid layout, and do not solve multi-selection. Voyager already has a coherent long-press selection model, so duplicating actions per row would create inconsistent paths.

## Selected interaction design

### Contextual actions

The top bar will continue to show at most three primary actions plus overflow on narrow phones.

- One shareable file: Share, Rename, Delete are primary. Copy, Cut, Select all visible, and Details are in overflow.
- One non-shareable item: Copy, Rename, Delete are primary. Cut, Select all visible, Details, and remote Download when applicable are in overflow.
- Multiple shareable files: Copy, Share, Delete are primary. Cut and Select all visible are in overflow.
- Multiple selections containing a directory, or remote selections: Copy and Delete remain primary. Cut, Select all visible, and remote Download are in overflow.

A selection is shareable only when every selected item is a non-directory item from a direct local or SAF session. Remote sharing remains a future enhancement because it requires downloading and managing temporary files. Voyager's existing Download action remains available for remote selections.

### Per-operation deletion

For a direct local selection when Trash is enabled, the delete dialog will offer three explicit actions: Cancel, Delete permanently, and Move to Trash. Move to Trash remains the visually preferred safe action. Delete permanently uses the error color and the dialog states that it cannot be undone. This one dialog is the required destructive confirmation.

When Trash is disabled, or the selection belongs to SAF or a remote provider, the existing permanent-delete confirmation remains. The ViewModel will accept an explicit `DeleteMode` so the per-operation choice cannot race with or mutate the saved preference. Requesting Trash for a non-local source will fail safely and leave the selection intact.

### Sharing

`FileUtils.shareFiles(context, files)` will support one or many eligible items. Direct local paths will become `FileProvider` content URIs. SAF paths will remain their existing content URIs. A single item will use `ACTION_SEND`; multiple items will use `ACTION_SEND_MULTIPLE`. The intent will include read grants and `ClipData` so receiving apps can open every URI.

When every selected file has the same MIME type, Voyager will use it. If MIME types differ, the share type will use the common top-level family such as `image/*`; otherwise it will use `*/*`. Voyager will open Android's chooser and report an actionable snackbar if URI creation or chooser launch fails. Starting the chooser clears selection only after the intent has been created successfully.

### View options

The existing List and Grid modes remain, and a third Compact list mode will be added. Compact list keeps file metadata but reduces vertical padding, icon size, and spacing so more items fit on screen without shrinking touch targets below 48dp. The existing toolbar slot will open a View options menu with List, Compact list, and Grid entries and a check beside the active mode. Its content description will include the active mode.

`ViewMode.COMPACT` will be persisted through the existing DataStore preference. Existing stored values continue to parse, and unknown values continue to fall back to List.

### Details

Details will be available for exactly one selected item. A Material 3 bottom sheet will show name, item type, size for files, modified time, source, full path, and provider-supplied owner or permissions when present. The sheet is read-only and does not introduce metadata editing or recursive folder-size calculation.

## Architecture and components

- `SelectionToolbarModel` remains a pure Kotlin model but will consume selection count, source, and whether every selected item is shareable. Its tests define primary and overflow placement.
- A pure `ShareIntentModel` will determine action type and MIME type without Android framework calls. `FileUtils` will resolve URIs and build the actual chooser intent.
- `DeleteMode` will make Trash and permanent behavior explicit at the ViewModel boundary. Existing preference behavior remains the default for callers that do not specify a mode.
- `DeleteChoiceDialog` and `FileDetailsSheet` will live with Voyager's existing focused Compose components. They will receive immutable models and callbacks rather than access the ViewModel directly.
- `ViewMode.COMPACT` will reuse `FileListItem` with an explicit density parameter rather than duplicate the list component.

## Error handling and recovery

- Share is hidden for directories and unsupported sources instead of failing after the user chooses it.
- Invalid local paths, revoked SAF grants, or missing chooser activities produce a snackbar and keep the selection.
- Permanent deletion always includes irreversible wording. Trash remains the default safe action when available.
- A failed Trash or permanent delete continues to use the existing partial-failure reporting and refresh behavior.
- Details never performs recursive I/O, so large folders open the sheet immediately.
- Unknown persisted view values fall back to List, preserving existing recovery behavior.

## Testing and verification

Implementation will follow red, green, refactor cycles.

- JVM tests will cover contextual action placement, share eligibility, single versus multiple intent models, MIME-type merging, delete dialog wording, and view-mode parsing behavior that can be isolated from DataStore.
- Compose instrumentation tests will verify Share and Rename visibility, the three-action local delete dialog, permanent-delete wording, view-option labels, compact-row accessibility, and the Details sheet.
- Existing unit and instrumentation suites must remain green.
- Device verification on the K60 will share one file and multiple mixed-type files through Android's chooser, verify SAF sharing when a document-tree grant is available, rename a file, exercise both Trash and permanent deletion on disposable fixtures, switch among all three view modes, rotate the app, and inspect logcat for crashes.
- The full gate remains `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, and `connectedDebugAndroidTest`.

## Out of scope

- Sharing directories as archives.
- Sharing remote files through temporary downloads.
- Batch rename, archive creation or extraction, file hashing, recursive folder-size calculation, and metadata editing.
- Replacing the selection model, navigation system, provider interface, or visual design system.

## Acceptance criteria

- Eligible local and SAF files can be shared singly or together through Android's chooser with readable URIs.
- Share is not offered for directories or remote items.
- Rename is a primary action whenever exactly one item is selected.
- Local deletion with Trash enabled offers both recoverable Trash and explicit permanent deletion in the same confirmation dialog.
- Permanent delete wording states that the action cannot be undone, and no destructive action runs without confirmation.
- List, Compact list, and Grid are selectable, persistent, and correctly labeled for assistive technology.
- Details accurately reports the selected item's available metadata without blocking on recursive work.
- Narrow portrait and landscape layouts remain readable, changed controls retain 48dp targets, and all focused and full verification commands pass.
