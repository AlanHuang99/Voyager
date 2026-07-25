# GitHub Issues 31-34 and SFTP Public-Key Access Design

## Goal

Resolve the four open GitHub feature requests as of 2026-07-25 and the new actionable comment on issue 6 while preserving Voyager's provider-neutral file model, bounded-memory transfers, security boundaries, and Android 8.0 minimum.

## Scope

This batch contains five independently testable deliverables:

1. Make an in-app generated SFTP public key accessible without exposing its private key.
2. Let Android honor or establish default apps for file types.
3. Render the first page of local and Storage Access Framework PDF files as thumbnails.
4. Show useful progress during copy and move operations.
5. Create ZIP archives and extract ZIP, TAR, TGZ, TBZ2, GZ, BZ2, and RAR archives.

Archive support means create and extract operations. It does not include browsing an archive as a virtual directory, editing entries in place, creating RAR or compressed TAR archives, password-protected archives, split archives, or preserving Unix ownership and special file nodes.

## Design Principles

- Keep file operations expressed through `FileProvider` so local, SAF, and remote locations behave consistently where their stream and mutation capabilities permit it.
- Keep generated private keys in app-private storage with owner-only permissions.
- Never overwrite an existing file or directory during archive creation or extraction.
- Stream file contents with bounded buffers. Temporary local files are allowed only when a library requires seekable input and must be removed on every exit path.
- Surface actionable errors through the existing operation state and message layers.
- Add focused tests before production changes and retain the existing complete Gradle gate.

## SFTP Public-Key Access

`SshKeyGenerator` continues to create the private and public key files in `files/ssh`. After generation, the connection editor reads only the public key text into UI state. A result dialog displays the OpenSSH-formatted public key and provides Copy public key and Save public key actions. Saving uses Android's `CreateDocument("text/plain")` contract so the user chooses a location through the system document picker. The private key path remains filled into the saved SFTP connection and is never copied, shared, or exported by this flow.

Generation failures, clipboard failures, and document-write failures remain visible in the dialog or connection screen. Rotating or recreating the activity must not expose private key material in saved instance state; only the public key text and generated filename may be transient UI state.

## Default Apps for File Types

`FileUtils` will separate construction of an `ACTION_VIEW` intent from launching it. The launch path will start that implicit intent directly instead of wrapping it in `Intent.createChooser`. Android then resolves an existing user default or presents its resolver UI when no default exists. Voyager will continue granting read access to local `FileProvider` URIs and SAF content URIs.

The app will not maintain a competing extension-to-package preference database. Android owns app availability, defaults, disabled packages, and user reset behavior. If no activity can handle a file, Voyager will return a failure that the browser can translate into an actionable message instead of crashing.

## PDF Thumbnails

`PdfThumbnailLoader` will accept a `Context`, `FileItem`, and pixel bounds. It opens a seekable `ParcelFileDescriptor` from a direct local file or SAF URI, creates `PdfRenderer`, renders page zero into an ARGB bitmap off the main thread, and closes every descriptor and renderer in structured `use` blocks. Unsupported sources, empty or malformed PDFs, and unavailable documents return a failure that causes the existing document icon fallback.

An application-scoped, byte-bounded LRU cache will key rendered bitmaps by source, path, last-modified value, and requested bounds. `FileThumbnail` will use the loader only for non-directory PDF items and preserve existing Coil image behavior for image files. Remote PDFs retain the document fallback because rendering them would require an implicit download.

## Copy and Move Progress

`OperationState.Running` will carry an immutable progress snapshot with operation label, completed item count, total item count, current item name, copied bytes for the current item, and current-item byte total when known. The model will expose a normalized fraction only when it can produce a truthful determinate value.

`FileOperationCoordinator` will accept an optional progress callback for streamed cross-provider copies. Its copy loop will report cumulative bytes after each bounded chunk. Recursive copies will identify the active file, but directory totals remain unknown unless already available without an extra traversal. Same-provider native copy and move operations will report item progress because their protocols do not expose byte callbacks.

`FileBrowserViewModel` will publish progress before each selected item, throttle byte updates to avoid excessive Compose invalidation, mark an item complete only after the operation succeeds, and preserve partial-failure accounting. `BrowserScreen` will render a linear progress indicator, fraction text when determinate, and completed-item context. Cancellation is outside this batch because current provider mutations are not uniformly cancellable or transactional.

## Archive Operations

`ArchiveFormat` will recognize supported extensions case-insensitively, including compound suffixes such as `.tar.gz`, `.tgz`, `.tar.bz2`, and `.tbz2`. `ArchivePath` will normalize entry separators and reject blank names, NUL bytes, absolute paths, drive-prefixed paths, `.` or `..` segments, and any path that escapes the extraction root.

`ArchiveService` will provide two operations:

- `createZip(provider, selectedItems, destinationDirectory, archiveName, progress)` streams entries through `ZipOutputStream` into a newly created provider output stream. It recursively lists selected directories, writes explicit directory entries, rejects duplicate entry names, and removes a partially written archive after failure.
- `extract(provider, archiveItem, destinationDirectory, progress)` creates one new sibling directory derived from the archive filename, then writes validated entries beneath it. ZIP and TAR-family readers stream from provider input where supported. RAR input is copied into a unique cache directory for seekable access. Plain GZ and BZ2 inputs produce one file named from the archive stem. Any failure removes the newly created extraction tree.

Extraction rejects symbolic links, hard links, device entries, encrypted or split RAR archives, duplicate normalized paths, path type conflicts, and existing destination roots. Individual files are created only after their parent directories exist. A failed entry write removes its partial file before the enclosing extraction root is cleaned.

The browser selection UI will expose Compress to ZIP for one or more selected items and Extract here for exactly one supported archive. Both operations reuse the existing operation-state presentation and refresh the directory after completion. Archive actions remain unavailable while another operation is running.

## Dependencies

The Java standard library supplies ZIP support. Apache Commons Compress supplies TAR, GZIP, and BZIP2 codecs. Junrar supplies read-only RAR extraction. Dependency versions and licenses must be verified from primary project and Maven metadata before addition, and Android lint plus release minification must validate compatibility.

## Error Handling and User Feedback

New domain exceptions will distinguish destination conflicts, unsafe archive entries, unsupported or encrypted archives, corrupt inputs, missing handlers for file opening, and public-key export failures. User-facing messages will state what did not happen and whether any partial destination was removed. Missing, zero, and unknown sizes remain distinct in progress state.

## Verification

- Unit tests cover public-key content access, open-intent policy, progress fraction rules, progress callbacks, archive-format recognition, unsafe path rejection, ZIP round trips, TAR-family and RAR extraction, overwrite protection, and cleanup after failure.
- Android instrumentation tests cover generated-key Copy and Save affordances, direct `ACTION_VIEW` intent construction, valid PDF rendering, malformed PDF fallback, and progress UI semantics.
- A disposable Docker OpenSSH server accepts only the generated public key. The JVM SFTP client test lists and transfers a file with an empty password, proving the exported public key matches the app-owned private key.
- The full local gate remains `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`.

## Documentation and GitHub

`README.md`, `docs/ARCHITECTURE.md`, and `docs/TESTING.md` will be updated for the shipped behavior and integration procedure. After local verification and CI, GitHub issues 31 through 34 and the comment thread on issue 6 will receive concise evidence-backed updates. No release, tag, or package publication is part of this batch.
