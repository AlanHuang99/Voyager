# Architecture

Voyager is a single-activity Jetpack Compose application. `MainActivity` owns the storage-access decision and renders either the permission choice or the navigation graph. `FileBrowserViewModel` owns browser state, provider sessions, file operations, persisted preferences, saved connections, bookmarks, and Trash state.

## UI and navigation

`AppNavigation` defines Home, Browser, Remote Connections, Trash, and Settings destinations. Home presents storage volumes, common folders, local bookmarks, document-tree access, active sessions, Trash, and remote connections. Browser renders a session-aware toolbar, breadcrumb navigation, search and type filters, list, compact-list, or grid content, contextual selection actions, progress, empty states, retryable errors, and a read-only details sheet.

The UI uses Material 3 components and theme tokens from `ui/theme`. Direct-local deletion offers recoverable Trash and explicitly irreversible permanent deletion per operation. Permanent-only provider deletion, permanent Trash deletion, emptying Trash, deleting saved connections, and saving a cleartext remote transport require explicit confirmation where appropriate.

## Browser state and sessions

`FileBrowserViewModel` exposes immutable `StateFlow` values to Compose. Each open location is represented by a `BrowserSession` with a stable ID, source, root boundary, current path, and optional remote connection metadata. A provider instance is retained per session so local, SAF, and remote state cannot be accidentally mixed.

Directory requests use `DirectoryLoadGuard` tokens. Only the newest request for the active session may update the visible file list, which prevents a slow response from overwriting a later navigation result. Search and type filtering are derived from the loaded directory in `BrowseState`; sorting preserves directories before files.

## File providers

All storage backends implement `FileProvider`, which defines listing, metadata, create, delete, rename, copy, move, existence checks, parent navigation, and input/output streams.

| Provider | Path form | Notes |
| --- | --- | --- |
| Local | Absolute filesystem path | Direct access requires Android storage permission; operations validate conflicts and self-descendant copies. |
| SAF | Document URI string | Uses persisted document-tree grants and `DocumentsContract`; parent relationships are learned while browsing. |
| SFTP | POSIX remote path | Uses JSch, one SSH session with per-operation channels, app-owned known hosts, and streamed transfers. |
| FTP | POSIX remote path | Uses Commons Net in passive binary mode; data connections stream and provider-local copy uses bounded cache files. |
| SMB | Slash-delimited UI path | Converts to SMB paths and keeps SMBJ file handles open for the lifetime of returned streams. |
| WebDAV | Slash-delimited remote path | Uses Sardine for metadata operations and OkHttp file-backed request bodies for bounded-memory uploads. |

`FileOperationCoordinator` handles copy and move across different providers. It rejects destination conflicts, copies recursively, removes a newly created partial destination after failure, and deletes a move source only after the copy succeeds. Providers handle same-provider operations so they can use native rename or server-side copy behavior when available.

Android sharing is intentionally limited to local and SAF files in the current implementation. `ShareIntentPlan` rejects directories and remote items, computes the narrowest common MIME type, and chooses `ACTION_SEND` or `ACTION_SEND_MULTIPLE`. `FileUtils` exposes local files through the app `FileProvider`, preserves SAF content URIs, grants read access through both the intent flag and `ClipData`, and opens the system chooser.

## Storage access

On Android 11 and later, full local browsing uses `MANAGE_EXTERNAL_STORAGE`. A user who declines can explicitly enter limited mode, where SAF document trees and remote connections remain available. On older supported versions, Voyager requests legacy read and write storage permissions.

`FileUtils.getStorageVolumes` adapts Android `StorageManager` volumes into `StorageVolumeInfo`. Mounted internal, SD card, and USB/OTG roots appear directly on Home when a filesystem path is available. Unmounted or otherwise unavailable media remains visible as disabled state rather than failing silently.

## Trash

Local deletion uses `LocalTrashManager` by default. Each configured volume has a hidden `.VoyagerTrash` directory containing one payload and metadata file per entry. A pending directory makes the move recoverable if finalization is interrupted. Restore recreates a missing parent directory but refuses to overwrite an existing destination. When Trash is enabled, direct-local deletion presents both Trash and permanent choices without mutating the saved preference. SAF and remote deletions remain permanent and use permanent-delete confirmation wording.

## Persistence and secrets

Room stores remote connection records and local bookmarks. DataStore stores theme, display, sort, Trash, limited-mode, and default-path preferences. `ConnectionRepository` is the only ViewModel-facing saved-connection layer: it encrypts passwords before Room writes, decrypts rows for the editor and providers, and atomically migrates legacy plaintext passwords.

`AndroidCredentialCipher` uses AES-GCM with a random IV and a non-exportable Android Keystore key. If an encrypted value cannot be decrypted, the repository exposes an empty password so the user can edit and save the connection again. Android backup rules exclude Room databases, DataStore, generated SSH material, and SFTP known hosts because Keystore keys are device-bound and connection state is sensitive.

SFTP stores first-seen host keys in `files/ssh/known_hosts` and rejects changed keys. WebDAV transport is explicit and supports HTTPS on any port. FTP and HTTP WebDAV are cleartext and require a warning confirmation in the connection editor. SMB encryption depends on server negotiation and is not enforced by Voyager.

## Concurrency and failure handling

Filesystem and network work runs on `Dispatchers.IO`. `OperationState` serializes user-initiated mutations and drives an accessible progress indicator. Provider references and operation inputs are captured before asynchronous work begins so switching locations cannot redirect an in-flight mutation. `OperationMessages` maps conflicts, permission denial, missing items, unreachable hosts, and timeouts to recovery-oriented feedback.

## Tests

JVM tests cover pure models, sharing plans, validation, operation safety, Trash recovery, credentials, storage adapters, navigation races, and embedded FTP, SFTP, and WebDAV servers. SMB integration tests run when server credentials are supplied through environment variables. Android instrumentation tests cover Compose rendering, unavailable storage, single and multiple share intents, contextual selection actions, per-operation deletion choices, compact-view persistence, file details, search-to-folder Back behavior, selection-control accessibility, and Android Keystore behavior. See [TESTING.md](TESTING.md) for commands and the manual regression matrix.
