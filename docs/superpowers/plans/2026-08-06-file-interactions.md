# File Interactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix APK opening, add an explicit Open with action, and make supported archive taps offer safe extraction.

**Architecture:** Keep Android intent construction in `FileUtils`, selection eligibility in `SelectionToolbarModel`, and archive classification in `BrowserArchiveActions`. `BrowserScreen` owns transient chooser and confirmation UI state, while `FileBrowserViewModel` owns the extraction operation.

**Tech Stack:** Kotlin 2.1, Android API 26–35, Jetpack Compose Material 3, AndroidX FileProvider, JUnit 4, AndroidX instrumentation.

## Global Constraints

- Preserve pull request #46 commit `fd80d15fa09bd0ea9ecce34d261e24f95d4284c2` with its original author.
- Ordinary taps continue to honor Android defaults; only the explicit Open with action uses a chooser.
- Only local and SAF files can be opened directly. Remote items must be downloaded first.
- Supported archives require confirmation before extraction. Unsupported archives remain actionable errors.
- Every production change follows red, green, refactor and receives K60 verification on Android 16/API 36.

---

### Task 1: APK installer permission

**Files:**
- Modify: `app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt`
- Modify through preserved commit: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: installed debug package metadata from `PackageManager`.
- Produces: a merged manifest containing `android.permission.REQUEST_INSTALL_PACKAGES`.

- [ ] **Step 1: Write the failing device test**

```kotlin
@Test
fun appCanRequestTheSystemPackageInstallerForApkFiles() {
    val requested = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()

    assertTrue(Manifest.permission.REQUEST_INSTALL_PACKAGES in requested)
}
```

- [ ] **Step 2: Run the test and verify the released manifest contract fails**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest#appCanRequestTheSystemPackageInstallerForApkFiles --stacktrace`

Expected: FAIL because the permission is absent.

- [ ] **Step 3: Preserve the contributor fix**

Run: `git fetch origin pull/46/head:refs/remotes/origin/pr-46 && git cherry-pick fd80d15fa09bd0ea9ecce34d261e24f95d4284c2`

Expected manifest addition:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest#appCanRequestTheSystemPackageInstallerForApkFiles --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit the regression test**

```bash
git add app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt
git commit -m "test: cover APK installer permission"
```

### Task 2: Explicit Open with chooser

**Files:**
- Modify: `app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt`

**Interfaces:**
- Consumes: `createOpenFileIntent(context: Context, file: FileItem): Result<Intent>`.
- Produces: `createOpenWithIntent(context: Context, file: FileItem): Result<Intent>` and `openFileWith(context: Context, file: FileItem): Result<Unit>`.

- [ ] **Step 1: Write the failing chooser-intent test**

```kotlin
@Test
fun openWithWrapsTheReadableViewIntentInAChooser() {
    val file = createLocalFile("report.pdf")

    val chooser = FileUtils.createOpenWithIntent(context, file).getOrThrow()
    val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
    assertEquals(Intent.ACTION_VIEW, target?.action)
    assertEquals("application/pdf", target?.type)
    assertTrue(target!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest#openWithWrapsTheReadableViewIntentInAChooser --stacktrace`

Expected: compilation FAIL because `createOpenWithIntent` does not exist.

- [ ] **Step 3: Implement chooser construction and launch**

```kotlin
fun createOpenWithIntent(context: Context, file: FileItem): Result<Intent> =
    createOpenFileIntent(context, file).map { target ->
        Intent.createChooser(target, "Open with")
    }

fun openFileWith(context: Context, file: FileItem): Result<Unit> =
    createOpenWithIntent(context, file).mapCatching { chooser ->
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
```

- [ ] **Step 4: Run all file-intent tests**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/util/FileUtils.kt app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt
git commit -m "feat: add an explicit Open with chooser"
```

### Task 3: Open with selection eligibility

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`

**Interfaces:**
- Consumes: `SelectionToolbarModel.forState(isRemote, selectionCount, canShare, canOpenWith)`.
- Produces: `SelectionToolbarAction.OPEN_WITH` in overflow only for one directly openable file.

- [ ] **Step 1: Write failing model tests**

```kotlin
@Test
fun oneLocalFileOffersOpenWithInOverflow() {
    val model = SelectionToolbarModel.forState(
        isRemote = false,
        selectionCount = 1,
        canShare = true,
        canOpenWith = true,
    )
    assertTrue(SelectionToolbarAction.OPEN_WITH in model.overflowActions)
}

@Test
fun foldersRemoteItemsAndMultipleSelectionsDoNotOfferOpenWith() {
    assertFalse(SelectionToolbarAction.OPEN_WITH in SelectionToolbarModel.forState(false, 1, false, false).overflowActions)
    assertFalse(SelectionToolbarAction.OPEN_WITH in SelectionToolbarModel.forState(true, 1, false, false).overflowActions)
    assertFalse(SelectionToolbarAction.OPEN_WITH in SelectionToolbarModel.forState(false, 2, true, false).overflowActions)
}
```

- [ ] **Step 2: Run the model tests and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest --stacktrace`

Expected: compilation FAIL because `canOpenWith` and `OPEN_WITH` do not exist.

- [ ] **Step 3: Implement eligibility and menu action**

```kotlin
val canOpenWith = selectedItems.singleOrNull()?.let { item ->
    !item.isDirectory && (item.source == FileSource.LOCAL || item.source == FileSource.SAF)
} == true
```

Add `OPEN_WITH` to `SelectionToolbarAction`, append it to overflow when `canOpenWith`, and launch `FileUtils.openFileWith(context, selectedItems.single())` from a new “Open with” menu item. Reuse the existing `ActivityNotFoundException` snackbar mapping and clear selection only after a successful launch.

- [ ] **Step 4: Run the model and device selection tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "feat: expose Open with for selected files"
```

