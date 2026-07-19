# Remote Create and Upload Design

## Problem

Voyager 1.4.0 hides the Create floating action button whenever a browser session uses a network provider. This prevents users from reaching the existing New Folder and New File commands even though the FTP, SFTP, SMB, and WebDAV providers implement both operations. Remote sessions also lack a direct document-picker entry point for uploading files, so users must discover the cross-session clipboard workflow.

## Requirements

- Show New Folder and New File in local, SAF, FTP, SFTP, SMB, and WebDAV browser sessions.
- Show Upload Files only for network sessions.
- Allow one or more documents to be selected with Android's system document picker.
- Stream each selected document directly to the active provider without loading the whole file into memory.
- Preserve an existing destination file instead of overwriting it.
- Remove a partially created destination when a transfer fails where the provider permits cleanup.
- Validate document display names before constructing remote paths.
- Keep provider operations and stream copying off the Android main thread.
- Reconnect FTP when the system picker outlives the existing control connection.
- Report complete success, complete failure, and partial success, then refresh the active directory.

## Considered Approaches

### Reuse only the clipboard workflow

Removing the remote-only UI guard would restore folder and empty-file creation, and the existing Copy, Sessions, and Paste here workflow can already stream local files to FTP. This is the smallest code change, but it leaves the reported upload discoverability problem unresolved.

### Stage selected documents in app storage

The app could copy picker results into a cache directory and then reuse a local FileProvider. This would duplicate large files, require temporary-space management, and delay the actual upload without adding useful behavior.

### Stream document-picker results to the active provider

The browser can use ActivityResultContracts.OpenMultipleDocuments, resolve each URI's OpenableColumns.DISPLAY_NAME, and pass a lazy input-stream source to the view model. The operation coordinator can apply the same conflict and partial-file cleanup semantics used by cross-provider copies. This adds a direct upload path while retaining bounded memory use and is the selected approach.

## Design

BrowserScreen will model the Create menu explicitly. Local and SAF sessions receive New Folder and New File. Network sessions receive those actions plus Upload Files. Selecting Upload Files launches ActivityResultContracts.OpenMultipleDocuments with a wildcard MIME filter, converts each returned URI into an UploadSource using ContentResolver, and submits the sources to FileBrowserViewModel.

UploadSource contains a display name and a lazy InputStream factory. The view model validates every name with FileNameValidator before starting the operation. It then uploads sources sequentially to the provider and directory captured when the operation starts. Sequential transfer avoids concurrent use of provider clients that are not designed for parallel commands and makes partial-failure accounting deterministic.

FileOperationCoordinator will add uploadFile. The method switches to Dispatchers.IO, joins the validated name to the destination directory, refuses a conflicting target with DestinationConflictException, opens the selected document and destination streams, copies with the existing 64 KiB buffer, and attempts to delete a partially created target after failure. The input stream is opened inside the I/O context.

FtpFileProvider will validate an existing control connection with the FTP NOOP command before reuse. If the peer closed the socket while Voyager was in the system picker, the provider disconnects the stale client and authenticates a replacement before issuing the upload command.

The view model will refresh once after all sources have been attempted. A complete success reports the uploaded file count. A partial or complete failure uses the existing OperationMessages.partial format and includes the first underlying error. The normal operation state disables additional create or upload actions until completion.

## Testing

- Unit-test that the remote Create menu exposes New Folder, New File, and Upload Files while the local menu omits Upload Files.
- Unit-test that uploadFile streams bytes to the requested destination on a non-calling thread.
- Unit-test that uploadFile refuses an existing target without modifying it.
- Unit-test that uploadFile deletes a partially written target after a stream failure.
- Unit-test that the FTP provider reconnects and uploads after the server drops the original control connection.
- Add an instrumented UI test that opens a remote-mode Create menu model or composable and verifies the three user-facing actions if practical without a live server.
- Run the full unit, lint, debug assembly, and release assembly gate.
- Exercise the picker and upload flow on the connected Android device against a local FTP fixture if the device can reach the fixture; otherwise verify the picker UI on device and cover provider transfer behavior with the existing in-process FTP and coordinator tests.

## Non-goals

- Directory-tree upload is not included because OpenMultipleDocuments returns files, not a portable recursive directory tree.
- Overwrite prompts and rename-on-conflict policies are not included. Existing destination content remains protected.
- Upload progress by byte count is not included; the existing operation-level progress indicator remains in use.
