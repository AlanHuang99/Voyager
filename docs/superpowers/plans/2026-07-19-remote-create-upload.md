# Remote Create and Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore remote folder and file creation and add direct multi-file upload from Android's document picker.

**Architecture:** BrowserScreen will render an explicit provider-aware Create menu and launch OpenMultipleDocuments for Upload Files. FileBrowserViewModel will resolve selected URIs into validated lazy stream sources, then FileOperationCoordinator will stream each source to the active provider with conflict protection, I/O dispatching, partial-file cleanup, and batch result reporting.

**Tech Stack:** Kotlin 2.1, Jetpack Compose Material 3, Android Activity Result API, Android ContentResolver, Kotlin coroutines, JUnit 4, AndroidX Compose UI tests.

## Global Constraints

- Show New Folder and New File in local, SAF, FTP, SFTP, SMB, and WebDAV browser sessions.
- Show Upload Files only for network sessions.
- Stream selected documents with a 64 KiB buffer and never load a whole file into memory.
- Refuse destination conflicts and preserve existing content.
- Validate every selected display name before starting any transfer.
- Run resolver and stream I/O on Dispatchers.IO.
- Validate an existing FTP control connection with NOOP and reconnect before reuse when the peer has closed it.
- Keep prose in Markdown as one paragraph per physical line and do not add AI-authorship markers.

---

### Task 1: Stream a selected document into a provider

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/viewmodel/UploadSource.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`
- Test: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`

**Interfaces:**
- Consumes: `FileProvider.exists(path)`, `FileProvider.getOutputStream(path)`, and `FileProvider.delete(path)`.
- Produces: `data class UploadSource(val name: String, val openInputStream: () -> InputStream)` and `suspend fun FileOperationCoordinator.uploadFile(source: UploadSource, destinationProvider: FileProvider, destinationDirectoryPath: String): Result<Unit>`.

- [ ] **Step 1: Write failing coordinator tests**

Add tests that upload `report.txt` from a ByteArrayInputStream, assert that the destination contains the exact bytes, record the write thread and assert it differs from the caller thread, pre-create `/remote/report.txt` and assert DestinationConflictException without overwrite, and use FailingWriteProvider to assert a partial `/remote/report.txt` is removed while the source factory remains reusable.

```kotlin
@Test
fun uploadStreamsSelectedDocumentOffTheCallingThread() = runBlocking {
    val callerThread = Thread.currentThread().name
    val writeThread = AtomicReference<String?>(null)
    val destination = ThreadRecordingProvider(writeThread).apply { putDirectory("/remote") }
    val source = UploadSource("report.txt") { ByteArrayInputStream("report".toByteArray()) }

    FileOperationCoordinator.uploadFile(source, destination, "/remote").getOrThrow()

    assertEquals("report", destination.readFile("/remote/report.txt"))
    assertNotEquals(callerThread, writeThread.get())
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: compilation fails because UploadSource and uploadFile do not exist.

- [ ] **Step 3: Add UploadSource and the minimal streaming implementation**

```kotlin
data class UploadSource(
    val name: String,
    val openInputStream: () -> InputStream,
)
```

Implement uploadFile with `withContext(Dispatchers.IO)`, the existing joinPath helper, an `exists` conflict check, `use` blocks around both streams, `copyTo(output, BUFFER_SIZE)`, and the same guarded target deletion used by copyPathInternal.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --stacktrace`

Expected: all FileOperationCoordinatorTest cases pass.

- [ ] **Step 5: Commit the coordinator boundary**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/UploadSource.kt app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "feat: stream document uploads to providers"
```

### Task 2: Model and render provider-aware Create actions

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserCreateMenu.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserCreateMenuModelTest.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserCreateMenuTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`

**Interfaces:**
- Consumes: `isNetwork` from BrowseState's FileSource.
- Produces: `BrowserCreateAction`, `BrowserCreateMenuModel.forState(isRemote: Boolean)`, and `BrowserCreateMenu(expanded, model, onDismiss, onAction)`.

- [ ] **Step 1: Write failing menu-model and Compose tests**