### Task 4: Archive tap classification

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserArchiveActionsTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserArchiveActions.kt`

**Interfaces:**
- Produces: `ArchiveTapAction` and `BrowserArchiveActions.tapAction(file: FileItem): ArchiveTapAction`.

- [ ] **Step 1: Write failing classification tests**

```kotlin
@Test
fun supportedArchiveTapRequestsConfirmation() {
    assertEquals(ArchiveTapAction.CONFIRM_EXTRACTION, BrowserArchiveActions.tapAction(file("bundle.zip")))
}

@Test
fun unsupportedAndOrdinaryFilesKeepDistinctTapBehavior() {
    assertEquals(ArchiveTapAction.SHOW_UNSUPPORTED, BrowserArchiveActions.tapAction(file("legacy.rar")))
    assertEquals(ArchiveTapAction.OPEN_EXTERNALLY, BrowserArchiveActions.tapAction(file("notes.txt")))
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserArchiveActionsTest --stacktrace`

Expected: compilation FAIL because `ArchiveTapAction` is missing.

- [ ] **Step 3: Implement the classifier**

```kotlin
enum class ArchiveTapAction { CONFIRM_EXTRACTION, SHOW_UNSUPPORTED, OPEN_EXTERNALLY }

fun tapAction(item: FileItem): ArchiveTapAction = when {
    ArchiveFormat.detect(item.name)?.canExtract == true -> ArchiveTapAction.CONFIRM_EXTRACTION
    item.isArchive -> ArchiveTapAction.SHOW_UNSUPPORTED
    else -> ArchiveTapAction.OPEN_EXTERNALLY
}
```

- [ ] **Step 4: Run the focused tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserArchiveActionsTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserArchiveActions.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserArchiveActionsTest.kt
git commit -m "feat: classify archive tap behavior"
```

### Task 5: Confirm extraction from a direct tap

**Files:**
- Modify: `app/src/androidTest/java/com/voyagerfiles/viewmodel/ArchiveOperationsTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`

**Interfaces:**
- Produces: `extractArchive(path: String)` and a Compose confirmation dialog bound to the selected `FileItem`.

- [ ] **Step 1: Write a failing direct-extraction ViewModel test**

Create `bundle.zip` in the active local directory, call `viewModel.extractArchive(archive.absolutePath)` without selecting it, wait for `OperationState.Idle`, and assert the exact extracted file contents under `bundle_extracted`.

- [ ] **Step 2: Run and verify compilation fails**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.viewmodel.ArchiveOperationsTest --stacktrace`

Expected: compilation FAIL because `extractArchive` does not exist.

- [ ] **Step 3: Extract a shared archive operation**

```kotlin
fun extractArchive(path: String) {
    val state = _browseState.value
    val archive = state.files.firstOrNull { it.path == path }
        ?: return showSnackbar("The archive is no longer available. Refresh the folder and try again.")
    launchArchiveExtraction(archive, state.currentPath, clearSelectionAfter = false)
}
```

Refactor `extractSelectedArchive()` to validate its single selection and call the same private `launchArchiveExtraction` helper with `clearSelectionAfter = true`.

- [ ] **Step 4: Write the failing Compose confirmation test**

Render `BrowserScreen` with a supported ZIP, click the ZIP row, assert an “Extract archive?” dialog and Cancel/Extract buttons, click Extract, and assert the ViewModel operation starts. Also assert an unsupported RAR reports the existing reason without showing the confirmation.

- [ ] **Step 5: Implement confirmation UI in both list and grid paths**

Store `archiveToExtract: FileItem?`, classify local/SAF file taps through `BrowserArchiveActions.tapAction`, and show `AlertDialog` whose confirmation calls `viewModel.extractArchive(item.path)`. Keep network taps mapped to download before archive classification.

- [ ] **Step 6: Run archive unit and device tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserArchiveActionsTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.viewmodel.ArchiveOperationsTest,com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/viewmodel/ArchiveOperationsTest.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "feat: confirm extraction when archives are opened"
```

### Task 6: Verification, documentation, and PR

**Files:**
- Modify: `docs/TESTING.md`

- [ ] **Step 1: Document APK, Open with, and archive-tap device coverage**

Update the file-opening and archive rows in the manual regression matrix and the focused instrumentation command.

- [ ] **Step 2: Run the complete local gate**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`

Expected: BUILD SUCCESSFUL with zero test or lint failures.

- [ ] **Step 3: Run complete device instrumentation**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest --stacktrace`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 4: Smoke-test on K60**

Install `app/build/outputs/apk/debug/app-universal-debug.apk`, cleanly restart `com.voyagerfiles.debug`, tap a disposable APK and verify `com.android.packageinstaller` becomes foreground, cancel installation, use Open with on a PDF, and confirm/cancel ZIP extraction once each.

- [ ] **Step 5: Commit docs, request review, push, and open PR**

```bash
git add docs/TESTING.md
git commit -m "docs: cover file interaction regressions"
git push -u origin codex/file-interactions
gh pr create --base master --head codex/file-interactions --title "Fix Android file opening and archive interactions" --body "Closes #39\nCloses #42\nCloses #43"
```
