# Core Safety, Search, and Trash Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local and cross-provider operations fail safely, add current-directory search and type filtering, resolve the image crash, and provide a complete recoverable local Trash workflow.

**Architecture:** Keep `FileProvider` as the operation boundary, add pure Kotlin validation and trash services, and keep screen state in `FileBrowserViewModel`. Browser UI derives visible files from immutable state and uses one operation state to prevent concurrent destructive actions.

**Tech Stack:** Kotlin 2.1, Coroutines, Jetpack Compose Material 3, JUnit 4, Android instrumentation tests.

## Global Constraints

- Preserve the existing Compose, ViewModel, provider, Room, DataStore, and Material 3 conventions.
- Add no runtime dependency.
- Every behavior change starts with a failing focused test and finishes with the focused suite green.
- Never overwrite an existing destination implicitly.
- Never delete a source until a complete copy is verified.
- Local Trash applies only to direct local-storage paths and defaults to enabled.
- Keep prose paragraphs on one physical line.

---

### Task 1: Commit the verified image-thumbnail crash fix

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileThumbnail.kt`
- Test: `app/src/androidTest/java/com/voyagerfiles/ui/components/FileThumbnailTest.kt`

**Interfaces:**
- Consumes: `FileItem.usesLocalImageThumbnail`
- Produces: `FileThumbnailOrIcon(file: FileItem, iconSize: Dp, modifier: Modifier = Modifier)` that accepts a Compose vector fallback without passing it to Coil as model data.

- [ ] **Step 1: Re-run the instrumentation regression test**

Run: `./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.components.FileThumbnailTest`

Expected: the test passes on the K60 and logcat contains no `Unsupported type: ImageVector` exception.

- [ ] **Step 2: Verify the production fallback uses a painter**

```kotlin
val fallbackPainter = rememberVectorPainter(icon)
AsyncImage(
    placeholder = fallbackPainter,
    error = fallbackPainter,
)
```

- [ ] **Step 3: Commit only the crash fix and regression test**

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/FileThumbnail.kt app/src/androidTest/java/com/voyagerfiles/ui/components/FileThumbnailTest.kt
git commit -m "fix: render thumbnail vector fallbacks safely"
```

### Task 2: Validate names before provider calls

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/util/FileNameValidator.kt`
- Create: `app/src/test/java/com/voyagerfiles/util/FileNameValidatorTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `FileNameValidator.validate(name: String): FileNameValidationResult`
- Produces: `sealed interface FileNameValidationResult { data class Valid(val name: String); data class Invalid(val message: String) }`

- [ ] **Step 1: Write failing validator tests**

```kotlin
@Test fun trimsAndAcceptsARegularName() {
    assertEquals(FileNameValidationResult.Valid("report.txt"), FileNameValidator.validate(" report.txt "))
}

@Test fun rejectsTraversalAndSeparators() {
    listOf("", ".", "..", "../secret", "folder/name", "bad\u0000name").forEach { name ->
        assertTrue(FileNameValidator.validate(name) is FileNameValidationResult.Invalid)
    }
}
```

- [ ] **Step 2: Run the validator tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.FileNameValidatorTest`

Expected: compilation fails because `FileNameValidator` does not exist.

- [ ] **Step 3: Implement the minimal validator**

```kotlin
object FileNameValidator {
    fun validate(name: String): FileNameValidationResult {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> FileNameValidationResult.Invalid("Enter a name")
            trimmed == "." || trimmed == ".." -> FileNameValidationResult.Invalid("Choose a different name")
            '/' in trimmed || '\u0000' in trimmed -> FileNameValidationResult.Invalid("Names cannot contain / or NUL")
            else -> FileNameValidationResult.Valid(trimmed)
        }
    }
}
```

- [ ] **Step 4: Run the validator tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.FileNameValidatorTest`

Expected: all validator tests pass.

- [ ] **Step 5: Add inline dialog errors and keep invalid dialogs open**

```kotlin
val validation = FileNameValidator.validate(name)
OutlinedTextField(
    value = name,
    onValueChange = { name = it },
    isError = validation is FileNameValidationResult.Invalid,
    supportingText = { (validation as? FileNameValidationResult.Invalid)?.let { Text(it.message) } },
)
```

- [ ] **Step 6: Commit name validation**

