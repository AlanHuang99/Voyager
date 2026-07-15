# Voyager Daily File Browser Hardening Design

## Purpose

Voyager will become a dependable daily-use Android file manager without replacing its existing Compose, Material 3, ViewModel, provider, Room, or DataStore architecture. The work will preserve working local, SAF, SFTP, FTP, SMB, and WebDAV behavior while making destructive actions safer, adding the missing search and trash workflows, improving removable-storage access, hardening remote connections, and making important states understandable and recoverable.

## Scope and priorities

The work is divided into four independently testable slices that can ship together but remain reviewable on their own:

1. File-operation safety, search, responsive selection, and crash regression coverage.
2. Local Trash and explicit permanent-delete behavior, resolving GitHub issue #15.
3. Permission recovery and modern removable-storage discovery, resolving GitHub issue #16.
4. Remote transfer, credential, host-verification, validation, and documentation hardening.

GitHub issue #14 is resolved by the existing uncommitted thumbnail fallback change, which replaces Coil's unsupported `ImageVector` model with a Compose vector painter and retains an on-device regression test.

## Considered approaches

### Approach A: Patch only the three open issues

This would be the smallest change, but it would leave silent copy and move failures, unsafe overwrite behavior, whole-file remote buffering, permission dead ends, and credential risks in place. It would close visible tickets without making the app trustworthy for daily use.

### Approach B: Staged reliability layers within the current architecture

This is the selected approach. It adds small testable services around the existing providers and ViewModel, strengthens provider contracts without replacing them, and updates only the UI surfaces needed to expose safe behavior. It offers the best balance of user value, implementation risk, compatibility, and reviewability.

### Approach C: Rewrite around a new domain and navigation architecture

A full clean-architecture rewrite could eventually improve separation, but it would create broad regression risk and delay user-visible fixes. Voyager's existing architecture is sufficient once file operations, state ownership, credentials, and UI responsibilities are made more explicit.

## Existing conventions to preserve

- Jetpack Compose and Material 3 remain the UI system.
- `FileBrowserViewModel` remains the screen-level state owner, with focused helpers extracted when behavior is independently testable.
- `FileProvider` remains the common local, SAF, and remote abstraction.
- Room remains the saved-connection and bookmark store; DataStore remains the preference store.
- Existing colors, shapes, typography, list/grid presentation, session model, and route structure remain recognizable.
- No new runtime dependency is required unless an existing AndroidX component is demonstrably insufficient.

## File-operation safety

A new pure Kotlin `FileNameValidator` will reject blank names, `.` and `..`, path separators, NUL characters, and names that resolve outside the current directory. Create and rename dialogs will display inline errors and will not dismiss until the request is accepted.

Local copy and move will reject a destination that already exists, reject copying or moving a directory into itself or a descendant, check recursive-copy return values, remove only newly created partial destinations after copy failure, and never delete a source unless copying completed successfully. A source-delete failure after a completed copy will preserve both copies and report the incomplete move because preserving data is safer than attempting destructive rollback.

Cross-provider operations will preflight the destination, refuse implicit overwrite or merge, stream into the destination, and clean up a newly created partial target after a failed copy. Move will delete the source only after the destination closes successfully. Conflicts will produce an actionable message that names the item and asks the user to rename or remove the existing destination.

The ViewModel will expose a single operation state so paste, delete, restore, download, and empty-trash actions cannot be started repeatedly. The UI will show progress, disable conflicting actions, preserve the current list while refreshing, and report per-operation success or failure. A monotonically increasing load request identifier will prevent a slow directory result from replacing a newer navigation result.

## Search, filtering, sorting, and selection

Browser state will keep the complete sorted directory listing and derive visible items from the search query and type filter. Search is current-directory, case-insensitive, updates as the user types, survives configuration changes through ViewModel state, and clears when the user enters another directory. Type filters cover all items, folders, images, video, audio, documents, archives, and APKs. Empty search results will be distinct from an empty folder.

Select All will select only currently visible results. Reloading, hiding files, or changing filters will intersect selection with visible existing items so hidden stale selections cannot be deleted accidentally. The sort menu will show the active field and direction explicitly.

The selection toolbar will retain copy, cut, and delete as primary actions and move select-all, rename, and remote download into an overflow menu when needed. This keeps the title readable on narrow phones while preserving every action.

## Local Trash

Trash is enabled by default and can be disabled in Settings. It applies only to direct local-storage sessions. SAF and remote providers continue to use permanent deletion because Voyager cannot guarantee a portable restore contract for those providers. Every permanent delete dialog will state that the action cannot be undone.

Each mounted local volume uses a hidden `.VoyagerTrash` directory at the volume root. Each entry is an isolated directory containing a payload plus a small `metadata.properties` file with a stable identifier, original absolute path, display name, directory flag, and deletion time. This avoids a Room schema dependency, keeps metadata with the payload, and allows recovery after process death. Voyager will hide its managed trash directories from normal browsing even when hidden files are shown.

Moving to Trash will use a same-volume move into a temporary entry followed by a final rename. If any step fails, the source remains at its original path and the temporary entry is removed. Restore recreates the original parent only when it already exists or can be safely created, refuses to overwrite an existing item, and retains the trash entry on failure. Permanent deletion and Empty Trash require confirmation and verify recursive deletion results.

A Trash screen reachable from Home will list entries newest first with original location and deletion time. Users can select entries, restore them, permanently delete them, or empty all Trash. A disconnected removable volume simply omits its entries and remains discoverable again when remounted.

## Storage access and removable volumes

