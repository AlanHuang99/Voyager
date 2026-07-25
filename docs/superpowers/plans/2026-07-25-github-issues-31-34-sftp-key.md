# GitHub Issues 31-34 and SFTP Public-Key Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add accessible SFTP public-key export, Android-managed file defaults, PDF thumbnails, transfer progress, and safe archive creation and extraction.

**Architecture:** Keep provider operations behind `FileProvider`, add small pure models for intent policy, progress, archive formats, and archive paths, and isolate Android-only UI and PDF rendering. Stream file contents through bounded buffers and clean every partial destination after failure.

**Tech Stack:** Kotlin 2.1, Android API 26-35, Jetpack Compose Material 3, Kotlin coroutines, JSch 2.28.2, Apache Commons Compress 1.28.0, JUnit 4, Android instrumentation, Docker with OpenSSH.

## Global Constraints

- Android 8.0 API 26 remains the minimum.
- Generated private keys remain in app-private storage and are never exported by the public-key flow.
- Missing, zero, and unknown sizes remain distinct.
- Archive extraction never overwrites, follows links, accepts unsafe paths, or leaves partial output.
- Stream buffers stay bounded and network or filesystem I/O stays off the Android main thread.
- Runtime dependencies must be FLOSS-compatible with GPLv3 and F-Droid.
- RAR extraction remains disabled because no patched FLOSS Android binding is available as of 2026-07-25.
- No release, tag, package publication, or version bump is part of this plan.

---

### Task 1: Android-Managed File Defaults

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt`

**Interfaces:**
- Produces: `FileUtils.createOpenFileIntent(context: Context, file: FileItem): Result<Intent>`
- Produces: `FileUtils.openFile(context: Context, file: FileItem): Result<Unit>`

- [ ] **Step 1: Write the failing direct-intent tests**

Add instrumentation cases that call `createOpenFileIntent()` for local and SAF PDFs and assert `Intent.ACTION_VIEW`, the original SAF URI or local `FileProvider` URI, `application/pdf`, `FLAG_GRANT_READ_URI_PERMISSION`, and an action other than `Intent.ACTION_CHOOSER`.

- [ ] **Step 2: Run the focused instrumentation test and verify RED**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest --stacktrace`

Expected: compilation fails because `createOpenFileIntent()` does not exist.

- [ ] **Step 3: Implement direct implicit opening**

Extract the existing URI and `ACTION_VIEW` construction into `createOpenFileIntent()`. Make `openFile()` start the returned intent directly inside `runCatching`, adding `FLAG_ACTIVITY_NEW_TASK` when the context is not an `Activity`. Keep `createChooser()` only in the sharing path.

- [ ] **Step 4: Surface launch failure without crashing**

Update both browser item-click branches to fold the `Result`; on failure call a public ViewModel message helper with `No app can open this file type` for `ActivityNotFoundException` and `Could not open this file` otherwise.

- [ ] **Step 5: Verify GREEN**

Run the focused instrumentation command again and run `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.FileUtilsTest --stacktrace`.

- [ ] **Step 6: Commit**

Commit message: `fix: honor Android file app defaults`

---

