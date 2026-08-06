# Transfer Correctness and Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make cross-provider writes correct for opaque SAF identifiers and show truthful upload/download progress with transfer speed.

**Architecture:** A provider-neutral stream-copy primitive reports bytes and monotonic elapsed time. The coordinator creates destination entries through `FileProvider` and writes only to returned identifiers; upload, paste, and download adapters publish those events as `TransferProgress` without inventing totals.

**Tech Stack:** Kotlin coroutines, Android Storage Access Framework, provider abstractions for local/SFTP/FTP/SMB/WebDAV, JUnit 4, Compose Material 3.

## Global Constraints

- Missing, zero, and unknown sizes remain distinct.
- Cross-provider writes never construct provider child identifiers with string concatenation.
- Existing targets are never overwritten.
- Partial destinations are removed after failure; move sources are deleted only after complete success.
- Network and filesystem I/O stays on `Dispatchers.IO`.
- Determinate progress appears only when the active stream has a trustworthy byte total.

---

### Task 1: Reproduce opaque destination failure

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`

**Interfaces:**
- Produces: `OpaquePathProvider`, whose returned child paths cannot be derived from the parent path and filename.

- [ ] **Step 1: Add failing file and directory copy tests**

Create an in-memory provider whose `createFile("content://tree/root", "report.txt")` returns `FileItem(path = "content://tree/root/document/id-1")`, and whose `getOutputStream` rejects every non-created identifier. Assert file copy, recursive directory copy, upload, and cleanup use returned identifiers and preserve exact bytes.

- [ ] **Step 2: Run and verify the root-cause failure**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: FAIL with an invalid destination identifier because the coordinator appends names to `content://tree/root`.

- [ ] **Step 3: Commit the red test**

```bash
git add app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "test: reproduce opaque SAF destination writes"
```

### Task 2: Provider-created destination identifiers

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`

**Interfaces:**
- Produces: provider-neutral conflict lookup and returned-path recursive copy.

- [ ] **Step 1: Implement destination-name conflict lookup**

```kotlin
private suspend fun requireNameAvailable(
    provider: FileProvider,
    directoryPath: String,
    name: String,
) {
    if (provider.listFiles(directoryPath).getOrThrow().any { it.name == name }) {
        throw DestinationConflictException(name)
    }
}
```

Adjust `DestinationConflictException` so it accepts either an identifier or a name while preserving the existing user-facing filename.

- [ ] **Step 2: Create entries before opening their output streams**

For files, call `requireNameAvailable`, then `createFile(destinationDirectoryPath, item.name)`, retain the returned `path`, and call `getOutputStream(created.path)`. For directories, use `createDirectory(...).path` as the recursive destination. Cleanup uses the returned path only.

- [ ] **Step 3: Run coordinator tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: PASS, including opaque-path, no-overwrite, cleanup, and source-preservation cases.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "fix: write through provider-created destinations"
```

### Task 3: Shared streaming progress primitive

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/data/repository/StreamTransferTest.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/repository/StreamTransfer.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`

**Interfaces:**
- Produces: `StreamTransferProgress(path: String, bytesTransferred: Long, totalBytes: Long?, elapsedNanos: Long)` and `StreamTransfer.copy(input, output, path, totalBytes, nanoTime, onProgress)`.

- [ ] **Step 1: Write failing progress tests with a deterministic clock**

Assert a 131,089-byte payload reports strictly increasing bytes, a nondecreasing positive elapsed duration, exact total, exact final bytes, and no event before a failing output write. Assert an empty stream reports zero bytes with a known zero total.

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.StreamTransferTest --stacktrace`

Expected: compilation FAIL because `StreamTransfer` is missing.

- [ ] **Step 3: Implement bounded copy with monotonic time**

```kotlin
data class StreamTransferProgress(
    val path: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val elapsedNanos: Long,
)
```

Use a 64 KiB buffer, call `output.write` before publishing each event, compute elapsed time from the injected monotonic clock, and publish one zero-byte event for an empty stream.

- [ ] **Step 4: Replace coordinator-local stream copying**

Delete `StreamCopyProgress` from `TransferProgress.kt`, update coordinator signatures to `StreamTransferProgress`, and preserve all current throttling consumers until later tasks enhance their display.

- [ ] **Step 5: Run stream and coordinator tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.StreamTransferTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/data/repository/StreamTransfer.kt app/src/test/java/com/voyagerfiles/data/repository/StreamTransferTest.kt app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt app/src/main/java/com/voyagerfiles/viewmodel/TransferProgress.kt app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "refactor: share stream transfer progress"
```

### Task 4: Upload sizes and progress

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/util/UploadSourceFactoryTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/UploadSource.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/UploadSourceFactory.kt`
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `UploadSource(name: String, size: Long? = null, openInputStream: () -> InputStream)` and upload `onProgress: (StreamTransferProgress) -> Unit`.

- [ ] **Step 1: Write failing size and upload-progress tests**

Use a real test `ContentProvider` cursor fixture with literal display name and size values. Assert a zero-length document yields `size = 0`, a null size yields `null`, and coordinator upload reports exact bytes and total without running on the caller thread.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.UploadSourceFactoryTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: compilation or assertion FAIL because upload size and progress are absent.

- [ ] **Step 3: Query `OpenableColumns.SIZE` without zero-filling nulls**

Extend the content query projection to display name and size, use `cursor.isNull(sizeColumn)` to preserve unknown size, and pass the value into `UploadSource`.

- [ ] **Step 4: Stream upload through `StreamTransfer.copy` and publish ViewModel progress**