All-files access remains appropriate for Voyager's core file-manager use case, but permission denial will no longer make the entire app unusable. The first-run permission screen will offer both Grant full access and Continue with limited access. Limited mode exposes SAF document trees, remote connections, settings, and permission recovery while hiding direct local-path shortcuts that cannot work.

Home will group direct local volumes under Storage. On API 30 and newer, discovery will use `StorageManager.storageVolumes` and `StorageVolume.directory`, preserving the system description, removable flag, mount state, and read-only state. API 26 through 29 will retain the existing `getExternalFilesDirs` fallback. Mounted and mounted-read-only volumes are shown; unavailable volumes are shown disabled when Android reports them, with a short state label. If direct access is unavailable, the Document Tree entry remains the explicit SAF fallback.

This modern discovery and dedicated Storage presentation resolves the intent of GitHub issue #16 while keeping SAF available for devices and providers that do not expose a direct filesystem root.

## Remote reliability and security

FTP and SMB input and output streams will wrap the libraries' real streams and close their associated remote handles correctly. FTP streams will use dedicated clients and call `completePendingCommand()` before logout. WebDAV uploads will spool to an app-cache temporary file and then upload from a file stream, deleting the temporary file in all outcomes. These changes bound heap usage for large transfers without changing the `FileProvider` interface. SFTP already streams and will retain that behavior.

WebDAV connections will have an explicit HTTPS setting instead of inferring security from port 443. New WebDAV connections default to HTTPS; migrated connections preserve their existing port-based behavior. Host fields will reject embedded schemes and invalid ports, and SMB saves will require a share name.

SFTP will use an app-private persistent known-hosts file. First use will follow trust-on-first-use behavior and save the host key. A changed key will be rejected with an actionable error rather than silently accepted. Tests will connect to a local SSH server, reconnect with the same host key, and reject a replacement key.

Saved passwords will be encrypted with an AES-GCM key held by Android Keystore before Room persistence. A repository layer will map encrypted database rows to decrypted UI models and migrate existing plaintext values when first read. If a key cannot decrypt a saved value, Voyager will leave the password blank and require the user to edit the connection rather than crash or expose ciphertext. Backup rules will exclude the connection database and generated SSH keys from cloud and device-transfer backup because Keystore keys are device-bound.

## Error handling and recovery

- User-facing errors will describe the item and next action without exposing stack traces.
- Existing-destination conflicts never overwrite silently.
- Partial destinations created by failed copy operations are removed when safe.
- Permission denial always leaves SAF and remote access available.
- Unavailable volumes remain non-interactive and explain their state.
- Failed Trash moves retain the original; failed restores retain the trash entry.
- Network failures retain the browser session and expose Retry or reconnect behavior.
- Unsupported local files open through Android's chooser; failures identify that no compatible app is installed.

## Accessibility and responsive behavior

All interactive controls will retain at least a 48dp touch target. File rows and grids will expose file name, kind, size when relevant, selection state, click action, and long-click selection semantics. Icon-only actions will have specific labels, progress and empty states will have meaningful text, destructive buttons will use error coloring, and state changes will be announced through Material components or explicit semantics where needed.

Phone portrait is the primary layout. Toolbars will avoid action crowding, dialogs will remain scrollable with the keyboard visible, and transient form values will use saveable state so rotation does not discard typed connection or rename data. Tablet and landscape layouts will continue to use adaptive grids and full-width content without introducing a separate navigation model.

## Testing and verification

Every behavior change follows a red-green-refactor cycle. Pure JVM tests will cover name validation, search and filters, selection reconciliation, path bounds, copy conflicts and cleanup, local copy/move failures, trash move/restore/conflict/permanent delete, removable-volume mapping, WebDAV URL selection, and connection validation. Protocol integration tests will verify FTP, SFTP, and WebDAV behavior; SMB remains opt-in unless a test server is available. Compose instrumentation tests will cover the thumbnail crash, responsive selection actions, delete wording, search states, and Trash controls where stable semantics are available.

Verification will include `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, and `connectedDebugAndroidTest`. The debug APK will be installed on the K60 for local browsing, search, sorting, selection, copy/move conflict, Trash restore, permission denial and recovery, large-directory responsiveness, rotation, unsupported-file handling, and crash-log review. A debug-key-signed release APK will receive a final smoke test when signing tools are available.

## Documentation and issue mapping

README capability claims, screenshots, store descriptions, and release notes will be updated only for behavior that is implemented and verified. The final handoff will map evidence to GitHub issues #14, #15, and #16. Public issue comments, issue closure, pushes, tags, and releases remain separate publication actions.

## Acceptance criteria

- Opening image-containing folders never reproduces the `Unsupported type: ImageVector` crash.
- No copy or move path silently overwrites, merges, reports a false success, deletes a source after a failed copy, or permits recursive self-copy.
- Search and type filters work in list and grid views with correct empty and selection states.
- Local deletion defaults to recoverable Trash, restore refuses conflicts, and permanent deletion is explicit.
- SAF and remote browsing remain available after all-files permission denial.
- Mounted removable volumes use Android's current storage APIs and appear as direct Storage entries when accessible.
- FTP, SMB, and WebDAV transfers do not buffer an entire file in heap memory.
- SFTP rejects changed host keys, saved passwords are encrypted at rest, and sensitive connection material is excluded from backup.
- Narrow portrait toolbars remain readable and all changed controls expose useful accessibility semantics.
- Focused tests, full unit tests, lint, debug and release builds, instrumentation tests, and K60 smoke tests pass or have a precisely documented external blocker.