```kotlin
@Test
fun remoteMenuIncludesCreateAndUploadActions() {
    assertEquals(
        listOf(NEW_FOLDER, NEW_FILE, UPLOAD_FILES),
        BrowserCreateMenuModel.forState(isRemote = true).actions,
    )
}

@Test
fun localMenuOmitsUploadAction() {
    assertEquals(
        listOf(NEW_FOLDER, NEW_FILE),
        BrowserCreateMenuModel.forState(isRemote = false).actions,
    )
}
```

The Compose test renders BrowserCreateMenu expanded with the remote model and asserts that New Folder, New File, and Upload Files are displayed.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserCreateMenuModelTest --stacktrace`

Expected: compilation fails because the menu model does not exist.

- [ ] **Step 3: Implement the menu model and composable**

```kotlin
enum class BrowserCreateAction { NEW_FOLDER, NEW_FILE, UPLOAD_FILES }

data class BrowserCreateMenuModel(val actions: List<BrowserCreateAction>) {
    companion object {
        fun forState(isRemote: Boolean) = BrowserCreateMenuModel(
            buildList {
                add(BrowserCreateAction.NEW_FOLDER)
                add(BrowserCreateAction.NEW_FILE)
                if (isRemote) add(BrowserCreateAction.UPLOAD_FILES)
            },
        )
    }
}
```

BrowserCreateMenu maps the three actions to the existing CreateNewFolder and NoteAdd icons plus an UploadFile icon, closes through onDismiss, and reports the selected enum through onAction. BrowserScreen replaces the `if (!isNetwork)` block with the menu model and retains the existing dialog callbacks for New Folder and New File.

- [ ] **Step 4: Run unit and instrumented menu tests and verify GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserCreateMenuModelTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserCreateMenuTest --stacktrace`

Expected: both commands pass and the device test reports one passing test.

- [ ] **Step 5: Commit the provider-aware Create menu**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserCreateMenu.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserCreateMenuModelTest.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserCreateMenuTest.kt
git commit -m "fix: expose create actions for remote sessions"
```

### Task 3: Resolve document URIs and upload a validated batch

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/util/UploadSourceFactory.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/viewmodel/DocumentUploadTest.kt`

**Interfaces:**
- Consumes: `UploadSource`, `FileOperationCoordinator.uploadFile`, `FileNameValidator`, and `ActivityResultContracts.OpenMultipleDocuments`.
- Produces: `UploadSourceFactory.fromUri(contentResolver: ContentResolver, uri: Uri): UploadSource` and `FileBrowserViewModel.uploadDocuments(uris: List<Uri>)`.

- [ ] **Step 1: Write a failing end-to-end Android upload test**

Create a source file in the application cache, expose it with the existing FileProvider authority, open a separate local destination root in FileBrowserViewModel, call uploadDocuments with the URI, wait for OperationState.Idle, and assert that the destination file has the source name and exact contents.

```kotlin
val sourceUri = FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", sourceFile)
viewModel.openLocalRoot(destination.absolutePath)
viewModel.uploadDocuments(listOf(sourceUri))
composeTestRule.waitUntil(10_000) { destination.resolve(sourceFile.name).exists() }
assertEquals(sourceFile.readText(), destination.resolve(sourceFile.name).readText())
```

- [ ] **Step 2: Run the focused device test and verify RED**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.viewmodel.DocumentUploadTest --stacktrace`

Expected: compilation fails because uploadDocuments does not exist.

- [ ] **Step 3: Implement URI resolution and batch upload**

UploadSourceFactory queries OpenableColumns.DISPLAY_NAME, falls back to the URI's last path segment as required by the Android OpenableColumns contract, and creates a lazy input-stream factory that throws IOException when ContentResolver returns null.

FileBrowserViewModel.uploadDocuments returns for an empty selection, captures the active provider and destination path, resolves all URIs on Dispatchers.IO, validates every name before transferring any file, uploads sequentially through FileOperationCoordinator, refreshes once, reports `Uploaded N file` or `Uploaded N files` on complete success, and uses OperationMessages.partial with action `uploaded` and the first error when any source fails.

BrowserScreen registers `rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.uploadDocuments(it) }` and launches it with `arrayOf("*/*")` when BrowserCreateAction.UPLOAD_FILES is selected.

- [ ] **Step 4: Run focused unit and device tests and verify GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest --tests com.voyagerfiles.ui.screens.BrowserCreateMenuModelTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.viewmodel.DocumentUploadTest,com.voyagerfiles.ui.screens.BrowserCreateMenuTest --stacktrace`