### Task 2: Accessible Generated SFTP Public Keys

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/sftp/SshKeyGenerator.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/sftp/SshKeyGeneratorTest.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/components/GeneratedPublicKeyDialogTest.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/remote/sftp/SftpDockerIntegrationTest.kt`
- Modify: `docs/TESTING.md`

**Interfaces:**
- Produces: `GeneratedSshKeyPair(privateKeyFile: File, publicKeyFile: File, publicKey: String)`
- Produces: `GeneratedPublicKeyDialog(publicKey: String, onCopy: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Write failing generator and dialog tests**

Extend `SshKeyGeneratorTest` to assert that `generated.publicKey` exactly equals the trimmed `.pub` file content and starts with `ssh-rsa `. Add a Compose test that renders `GeneratedPublicKeyDialog` and finds the public-key text plus `Copy`, `Save`, and `Done` actions.

- [ ] **Step 2: Verify RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SshKeyGeneratorTest --stacktrace`

Expected: compilation fails because `publicKey` is absent.

- [ ] **Step 3: Return public-key content from generation**

Read the generated `.pub` file after writing and permission hardening, trim only trailing line endings, and include the value in `GeneratedSshKeyPair`.

- [ ] **Step 4: Add public-key UI actions**

Replace the inaccessible-path message with `GeneratedPublicKeyDialog`. Copy through Android `ClipboardManager` using a `ClipData.newPlainText("SFTP public key", publicKey)`. Save through `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"))`, writing UTF-8 text plus one terminal newline on `Dispatchers.IO`. Use `<safe key base name>.pub` as the suggested filename and never place private-key content into UI state.

- [ ] **Step 5: Verify generator and dialog GREEN**

Run the focused JVM test and the focused `GeneratedPublicKeyDialogTest` instrumentation class.

- [ ] **Step 6: Add the opt-in real OpenSSH test**

Implement `SftpDockerIntegrationTest` with `Assume.assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")`. Generate the key with production `SshKeyGenerator`, start `atmoz/sftp:alpine` with the public key mounted read-only under `/home/voyager/.ssh/keys/`, discover the random published port with `docker port`, wait by retrying a provider list operation, then upload and download `/upload/probe.txt` through production `SftpFileProvider` with an empty password. Always disconnect and `docker rm -f` in `finally`.

- [ ] **Step 7: Run the Docker integration**

Run: `VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SftpDockerIntegrationTest --stacktrace`

Expected: key-only login succeeds and the uploaded bytes round-trip exactly.

- [ ] **Step 8: Document and commit**

Add the exact Docker command to `docs/TESTING.md`.

Commit message: `fix: expose generated SFTP public keys`

---

### Task 3: First-Page PDF Thumbnails

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/components/PdfThumbnailLoader.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileThumbnail.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/FileItem.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/components/PdfThumbnailLoaderTest.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/components/FileThumbnailTest.kt`

**Interfaces:**
- Produces: `FileItem.isPdf: Boolean`
- Produces: `PdfThumbnailLoader.load(context: Context, file: FileItem, maxWidth: Int, maxHeight: Int): Result<Bitmap>`
- Produces: `PdfThumbnailLoader.clear()`

- [ ] **Step 1: Write failing renderer tests**

Create a one-page PDF in `context.cacheDir` with `PdfDocument`, call `PdfThumbnailLoader.load()`, and assert a non-empty bitmap whose center pixel differs from its white background. Add malformed, empty-page, remote-source, and SAF URI cases. Extend `FileThumbnailTest` with semantics tags for successful PDF content and fallback.

- [ ] **Step 2: Verify RED**

Run the focused PDF instrumentation classes.

Expected: compilation fails because `PdfThumbnailLoader` and `FileItem.isPdf` do not exist.

- [ ] **Step 3: Implement bounded asynchronous rendering**

Open local files with `ParcelFileDescriptor.open()` and SAF files with `ContentResolver.openFileDescriptor()`. On `Dispatchers.IO`, create `PdfRenderer`, reject `pageCount == 0`, render page zero into an ARGB bitmap scaled within the requested bounds, and close page, renderer, and descriptor through `use`.

- [ ] **Step 4: Add a byte-bounded cache**

Use `android.util.LruCache<String, Bitmap>` whose size is `bitmap.allocationByteCount` and whose maximum is the smaller of 16 MiB or one-eighth of the process max memory. Key entries by source, path, last-modified milliseconds, width, and height.

- [ ] **Step 5: Integrate with Compose**

For local or SAF PDFs, use `produceState` keyed by the file and pixel bounds, render off the main thread, show the document icon while loading or on failure, and display the bitmap with `ContentScale.Crop` when available. Preserve existing Coil behavior for local images.

- [ ] **Step 6: Verify GREEN and commit**

Run the focused instrumentation tests, `testDebugUnitTest`, and `lintDebug`.

Commit message: `feat: render PDF first-page thumbnails`

---

### Task 4: Truthful Copy and Move Progress

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/viewmodel/TransferProgress.kt`
- Create: `app/src/test/java/com/voyagerfiles/viewmodel/TransferProgressTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/OperationState.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserOperationProgressTest.kt`

**Interfaces:**
- Produces: `TransferProgress(label: String, completedItems: Int = 0, totalItems: Int? = null, currentItemName: String? = null, copiedBytes: Long = 0, totalBytes: Long? = null)`
- Produces: `TransferProgress.fraction: Float?`
- Modifies: `OperationState.Running(val progress: TransferProgress)` with `label` delegating to `progress.label`
- Modifies: `FileOperationCoordinator.copyPath(..., onProgress: (StreamCopyProgress) -> Unit = {})`
- Modifies: `FileOperationCoordinator.movePath(..., onProgress: (StreamCopyProgress) -> Unit = {})`

- [ ] **Step 1: Write failing progress-model tests**

Cover zero bytes as a determinate zero only when `totalBytes > 0`, byte fractions clamped to `0f..1f`, completed-item fallback when byte total is unknown, null when no truthful denominator exists, and user-facing `2 of 5` text.

- [ ] **Step 2: Verify model RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.TransferProgressTest --stacktrace`

Expected: compilation fails because `TransferProgress` does not exist.

- [ ] **Step 3: Implement the immutable progress model**

Keep unknown totals nullable and reject negative counts or byte values with `require`.

- [ ] **Step 4: Write and verify failing coordinator callback tests**

Copy a payload larger than two 64 KiB buffers and assert monotonically increasing byte callbacks ending at the exact payload size. Copy a nested directory and assert callbacks identify each active file. Verify no callback reports completion before a write succeeds.

- [ ] **Step 5: Replace `InputStream.copyTo` with a reporting loop**

Read into the existing 64 KiB buffer, write exactly the bytes read, increment a per-file counter, and call `onProgress(StreamCopyProgress(path, bytesCopied, item.size.takeUnless { item.isDirectory }))`. This preserves a known zero-byte file as distinct from a directory whose total is unknown. Keep all work inside `Dispatchers.IO`.

- [ ] **Step 6: Publish ViewModel item and byte progress**

In `paste()`, resolve selected `FileItem` values, set total item count, update current item before work, forward cross-provider byte callbacks, and increment completed items only on success. Limit state publication to completion, a change of item, or at least 256 KiB of additional bytes.

- [ ] **Step 7: Render accessible progress**

Extract an internal `OperationProgressContent` composable. Use determinate `LinearProgressIndicator(progress = { fraction })` when non-null and indeterminate otherwise. Show operation label, current filename, item count, and percentage only when truthful. Set `stateDescription` to the same reader-facing status.

- [ ] **Step 8: Verify and commit**

Run focused model, coordinator, and UI tests, then `testDebugUnitTest` and `lintDebug`.

Commit message: `feat: show copy and move progress`

---

### Task 5: Safe Archive Core

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/voyagerfiles/data/archive/ArchiveFormat.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/archive/ArchiveEntryPath.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/archive/ArchiveService.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/archive/ArchiveException.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/archive/ArchiveFormatTest.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/archive/ArchiveEntryPathTest.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/archive/ArchiveServiceTest.kt`

**Interfaces:**
- Produces: `ArchiveFormat.detect(fileName: String): ArchiveFormat?`
- Produces: `ArchiveFormat.ZIP`, `TAR`, `TAR_GZIP`, `TAR_BZIP2`, `GZIP`, `BZIP2`, and `RAR_UNSUPPORTED`
- Produces: `ArchiveEntryPath.parse(raw: String): Result<List<String>>`
- Produces: `ArchiveService.createZip(provider: FileProvider, selectedItems: List<FileItem>, destinationDirectory: String, archiveName: String, onProgress: (ArchiveProgress) -> Unit = {}): Result<FileItem>`
- Produces: `ArchiveService.extract(provider: FileProvider, archive: FileItem, destinationDirectory: String, onProgress: (ArchiveProgress) -> Unit = {}): Result<FileItem>`

- [ ] **Step 1: Add Commons Compress**

Add `implementation("org.apache.commons:commons-compress:1.28.0")` and exclude duplicate license resources only if the build reports collisions.

- [ ] **Step 2: Write failing format and path tests**

Cover case-insensitive compound suffixes, archive stem derivation, RAR recognition, absolute Unix and Windows paths, drive prefixes, backslash normalization, NUL, blank segments, `.` and `..`, and valid Unicode path segments.

- [ ] **Step 3: Verify RED and implement pure parsing**

Run the two focused classes, implement the enums and parser, and rerun them GREEN.

- [ ] **Step 4: Write failing ZIP creation and extraction tests**

Use an in-memory `FileProvider` with files and nested directories. Assert ZIP entry names and contents, extraction into a new sibling directory, no overwrite, duplicate normalized entry rejection, symlink rejection, zip-slip rejection, bounded reads, progress events, and cleanup of a partial archive or extraction tree after an injected write failure.

- [ ] **Step 5: Implement ZIP streaming**

Use `ZipArchiveOutputStream` and `ZipArchiveInputStream`. Write explicit directory entries, use forward-slash relative names, preserve file modification time when known, reject duplicate normalized names, and close each archive entry in `finally`. Do not close a provider stream before the archive stream finishes.

- [ ] **Step 6: Write failing TAR-family and raw compression tests**

Build TAR, TGZ, TBZ2, GZ, and BZ2 fixtures with Commons Compress. Assert exact extracted bytes, link rejection, corrupt-input failure, cleanup, and `UnsupportedArchiveException` for RAR.

- [ ] **Step 7: Implement TAR, GZIP, and BZIP2 extraction**

Wrap `TarArchiveInputStream` in `GzipCompressorInputStream` or `BZip2CompressorInputStream` for compound formats. Treat plain GZ and BZ2 as one output file inside a new `<stem>_extracted` directory. Reject tar symbolic links, hard links, devices, and FIFOs.

- [ ] **Step 8: Run archive tests and dependency checks**

Run all archive JVM tests, `./gradlew dependencies`, `lintDebug`, `assembleDebug`, and `assembleRelease`. Inspect the resolved dependency graph and APK contents for unexpected licenses or native binaries.

- [ ] **Step 9: Commit**

Commit message: `feat: add safe archive operations`

---

### Task 6: Archive Browser Actions

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/components/ArchiveNameDialog.kt`
- Create: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserArchiveActions.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserArchiveActionsTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/OperationMessages.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserArchiveActionsTest.kt`

**Interfaces:**
- Produces: `BrowserArchiveActions.forSelection(items: List<FileItem>): Set<BrowserArchiveAction>`
- Produces: `FileBrowserViewModel.createZipFromSelection(fileName: String)`
- Produces: `FileBrowserViewModel.extractSelectedArchive()`

- [ ] **Step 1: Write failing action-model tests**

Assert Compress to ZIP for any non-empty selection, Extract here only for one supported archive, a disabled RAR extraction explanation, and no actions for an empty selection.

- [ ] **Step 2: Verify RED and implement the action model**

Run the focused JVM test and make it GREEN without Compose dependencies.

- [ ] **Step 3: Write failing UI tests**

Select a normal file and assert `Compress to ZIP`. Select one ZIP and assert `Extract here`. Select one RAR and assert the unsupported message. Open the compression dialog and assert `.zip` validation and a non-overwrite default name.

- [ ] **Step 4: Add ViewModel operations**

Resolve selected items from the active directory, call `ArchiveService` through `launchOperation`, forward `ArchiveProgress` into `TransferProgress`, clear selection only after the operation starts safely, refresh after success, and use exact success messages naming the created archive or extraction directory.

- [ ] **Step 5: Integrate the selection menu and dialog**

Add archive actions to the existing overflow without displacing primary Share, Rename, or Delete actions. Disable mutation commands while an operation is running.

- [ ] **Step 6: Add actionable failures**

Map unsafe entries, corrupt archives, existing destinations, and unsupported RAR to distinct concise messages. Do not report extraction success when cleanup failed.

- [ ] **Step 7: Verify and commit**

Run action-model, UI, archive, ViewModel-adjacent tests, `testDebugUnitTest`, and `lintDebug`.

Commit message: `feat: expose archive actions in browser`

---

### Task 7: Documentation, Full Verification, and GitHub Delivery

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`
- Modify: `docs/superpowers/specs/2026-07-25-github-issues-31-34-sftp-key-design.md`

**Interfaces:**
- Consumes: every completed behavior from Tasks 1-6.

- [ ] **Step 1: Align documentation**

Document Android-managed defaults, local and SAF PDF thumbnails, progress semantics, archive format scope and safety, public-key Copy and Save actions, and the opt-in Docker command. Remove the superseded RAR extraction claim from the design and preserve the CVE and licensing rationale.

- [ ] **Step 2: Run focused regression tests fresh**

Run every focused test command from Tasks 1-6 without relying on previous output.

- [ ] **Step 3: Run the complete local gate fresh**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`

Read the full result, confirm zero failed tests and zero lint errors, and inspect release minification output.

- [ ] **Step 4: Run device integration fresh**

If `adb devices` has an authorized device, run all `connectedDebugAndroidTest`, install the debug APK, and manually verify public-key export, default-app resolution, PDF thumbnails, visible transfer progress, ZIP creation, and archive extraction with disposable files.

- [ ] **Step 5: Run Docker SFTP integration fresh**

Run the opt-in Docker test, inspect the container log for accepted public-key authentication, and confirm the round-trip bytes.

- [ ] **Step 6: Inspect source and artifacts**

Run `git diff --check`, inspect `git status`, review every changed file, verify no secrets or temporary keys are tracked, and inspect APK metadata and sizes.

- [ ] **Step 7: Commit final documentation**

Commit message: `docs: document new file workflows`

- [ ] **Step 8: Push and open a pull request**

Push `codex/github-issues-31-34-sftp-key`, open a non-draft PR against `master`, include exact verification evidence, and reference issues 31, 32, 33, 34, and the follow-up on issue 6. Do not claim RAR extraction support.

- [ ] **Step 9: Wait for CI and address failures**

Monitor the GitHub Actions run through completion. If a check fails, inspect its logs, reproduce locally, add a regression test when behavior is wrong, fix the root cause, rerun the complete gate, commit, and push.

- [ ] **Step 10: Update GitHub discussions**

After CI succeeds, comment on issues 6 and 31-34 with the implemented behavior and verification. Close only reports fully addressed by the PR. Leave issue 34 open if maintainers want RAR support tracked separately.