```bash
git add app/src/main/java/com/voyagerfiles/util/FileNameValidator.kt app/src/test/java/com/voyagerfiles/util/FileNameValidatorTest.kt app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt
git commit -m "fix: validate file names before operations"
```

### Task 3: Make local copy and move fail safely

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/data/repository/LocalFileProviderTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/repository/LocalFileProvider.kt`

**Interfaces:**
- Produces unchanged `FileProvider.copy` and `FileProvider.move` contracts with explicit conflict and descendant rejection.

- [ ] **Step 1: Write failing tests for conflicts, self-copy, recursive-copy failure, and source preservation**

```kotlin
@Test fun copyRefusesExistingDestination() = runBlocking {
    val source = temp.newFile("source.txt")
    val destination = temp.newFolder("destination")
    File(destination, source.name).writeText("existing")
    assertTrue(provider.copy(source.path, destination.path).isFailure)
    assertEquals("existing", File(destination, source.name).readText())
}

@Test fun moveDirectoryIntoDescendantIsRejectedWithoutChangingSource() = runBlocking {
    val source = temp.newFolder("source")
    val child = File(source, "child").apply { mkdirs() }
    assertTrue(provider.move(source.path, child.path).isFailure)
    assertTrue(source.exists())
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.LocalFileProviderTest`

Expected: existing destination or descendant tests fail against current behavior.

- [ ] **Step 3: Implement canonical-path preflight and checked recursive copies**

```kotlin
private fun requireSafeDestination(source: File, destinationDirectory: File): File {
    val target = File(destinationDirectory, source.name)
    require(!target.exists()) { "${source.name} already exists in this folder" }
    if (source.isDirectory) require(!target.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) {
        "A folder cannot be copied or moved into itself"
    }
    return target
}
```

Check `copyRecursively` results, delete only newly created partial targets after copy failure, and throw when source deletion fails after a fallback move copy.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.LocalFileProviderTest`

Expected: all local-provider safety tests pass.

- [ ] **Step 5: Commit local operation safety**

```bash
git add app/src/main/java/com/voyagerfiles/data/repository/LocalFileProvider.kt app/src/test/java/com/voyagerfiles/data/repository/LocalFileProviderTest.kt
git commit -m "fix: make local copy and move data-safe"
```

### Task 4: Prevent cross-provider overwrite and clean partial targets

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt`

**Interfaces:**
- Produces: `DestinationConflictException(path: String)`
- Preserves: `copyPath(...)` and `movePath(...)` signatures.

- [ ] **Step 1: Add failing conflict and cleanup tests**

```kotlin
@Test fun copyRefusesExistingDestinationWithoutOverwriting() = runBlocking {
    val source = MemoryProvider().apply { putFile("/local/report.txt", "new") }
    val destination = MemoryProvider().apply { putDirectory("/remote"); putFile("/remote/report.txt", "old") }
    assertTrue(FileOperationCoordinator.copyPath(source, destination, "/local/report.txt", "/remote").isFailure)
    assertEquals("old", destination.readFile("/remote/report.txt"))
}

@Test fun failedCopyRemovesNewPartialTargetAndKeepsSource() = runBlocking {
    val source = MemoryProvider().apply { putFile("/local/report.txt", "report") }
    val destination = FailingWriteProvider().apply { putDirectory("/remote") }
    assertTrue(FileOperationCoordinator.movePath(source, destination, "/local/report.txt", "/remote").isFailure)
    assertFalse(destination.exists("/remote/report.txt"))
    assertTrue(source.exists("/local/report.txt"))
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest`

Expected: overwrite and partial-target assertions fail.

- [ ] **Step 3: Add preflight and best-effort cleanup**

```kotlin
if (destinationProvider.exists(targetPath)) throw DestinationConflictException(targetPath)
try {
    copyNewPath(...)
} catch (error: Throwable) {
    runCatching { if (destinationProvider.exists(targetPath)) destinationProvider.delete(targetPath).getOrThrow() }
    throw error
}
```

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.FileOperationCoordinatorTest`

Expected: all coordinator tests pass.

- [ ] **Step 5: Commit cross-provider safety**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/FileOperationCoordinator.kt app/src/test/java/com/voyagerfiles/viewmodel/FileOperationCoordinatorTest.kt
git commit -m "fix: reject unsafe cross-provider paste"
```

### Task 5: Add search, type filters, and selection reconciliation

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/data/model/FileFilter.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/model/BrowseStateTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/FileItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt`

**Interfaces:**
- Produces: `enum class FileTypeFilter`
- Produces: `BrowseState.query`, `BrowseState.typeFilter`, and `BrowseState.visibleFiles`
- Produces: `setSearchQuery(query: String)` and `setTypeFilter(filter: FileTypeFilter)`.

- [ ] **Step 1: Write failing state tests**

```kotlin
@Test fun searchIsCaseInsensitiveAndFiltersByType() {
    val state = BrowseState(files = fixtures, query = "PHOTO", typeFilter = FileTypeFilter.IMAGES)
    assertEquals(listOf("photo.jpg"), state.visibleFiles.map { it.name })
}

@Test fun selectAllUsesOnlyVisibleFiles() {
    val state = BrowseState(files = fixtures, query = "report")
    assertEquals(setOf("/report.txt"), state.visibleFiles.map { it.path }.toSet())
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.model.BrowseStateTest`

Expected: missing query and filter symbols fail compilation.

- [ ] **Step 3: Implement derived visibility**

```kotlin
val visibleFiles: List<FileItem>
    get() = files.filter { item ->
        item.name.contains(query.trim(), ignoreCase = true) && typeFilter.matches(item)
    }
```

- [ ] **Step 4: Update ViewModel selection and load state**

```kotlin
fun selectAll() = _browseState.update { state ->
    state.copy(selectedFiles = state.visibleFiles.mapTo(mutableSetOf()) { it.path })
}
```

Clear the query on navigation and intersect selection with loaded visible paths after refresh or filter changes.

- [ ] **Step 5: Add a search field, filter chips, explicit sort direction, and responsive selection overflow**

Use Material 3 `SearchBar` or an `OutlinedTextField` in the existing second toolbar row, `FilterChip` controls in a horizontally scrollable row, and a tested toolbar model that keeps at most copy, cut, delete, and overflow visible in portrait.

- [ ] **Step 6: Run model and toolbar tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.model.BrowseStateTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest`

Expected: search, filter, selection, and toolbar tests pass.

- [ ] **Step 7: Commit browser discovery and responsive selection**

```bash
git add app/src/main/java/com/voyagerfiles/data/model/FileFilter.kt app/src/main/java/com/voyagerfiles/data/model/FileItem.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/data/model/BrowseStateTest.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt
git commit -m "feat: add browser search and type filters"
```

### Task 6: Add recoverable local Trash

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/data/model/TrashEntry.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/repository/LocalTrashManager.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/repository/LocalTrashManagerTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `LocalTrashManager.moveToTrash(path: String): Result<TrashEntry>`
- Produces: `LocalTrashManager.listEntries(): List<TrashEntry>`
- Produces: `LocalTrashManager.restore(entry: TrashEntry): Result<Unit>`
- Produces: `LocalTrashManager.deletePermanently(entry: TrashEntry): Result<Unit>` and `empty(): Result<Int>`
- Produces: `PreferencesManager.useTrash: Flow<Boolean>` defaulting to true.

- [ ] **Step 1: Write failing Trash tests**

```kotlin
@Test fun moveAndRestorePreserveContentsAndOriginalPath() {
    val file = File(volume, "Documents/report.txt").apply { parentFile!!.mkdirs(); writeText("report") }
    val entry = manager.moveToTrash(file.path).getOrThrow()
    assertFalse(file.exists())
    manager.restore(entry).getOrThrow()
    assertEquals("report", file.readText())
}

@Test fun restoreRefusesExistingTargetAndKeepsTrashEntry() {
    val entry = manager.moveToTrash(source.path).getOrThrow()
    source.writeText("replacement")
    assertTrue(manager.restore(entry).isFailure)
    assertTrue(entry.payload.exists())
    assertEquals("replacement", source.readText())
}
```

Add tests for directories, duplicate display names, unsupported paths, permanent delete, corrupt metadata, and empty Trash.

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.LocalTrashManagerTest`

Expected: Trash symbols do not exist.

- [ ] **Step 3: Implement per-volume entry directories and properties metadata**

```kotlin
data class TrashEntry(
    val id: String,
    val originalPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val deletedAt: Long,
    val entryDirectory: File,
    val payload: File,
)
```

Use `.VoyagerTrash/.pending-<uuid>` during the move, write metadata before moving the payload, rename the complete pending directory to `<timestamp>-<uuid>`, and restore the source if finalization fails.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.repository.LocalTrashManagerTest`

Expected: all Trash service tests pass.

- [ ] **Step 5: Integrate the Trash preference and deletion policy**

```kotlin
val useTrash = prefs.useTrash.stateIn(viewModelScope, SharingStarted.Eagerly, true)
val canTrashSelection: Boolean
    get() = browseState.value.source == FileSource.LOCAL && useTrash.value
```

Local delete moves each item to Trash when enabled. SAF and remote delete permanently only after explicit wording.

- [ ] **Step 6: Commit the Trash service and policy**

```bash
git add app/src/main/java/com/voyagerfiles/data/model/TrashEntry.kt app/src/main/java/com/voyagerfiles/data/repository/LocalTrashManager.kt app/src/test/java/com/voyagerfiles/data/repository/LocalTrashManagerTest.kt app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt
git commit -m "feat: add recoverable local trash"
```

### Task 7: Add the Trash screen and safe operation state

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/screens/TrashScreen.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/DeleteDialogModelTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `Screen.Trash`
- Produces: `TrashState(entries, selectedIds, isLoading, error)`
- Produces: `OperationState.Idle` and `OperationState.Running(label)`.

- [ ] **Step 1: Write failing delete-copy tests**

```kotlin
@Test fun localTrashDeleteExplainsRestore() {
    assertEquals("Move 2 items to Trash? You can restore them later.", DeleteDialogModel.localTrash(2, "").message)
}

@Test fun permanentDeleteExplainsIrreversibility() {
    assertTrue(DeleteDialogModel.permanent(1, "report.txt").message.contains("cannot be undone"))
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.DeleteDialogModelTest`

Expected: delete dialog model does not exist.

- [ ] **Step 3: Implement Trash navigation, selection, restore, permanent delete, and empty confirmation**

Use the existing list row, selection top bar, snackbar host, loading/error/empty patterns, and Material error colors. Home gains a Trash card and Settings gains a `Use Trash for local files` switch.

- [ ] **Step 4: Add a single operation state to disable overlapping actions**

```kotlin
sealed interface OperationState {
    data object Idle : OperationState
    data class Running(val label: String) : OperationState
}
```

Wrap paste, delete, restore, download, and empty Trash in one helper that returns to Idle in `finally`.

- [ ] **Step 5: Run focused and full unit tests**

Run: `./gradlew testDebugUnitTest`

Expected: all non-optional tests pass and only environment-gated SMB tests are skipped.

- [ ] **Step 6: Commit the Trash UI and operation state**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/TrashScreen.kt app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/test/java/com/voyagerfiles/ui/screens/DeleteDialogModelTest.kt
git commit -m "feat: add trash management screen"
```

### Task 8: Prevent stale directory results

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/viewmodel/DirectoryLoadGuard.kt`
- Create: `app/src/test/java/com/voyagerfiles/viewmodel/DirectoryLoadGuardTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `DirectoryLoadGuard.nextRequest(): Long` and `isCurrent(requestId: Long, sessionId: String?): Boolean`.

- [ ] **Step 1: Write a failing stale-request test**

```kotlin
@Test fun onlyLatestRequestForActiveSessionMayCommit() {
    val first = guard.nextRequest("local")
    val second = guard.nextRequest("local")
    assertFalse(guard.isCurrent(first, "local"))
    assertTrue(guard.isCurrent(second, "local"))
}
```

- [ ] **Step 2: Run focused test and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.DirectoryLoadGuardTest`

Expected: guard symbols do not exist.

- [ ] **Step 3: Implement request tokens and apply them around provider loads**

```kotlin
val requestId = loadGuard.nextRequest(sessionId)
val result = provider.listFiles(path)
if (!loadGuard.isCurrent(requestId, sessionId)) return
```

- [ ] **Step 4: Run focused and core suites and commit**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.viewmodel.*' --tests 'com.voyagerfiles.data.repository.*' --tests 'com.voyagerfiles.data.model.*'`

Expected: all focused tests pass.

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/DirectoryLoadGuard.kt app/src/test/java/com/voyagerfiles/viewmodel/DirectoryLoadGuardTest.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt
git commit -m "fix: ignore stale directory loads"
```