Expected: all focused tests pass and the uploaded file contents match exactly.

- [ ] **Step 5: Commit picker integration**

```bash
git add app/src/main/java/com/voyagerfiles/util/UploadSourceFactory.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/viewmodel/DocumentUploadTest.kt
git commit -m "feat: upload selected documents to remote sessions"
```

### Task 4: Recover an FTP session after the document picker

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/ftp/FtpFileProvider.kt`
- Test: `app/src/test/java/com/voyagerfiles/data/remote/ftp/FtpFileProviderTest.kt`

**Interfaces:**
- Consumes: Apache Commons Net `FTPClient.sendNoOp()`, `FTPClient.disconnect()`, and the existing FTP login sequence.
- Produces: `FtpFileProvider.ensureConnected()` that validates and replaces a stale control connection before provider operations.

- [ ] **Step 1: Write a failing dropped-connection test**

Connect a provider to the in-process FTP server, stop that server while retaining the provider, restart a server on the same port and root, then request an output stream and assert that the uploaded bytes arrive through a replacement connection.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.ftp.FtpFileProviderTest.reconnectsAfterServerDropsTheControlConnection --stacktrace`

Expected: FAIL with FTPConnectionClosedException because `isConnected` does not prove the peer socket is usable.

- [ ] **Step 3: Validate and replace stale clients**

In ensureConnected, call sendNoOp for a non-null connected client. Return only when NOOP succeeds. Otherwise disconnect that client, clear the field, and run the existing connect, reply validation, login, passive-mode, and binary-mode sequence. Disconnect a partially initialized replacement if setup throws.

- [ ] **Step 4: Run all FTP provider tests and verify GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.ftp.FtpFileProviderTest --stacktrace`

Expected: all FTP provider tests pass, including the dropped-control-connection regression.

- [ ] **Step 5: Commit stale-session recovery**

```bash
git add app/src/main/java/com/voyagerfiles/data/remote/ftp/FtpFileProvider.kt app/src/test/java/com/voyagerfiles/data/remote/ftp/FtpFileProviderTest.kt docs/superpowers/specs/2026-07-19-remote-create-upload-design.md docs/superpowers/plans/2026-07-19-remote-create-upload.md
git commit -m "fix: reconnect stale FTP sessions"
```

### Task 5: Verify the complete issue fix

**Files:**
- Modify only if verification finds a defect in files already listed above.

**Interfaces:**
- Consumes: all production and test interfaces introduced in Tasks 1 through 4.
- Produces: a clean branch with reproducible local, device, and CI evidence.

- [ ] **Step 1: Run the complete local gate**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`

Expected: BUILD SUCCESSFUL with no test or lint failures.

- [ ] **Step 2: Run the complete connected test suite**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --stacktrace`

Expected: all instrumented tests pass on the connected Android 16 device.

- [ ] **Step 3: Exercise the FTP workflow on device**

Start a temporary authenticated pyftpdlib server with `uv run --with pyftpdlib python -m pyftpdlib`, connect the debug app to the workstation's LAN address, create a folder through the remote Create menu, upload a known file through the system picker, and verify the server root contains both the new directory and an uploaded file with byte-for-byte matching contents. Keep the server root under a `mktemp -d` directory and remove the temporary server, debug app, device fixture, and directory after verification.

- [ ] **Step 4: Audit the branch**

Run: `git diff --check master...HEAD && git status --short && git log --oneline master..HEAD`

Expected: no whitespace errors, no uncommitted files, and only the design, plan, coordinator, menu, picker, and tests for issue #23.

- [ ] **Step 5: Publish for CI**

Push `codex/ftp-create-upload`, open a focused pull request with `Fixes #23`, wait for Android CI, and merge only after GitHub reports the branch mergeable and every required check successful.