Add the callback to `uploadFile`, then update `uploadDocuments` with completed/total item counts, current filename, active-stream bytes, total, and elapsed duration. Reuse the existing byte publication threshold.

- [ ] **Step 5: Run upload tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.UploadSourceFactoryTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.viewmodel.DocumentUploadTest --stacktrace`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/UploadSource.kt app/src/main/java/com/voyagerfiles/util/UploadSourceFactory.kt app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/test/java/com/voyagerfiles/util/UploadSourceFactoryTest.kt app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "feat: report upload progress"
```

### Task 5: Recursive download progress

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/sftp/SftpFileProviderTest.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavFileProviderTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/repository/FileDownloader.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Consumes: `StreamTransfer.copy`.
- Produces: `DownloadProgress(completedRequestedItems, totalRequestedItems, stream: StreamTransferProgress?)` callback from `FileDownloader.download`.

- [ ] **Step 1: Add failing real-provider download progress assertions**

For embedded SFTP and WebDAV fixtures, download a literal multi-buffer payload and assert the final event names the real remote path, reports exact bytes and total, and the output bytes match. Add a directory fixture and assert nested filenames appear while top-level completion reaches the requested item count.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SftpFileProviderTest --tests com.voyagerfiles.data.remote.webdav.WebDavFileProviderTest --stacktrace`

Expected: compilation FAIL because `FileDownloader.download` has no callback.

- [ ] **Step 3: Add recursive download events**

Stream every file through `StreamTransfer.copy`. Publish an item-start event with `stream = null`, forward stream events for nested files, and increment `completedRequestedItems` only after each requested top-level file or directory completes.

- [ ] **Step 4: Publish download progress in the ViewModel**

Map `DownloadProgress` into `TransferProgress(label = "Downloading", completedItems, totalItems, currentItemName, copiedBytes, totalBytes, elapsedNanos)` and preserve indeterminate progress for unknown totals.

- [ ] **Step 5: Run provider and ViewModel tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SftpFileProviderTest --tests com.voyagerfiles.data.remote.webdav.WebDavFileProviderTest --stacktrace`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/data/repository/FileDownloader.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/test/java/com/voyagerfiles/data/remote/sftp/SftpFileProviderTest.kt app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavFileProviderTest.kt
git commit -m "feat: report recursive download progress"
```

### Task 6: Byte detail and transfer speed UI

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/TransferProgressTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/TransferProgress.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserOperationProgressTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`

**Interfaces:**
- Produces: `elapsedNanos`, `bytesPerSecond`, `byteProgressText`, and `speedText` on `TransferProgress`.

- [ ] **Step 1: Write failing literal formatting tests**

```kotlin
@Test
fun formatsTransferredBytesAndAverageSpeedWithoutInventingTotals() {
    val known = TransferProgress("Downloading", copiedBytes = 1_572_864, totalBytes = 3_145_728, elapsedNanos = 1_500_000_000)
    assertEquals("1.5 MB of 3 MB", known.byteProgressText)
    assertEquals("1 MB/s", known.speedText)

    val unknown = TransferProgress("Downloading", copiedBytes = 1_024, totalBytes = null, elapsedNanos = 1_000_000_000)
    assertEquals("1 KB", unknown.byteProgressText)
    assertEquals("1 KB/s", unknown.speedText)
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.TransferProgressTest --stacktrace`

Expected: compilation FAIL because elapsed and formatted properties are missing.

- [ ] **Step 3: Implement truthful formatting**

Require nonnegative elapsed duration, derive bytes per second only when elapsed and transferred bytes are positive, format with `FileItem.formatFileSize`, and append byte progress and speed to `detailText` and `stateDescription` without adding `0%` for unknown totals.

- [ ] **Step 4: Update the Compose progress test**

Render known and unknown totals and assert literal detail strings plus accessible `StateDescription` values include active filename, item count, byte progress, percentage when known, and speed.

- [ ] **Step 5: Run unit and device progress tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.TransferProgressTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserOperationProgressTest --stacktrace`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/TransferProgress.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/viewmodel/TransferProgressTest.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserOperationProgressTest.kt
git commit -m "feat: show transfer bytes and speed"
```

### Task 7: Verification, SAF device reproduction, and PR

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`

- [ ] **Step 1: Update architecture and testing documentation**

Document provider-created opaque destinations, byte/speed fields, unknown-total behavior, and the real SAF plus disposable SMB verification procedures.

- [ ] **Step 2: Run the complete local gate**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`

Expected: BUILD SUCCESSFUL with zero failures.

- [ ] **Step 3: Run device instrumentation**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest --stacktrace`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 4: Verify real SAF writes on K60**

Create a disposable `VoyagerSafTest` document-tree folder through Android's picker, copy and move a small file and a nested directory from internal storage into it, verify exact server-side/device byte counts, then remove the disposable fixtures. Confirm no `rbDocOpen` or `EPERM` appears in logcat.

- [ ] **Step 5: Verify SMB when credentials are configured**

If every `VOYAGER_SMB_*` variable documented in `docs/TESTING.md` is present, run `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbFileProviderTest --stacktrace` and manually observe upload/download progress against the disposable share. If credentials are absent, record SMB integration as unavailable while relying on shared stream and embedded-provider coverage.

- [ ] **Step 6: Commit, review, push, and open PR**

```bash
git add docs/ARCHITECTURE.md docs/TESTING.md
git commit -m "docs: describe reliable transfer progress"
git push -u origin codex/transfer-correctness-progress
gh pr create --base master --head codex/transfer-correctness-progress --title "Fix SAF writes and report transfer progress" --body "Closes #44\nCloses #45"
```
