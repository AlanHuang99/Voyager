# Daily Actions, Sharing, and View Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Voyager's daily selected-item actions discoverable and safe by adding local and SAF sharing, a per-operation Trash choice, primary rename, compact list mode, explicit view options, and a file Details sheet.

**Architecture:** Keep `BrowserScreen` and `FileBrowserViewModel` as the orchestration points, but place selection policy, share planning, delete copy, and file-detail presentation in focused testable models. Reuse the existing Material 3 components, `FileProvider` abstraction, `FileProvider` manifest entry, and DataStore view preference. Add no runtime dependency.

**Tech Stack:** Kotlin 2.1, Android intents and `FileProvider`, Jetpack Compose Material 3, DataStore Preferences, JUnit 4, AndroidX Compose instrumentation tests.

## Global Constraints

- Preserve the existing Compose, Material 3, ViewModel, provider, Room, DataStore, navigation, and theme conventions.
- Add no runtime dependency.
- Every behavior change begins with a focused failing test and finishes with that focused test green.
- Trash remains the safe default for direct local deletion when enabled.
- Permanent deletion always requires confirmation and states that it cannot be undone.
- Share only non-directory direct local or SAF items in this milestone.
- Keep the selection top bar to at most three primary actions plus overflow on a narrow phone.
- Keep every changed interactive target at least 48dp.
- Keep prose paragraphs on one physical line and do not add AI-authorship markers.

---

### Task 1: Define context-aware selection action placement

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt:117-274,843-879`
- Modify: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt`

**Interfaces:**
- Produces: `SelectionToolbarModel.forState(isRemote: Boolean, selectionCount: Int, canShare: Boolean): SelectionToolbarModel`
- Produces: `SelectionToolbarAction.SHARE` and `SelectionToolbarAction.DETAILS`
- Consumes later: Tasks 2, 3, and 5 render the actions defined here.

- [ ] **Step 1: Write failing action-placement tests**

```kotlin
@Test
fun shareableSingleSelectionPromotesShareRenameAndDelete() {
    val model = SelectionToolbarModel.forState(
        isRemote = false,
        selectionCount = 1,
        canShare = true,
    )

    assertEquals(
        listOf(
            SelectionToolbarAction.SHARE,
            SelectionToolbarAction.RENAME,
            SelectionToolbarAction.DELETE,
        ),
        model.primaryActions,
    )
    assertTrue(model.overflowActions.contains(SelectionToolbarAction.COPY))
    assertTrue(model.overflowActions.contains(SelectionToolbarAction.CUT))
    assertTrue(model.overflowActions.contains(SelectionToolbarAction.DETAILS))
}

@Test
fun multipleRemoteSelectionKeepsShareAndRenameHidden() {
    val model = SelectionToolbarModel.forState(
        isRemote = true,
        selectionCount = 2,
        canShare = false,
    )

    assertEquals(
        listOf(SelectionToolbarAction.COPY, SelectionToolbarAction.DELETE),
        model.primaryActions,
    )
    assertFalse(model.primaryActions.contains(SelectionToolbarAction.SHARE))
    assertFalse(model.overflowActions.contains(SelectionToolbarAction.RENAME))
    assertTrue(model.overflowActions.contains(SelectionToolbarAction.DOWNLOAD))
}
```

- [ ] **Step 2: Run the focused model test and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest`

Expected: compilation fails because the new signature and actions do not exist.

- [ ] **Step 3: Implement the minimal selection policy**

```kotlin
fun forState(
    isRemote: Boolean,
    selectionCount: Int,
    canShare: Boolean,
): SelectionToolbarModel {
    val isSingle = selectionCount == 1
    val primary = when {
        isSingle && canShare -> listOf(SelectionToolbarAction.SHARE, SelectionToolbarAction.RENAME, SelectionToolbarAction.DELETE)
        isSingle -> listOf(SelectionToolbarAction.COPY, SelectionToolbarAction.RENAME, SelectionToolbarAction.DELETE)
        canShare -> listOf(SelectionToolbarAction.COPY, SelectionToolbarAction.SHARE, SelectionToolbarAction.DELETE)
        else -> listOf(SelectionToolbarAction.COPY, SelectionToolbarAction.DELETE)
    }
    val overflow = buildList {
        if (SelectionToolbarAction.COPY !in primary) add(SelectionToolbarAction.COPY)
        add(SelectionToolbarAction.CUT)
        add(SelectionToolbarAction.SELECT_ALL)
        if (isRemote) add(SelectionToolbarAction.DOWNLOAD)
        if (isSingle) add(SelectionToolbarAction.DETAILS)
    }
    return SelectionToolbarModel(primary, overflow)
}
```

- [ ] **Step 4: Run the focused model test and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest`

Expected: all toolbar model tests pass and every primary-action list has no more than three entries.

- [ ] **Step 5: Render primary Rename and overflow Copy and Cut**

Call the new model with `canShare = false` until Task 2 supplies share eligibility. Render Rename in the primary bar when requested, and render Copy and Cut in overflow when the model moves them there.

```kotlin
val selectionToolbarModel = remember(isNetwork, state.selectedFiles.size) {
    SelectionToolbarModel.forState(
        isRemote = isNetwork,
        selectionCount = state.selectedFiles.size,
        canShare = false,
    )
}

if (SelectionToolbarAction.RENAME in selectionToolbarModel.primaryActions) {
    IconButton(
        onClick = { showRenameDialog = state.selectedFiles.single() },
        enabled = runningOperation == null,
    ) {
        Icon(Icons.Filled.DriveFileRenameOutline, "Rename")
    }
}

if (SelectionToolbarAction.COPY in selectionToolbarModel.overflowActions) {
    DropdownMenuItem(
        text = { Text("Copy") },
        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
        onClick = {
            viewModel.copyToClipboard(state.selectedFiles.toList())
            showSelectionMoreMenu = false
        },
    )
}
if (SelectionToolbarAction.CUT in selectionToolbarModel.overflowActions) {
    DropdownMenuItem(
        text = { Text("Cut") },
        leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
        onClick = {
            viewModel.cutToClipboard(state.selectedFiles.toList())
            showSelectionMoreMenu = false
        },
    )
}
```

- [ ] **Step 6: Commit the selection policy**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt
git commit -m "feat: prioritize daily selection actions"
```

### Task 2: Share eligible local and SAF files through Android

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/util/ShareIntentPlan.kt`
- Create: `app/src/test/java/com/voyagerfiles/util/ShareIntentPlanTest.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt:116-151`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt:117-274`

**Interfaces:**
- Produces: `enum class ShareIntentKind { SINGLE, MULTIPLE }`
- Produces: `data class ShareIntentPlan(val kind: ShareIntentKind, val mimeType: String)`
- Produces: `ShareIntentPlan.forFiles(files: List<FileItem>): ShareIntentPlan?`
- Produces: `FileUtils.createShareIntent(context: Context, files: List<FileItem>): Result<Intent>`
- Produces: `FileUtils.shareFiles(context: Context, files: List<FileItem>): Result<Unit>`
- Consumes: `SelectionToolbarAction.SHARE` from Task 1.

- [ ] **Step 1: Write failing pure share-plan tests**

```kotlin
@Test
fun plansSingleAndMultipleShares() {
    assertEquals(
        ShareIntentPlan(ShareIntentKind.SINGLE, "image/jpeg"),
        ShareIntentPlan.forFiles(listOf(localFile("one.jpg"))),
    )
    assertEquals(
        ShareIntentPlan(ShareIntentKind.MULTIPLE, "image/*"),
        ShareIntentPlan.forFiles(listOf(localFile("one.jpg"), localFile("two.png"))),
    )
}

@Test
fun rejectsDirectoriesAndRemoteFiles() {
    assertNull(ShareIntentPlan.forFiles(listOf(localDirectory("Folder"))))
    assertNull(ShareIntentPlan.forFiles(listOf(remoteFile("report.pdf"))))
}

@Test
fun unrelatedMimeFamiliesUseWildcard() {
    assertEquals(
        "*/*",
        ShareIntentPlan.forFiles(listOf(localFile("one.jpg"), localFile("notes.txt")))?.mimeType,
    )
}

private fun localFile(name: String) = FileItem(
    name = name,
    path = "/storage/emulated/0/$name",
    isDirectory = false,
    source = FileSource.LOCAL,
)

private fun localDirectory(name: String) = FileItem(
    name = name,
    path = "/storage/emulated/0/$name",
    isDirectory = true,
    source = FileSource.LOCAL,
)

private fun remoteFile(name: String) = FileItem(
    name = name,
    path = "/$name",
    isDirectory = false,
    source = FileSource.SFTP,
)
```

- [ ] **Step 2: Run the pure tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.ShareIntentPlanTest`

Expected: compilation fails because `ShareIntentPlan` does not exist.

- [ ] **Step 3: Implement the pure share plan**

```kotlin
data class ShareIntentPlan(
    val kind: ShareIntentKind,
    val mimeType: String,
) {
    companion object {
        fun forFiles(files: List<FileItem>): ShareIntentPlan? {
            if (files.isEmpty()) return null
            if (files.any { it.isDirectory || it.source !in setOf(FileSource.LOCAL, FileSource.SAF) }) return null
            val mimeTypes = files.map(FileItem::mimeType).distinct()
            val mimeType = when {
                mimeTypes.size == 1 -> mimeTypes.single()
                mimeTypes.map { it.substringBefore('/') }.distinct().size == 1 ->
                    "${mimeTypes.first().substringBefore('/')}/*"
                else -> "*/*"
            }
            return ShareIntentPlan(
                kind = if (files.size == 1) ShareIntentKind.SINGLE else ShareIntentKind.MULTIPLE,
                mimeType = mimeType,
            )
        }
    }
}
```

- [ ] **Step 4: Run the pure tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.ShareIntentPlanTest`

Expected: all share-plan tests pass.

- [ ] **Step 5: Write failing Android intent tests**

```kotlin
@Test
fun multipleLocalFilesBuildReadableSendMultipleIntent() {
    val files = listOf(createLocalFile("one.jpg"), createLocalFile("two.png"))
    val intent = FileUtils.createShareIntent(context, files).getOrThrow()

    assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
    assertEquals("image/*", intent.type)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    assertEquals(2, intent.clipData?.itemCount)
    assertEquals(2, intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.size)
}

@Test
fun safFileKeepsItsContentUri() {
    val uri = Uri.parse("content://documents/tree/root/document/report.pdf")
    val intent = FileUtils.createShareIntent(context, listOf(safFile(uri))).getOrThrow()

    assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
}

private fun createLocalFile(name: String): FileItem {
    val file = File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    return FileItem(
        name = file.name,
        path = file.absolutePath,
        isDirectory = false,
        source = FileSource.LOCAL,
    )
}

private fun safFile(uri: Uri) = FileItem(
    name = "report.pdf",
    path = uri.toString(),
    isDirectory = false,
    source = FileSource.SAF,
)
```

- [ ] **Step 6: Run the Android intent test and confirm RED**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.util.FileSharingTest`

Expected: compilation fails because `createShareIntent` does not exist.

- [ ] **Step 7: Build readable single and multiple share intents**

```kotlin
fun createShareIntent(context: Context, files: List<FileItem>): Result<Intent> = runCatching {
    val plan = requireNotNull(ShareIntentPlan.forFiles(files)) { "Select files stored on this device to share" }
    val uris = files.map { file ->
        if (file.source == FileSource.SAF) Uri.parse(file.path)
        else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path))
    }
    Intent(
        if (plan.kind == ShareIntentKind.SINGLE) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
        type = plan.mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        if (plan.kind == ShareIntentKind.SINGLE) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
}

fun shareFiles(context: Context, files: List<FileItem>): Result<Unit> =
    createShareIntent(context, files).mapCatching { intent ->
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
```

- [ ] **Step 8: Run JVM and Android share tests and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.ShareIntentPlanTest`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.util.FileSharingTest`

Expected: both focused suites pass.

- [ ] **Step 9: Wire Share into selection mode**

Build `selectedItems` from the current directory listing, derive `canShare` through `ShareIntentPlan.forFiles(selectedItems) != null`, render Share wherever the Task 1 model requests it, and clear selection only after `FileUtils.shareFiles` succeeds.

```kotlin
val selectedItems = remember(state.files, state.selectedFiles) {
    state.files.filter { it.path in state.selectedFiles }
}
val sharePlan = remember(selectedItems) { ShareIntentPlan.forFiles(selectedItems) }
val selectionToolbarModel = remember(isNetwork, selectedItems.size, sharePlan) {
    SelectionToolbarModel.forState(
        isRemote = isNetwork,
        selectionCount = selectedItems.size,
        canShare = sharePlan != null,
    )
}

fun shareSelected() {
    FileUtils.shareFiles(context, selectedItems).fold(
        onSuccess = viewModel::clearSelection,
        onFailure = {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Could not share the selected files. Check that access is still available and try again.",
                )
            }
        },
    )
}

if (SelectionToolbarAction.SHARE in selectionToolbarModel.primaryActions) {
    IconButton(onClick = ::shareSelected, enabled = runningOperation == null) {
        Icon(Icons.Filled.Share, "Share")
    }
}
```

- [ ] **Step 10: Commit sharing**

```bash
git add app/src/main/java/com/voyagerfiles/util/ShareIntentPlan.kt app/src/test/java/com/voyagerfiles/util/ShareIntentPlanTest.kt app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt app/src/main/java/com/voyagerfiles/util/FileUtils.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt
git commit -m "feat: share local and document-tree files"
```

### Task 3: Offer Trash and permanent delete per operation

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/viewmodel/DeleteMode.kt`
- Create: `app/src/main/java/com/voyagerfiles/ui/components/DeleteChoiceDialog.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/components/DeleteChoiceDialogModelTest.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt:356-392`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt:697-712`

**Interfaces:**
- Produces: `enum class DeleteMode { TRASH, PERMANENT }`
- Produces: `FileBrowserViewModel.deleteSelected(mode: DeleteMode? = null)`
- Produces: `DeleteChoiceDialogModel.local(count: Int, fileName: String): DeleteChoiceDialogModel`
- Produces: `DeleteChoiceDialog(model, onDismiss, onMoveToTrash, onDeletePermanently)`

- [ ] **Step 1: Write failing local-choice copy tests**

```kotlin
@Test
fun singleItemNamesBothOutcomesAndIrreversibility() {
    val model = DeleteChoiceDialogModel.local(1, "notes.txt")

    assertEquals("Delete \"notes.txt\"?", model.title)
    assertTrue(model.message.contains("restore"))
    assertTrue(model.message.contains("cannot be undone"))
    assertEquals("Move to Trash", model.trashLabel)
    assertEquals("Delete permanently", model.permanentLabel)
}

@Test
fun multipleItemsUseTheSelectionCount() {
    assertEquals("Delete 3 items?", DeleteChoiceDialogModel.local(3, "").title)
}
```

- [ ] **Step 2: Run the dialog-model test and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.DeleteChoiceDialogModelTest`

Expected: compilation fails because the choice model does not exist.

- [ ] **Step 3: Implement the model and Material 3 dialog**

```kotlin
data class DeleteChoiceDialogModel(
    val title: String,
    val message: String,
    val trashLabel: String = "Move to Trash",
    val permanentLabel: String = "Delete permanently",
) {
    companion object {
        fun local(count: Int, fileName: String) = DeleteChoiceDialogModel(
            title = if (count == 1) "Delete \"$fileName\"?" else "Delete $count items?",
            message = "Move the selection to Trash so it can be restored later, or delete it permanently. Permanent deletion cannot be undone.",
        )
    }
}
```

Render Cancel, error-colored Delete permanently, and primary Move to Trash buttons in `DeleteChoiceDialog`. Keep the existing `DeleteConfirmDialog` for permanent-only sources and Trash-screen permanent deletion.

```kotlin
@Composable
fun DeleteChoiceDialog(
    model: DeleteChoiceDialogModel,
    onDismiss: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.title) },
        text = { Text(model.message) },
        confirmButton = {
            TextButton(onClick = onMoveToTrash) { Text(model.trashLabel) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onDeletePermanently) {
                    Text(model.permanentLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
```

- [ ] **Step 4: Run the dialog-model test and confirm GREEN**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.DeleteChoiceDialogModelTest`

Expected: all delete-choice copy tests pass.

- [ ] **Step 5: Accept an explicit delete mode in the ViewModel**

```kotlin
fun deleteSelected(mode: DeleteMode? = null) {
    val state = _browseState.value
    val selectedPaths = state.selectedFiles.toList()
    if (selectedPaths.isEmpty()) return
    val resolvedMode = mode ?: if (state.source == FileSource.LOCAL && useTrash.value) {
        DeleteMode.TRASH
    } else {
        DeleteMode.PERMANENT
    }
    if (resolvedMode == DeleteMode.TRASH && state.source != FileSource.LOCAL) {
        showSnackbar("Trash is available only for direct local storage")
        return
    }
    val moveToTrash = resolvedMode == DeleteMode.TRASH
    val provider = fileProvider
    launchOperation(if (moveToTrash) "Moving to Trash" else "Deleting") {
        val count = selectedPaths.size
        var failed = 0
        var firstError: Throwable? = null
        for (path in selectedPaths) {
            val result = if (moveToTrash) trashManager.moveToTrash(path).map { Unit } else provider.delete(path)
            result.onFailure { error ->
                failed++
                if (firstError == null) firstError = error
            }
        }
        clearSelection()
        refreshFiles()
        if (failed > 0) {
            showSnackbar(
                OperationMessages.partial(
                    failed = failed,
                    total = count,
                    action = if (moveToTrash) "moved to Trash" else "deleted",
                    error = checkNotNull(firstError),
                ),
            )
        } else {
            val action = if (moveToTrash) "moved to Trash" else "permanently deleted"
            showSnackbar("$count item${if (count > 1) "s" else ""} $action")
        }
    }
}
```

- [ ] **Step 6: Wire the local choice without mutating the saved preference**

When `state.source == FileSource.LOCAL && useTrash`, show `DeleteChoiceDialog`. Call `deleteSelected(DeleteMode.TRASH)` or `deleteSelected(DeleteMode.PERMANENT)` from the matching button. For all other states, keep the existing permanent-only dialog and call `deleteSelected(DeleteMode.PERMANENT)`.

```kotlin
if (showDeleteDialog) {
    val count = state.selectedFiles.size
    val fileName = state.selectedFiles.firstOrNull()?.substringAfterLast("/").orEmpty()
    if (state.source == FileSource.LOCAL && useTrash) {
        DeleteChoiceDialog(
            model = DeleteChoiceDialogModel.local(count, fileName),
            onDismiss = { showDeleteDialog = false },
            onMoveToTrash = {
                showDeleteDialog = false
                viewModel.deleteSelected(DeleteMode.TRASH)
            },
            onDeletePermanently = {
                showDeleteDialog = false
                viewModel.deleteSelected(DeleteMode.PERMANENT)
            },
        )
    } else {
        DeleteConfirmDialog(
            model = DeleteDialogModel.permanent(count, fileName),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteSelected(DeleteMode.PERMANENT)
            },
        )
    }
}
```

- [ ] **Step 7: Add a Compose regression to the existing browser-selection test fixture**

Select a disposable local file, click Delete, and assert that `Move to Trash`, `Delete permanently`, and `cannot be undone` are all visible. Dismiss the dialog without deleting the fixture.

```kotlin
composeTestRule.onNodeWithText("notes.txt").performTouchInput { longClick() }
composeTestRule.onNodeWithContentDescription("Delete").performClick()
composeTestRule.onNodeWithText("Move to Trash").assertIsDisplayed()
composeTestRule.onNodeWithText("Delete permanently").assertIsDisplayed()
composeTestRule.onNodeWithText("Permanent deletion cannot be undone.", substring = true).assertIsDisplayed()
composeTestRule.onNodeWithText("Cancel").performClick()
```

- [ ] **Step 8: Run focused JVM and device tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.DeleteChoiceDialogModelTest`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.screens.BrowserSelectionActionsTest`

Expected: copy and Compose delete-choice assertions pass.

- [ ] **Step 9: Commit the per-operation delete choice**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/DeleteMode.kt app/src/main/java/com/voyagerfiles/ui/components/DeleteChoiceDialog.kt app/src/test/java/com/voyagerfiles/ui/components/DeleteChoiceDialogModelTest.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "feat: choose Trash or permanent delete"
```

### Task 4: Add persistent compact list and explicit view options

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/model/FileItem.kt:147-150`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileListItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt:280-346,580-619,827-841`
- Modify: `app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`

**Interfaces:**
- Produces: `ViewMode.COMPACT`
- Produces: `ViewMode.label: String` with `List`, `Compact list`, and `Grid`
- Produces: `FileListItem(..., compact: Boolean = false)`
- Replaces: `BrowserToolbarAction.TOGGLE_VIEW` with `BrowserToolbarAction.VIEW_OPTIONS`

- [ ] **Step 1: Write a failing view-label test**

```kotlin
@Test
fun viewModesHaveUserFacingLabels() {
    assertEquals("List", ViewMode.LIST.label)
    assertEquals("Compact list", ViewMode.COMPACT.label)
    assertEquals("Grid", ViewMode.GRID.label)
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest`

Expected: compilation fails because Compact and label do not exist.

- [ ] **Step 3: Add the compact enum value and labels**

```kotlin
enum class ViewMode(val label: String) {
    LIST("List"),
    COMPACT("Compact list"),
    GRID("Grid"),
}
```

The existing DataStore parser already persists enum names and falls back to List on unknown values.

- [ ] **Step 4: Render compact rows through the existing list component**

Add `compact: Boolean = false` to `FileListItem`. For compact rows use 8dp horizontal padding, 4dp vertical padding, a 32dp icon, 12dp icon-to-text spacing, and `Modifier.heightIn(min = 48.dp)`. Keep both the name and metadata and retain the existing comfortable values when `compact` is false.

```kotlin
val horizontalPadding = if (compact) 8.dp else 16.dp
val verticalPadding = if (compact) 4.dp else 12.dp
val iconSize = if (compact) 32.dp else 40.dp
val iconSpacing = if (compact) 12.dp else 16.dp
val nameStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge

Row(
    modifier = Modifier
        .heightIn(min = 48.dp)
        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    verticalAlignment = Alignment.CenterVertically,
) {
    FileThumbnailOrIcon(file = file, iconSize = iconSize)
    Spacer(modifier = Modifier.width(iconSpacing))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(
            text = file.name,
            style = nameStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!file.isDirectory) {
                Text(
                    text = file.formattedSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatDate(file.lastModified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Keep the exact padding, icon size, text style, metadata, and 48dp minimum above.

- [ ] **Step 5: Replace the binary toggle with a labeled view menu**

Open a `DropdownMenu` from the existing toolbar slot. Show List, Compact list, and Grid with matching Material icons and a trailing check on the active mode. Use `View options, current ${state.viewMode.label}` as the icon content description. Selecting an entry calls `viewModel.setViewMode(mode)` and closes the menu.

```kotlin
Box {
    IconButton(onClick = { showViewMenu = true }) {
        Icon(
            imageVector = when (state.viewMode) {
                ViewMode.GRID -> Icons.Filled.GridView
                ViewMode.LIST, ViewMode.COMPACT -> Icons.AutoMirrored.Filled.ViewList
            },
            contentDescription = "View options, current ${state.viewMode.label}",
        )
    }
    DropdownMenu(
        expanded = showViewMenu,
        onDismissRequest = { showViewMenu = false },
    ) {
        ViewMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(mode.label) },
                trailingIcon = {
                    if (state.viewMode == mode) Icon(Icons.Filled.Check, null)
                },
                onClick = {
                    viewModel.setViewMode(mode)
                    showViewMenu = false
                },
            )
        }
    }
}
```

- [ ] **Step 6: Render List and Compact through `LazyColumn`**

```kotlin
state.viewMode == ViewMode.LIST || state.viewMode == ViewMode.COMPACT -> {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.visibleFiles, key = { it.path }) { file ->
            FileListItem(
                file = file,
                compact = state.viewMode == ViewMode.COMPACT,
                isSelected = file.path in state.selectedFiles,
                isSelectionMode = isSelectionMode,
                onClick = {
                    if (isSelectionMode) {
                        viewModel.toggleSelection(file.path)
                    } else if (file.isDirectory) {
                        navigateTo(file.path)
                    } else if (isNetwork) {
                        viewModel.downloadFile(file.path)
                    } else if (state.source == FileSource.LOCAL || state.source == FileSource.SAF) {
                        runCatching { FileUtils.openFile(context, file) }.onFailure {
                            scope.launch {
                                snackbarHostState.showSnackbar("No app found to open this file")
                            }
                        }
                    }
                },
                onLongClick = { viewModel.toggleSelection(file.path) },
            )
        }
    }
}
```

- [ ] **Step 7: Add a Compose view-options regression**

Open View options, assert all three labels, choose Compact list, and assert that the icon label changes to `View options, current Compact list`. Recreate the browser content with the same ViewModel and assert Compact remains selected.

```kotlin
composeTestRule.onNodeWithContentDescription("View options, current List").performClick()
composeTestRule.onNodeWithText("List").assertIsDisplayed()
composeTestRule.onNodeWithText("Compact list").assertIsDisplayed().performClick()
composeTestRule.onNodeWithContentDescription("View options, current Compact list").assertIsDisplayed()

composeTestRule.activityRule.scenario.recreate()
composeTestRule.waitUntil(timeoutMillis = 10_000) {
    composeTestRule.onAllNodesWithContentDescription("View options, current Compact list")
        .fetchSemanticsNodes().isNotEmpty()
}
```

- [ ] **Step 8: Run focused JVM and device tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.screens.BrowserSelectionActionsTest`

Expected: view labels, menu behavior, and compact persistence assertions pass.

- [ ] **Step 9: Commit view options**

```bash
git add app/src/main/java/com/voyagerfiles/data/model/FileItem.kt app/src/main/java/com/voyagerfiles/ui/components/FileListItem.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/test/java/com/voyagerfiles/ui/screens/BrowserToolbarModelTest.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "feat: add compact list view options"
```

### Task 5: Add a focused file Details sheet

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/components/FileDetailsSheet.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/components/FileDetailsSheetTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt:117-274,714-726`

**Interfaces:**
- Produces: `FileDetailsSheet(file: FileItem, onDismiss: () -> Unit)`
- Consumes: `SelectionToolbarAction.DETAILS` from Task 1.

- [ ] **Step 1: Write a failing Compose details test**

```kotlin
@Test
fun detailsShowsAvailableMetadata() {
    composeTestRule.setContent {
        VoyagerTheme {
            FileDetailsSheet(
                file = FileItem(
                    name = "report.pdf",
                    path = "/storage/emulated/0/Documents/report.pdf",
                    isDirectory = false,
                    size = 2048,
                    owner = "media_rw",
                    permissions = "rw-r--r--",
                ),
                onDismiss = {},
            )
        }
    }

    composeTestRule.onNodeWithText("Details").assertIsDisplayed()
    composeTestRule.onNodeWithText("report.pdf").assertIsDisplayed()
    composeTestRule.onNodeWithText("2 KB").assertIsDisplayed()
    composeTestRule.onNodeWithText("/storage/emulated/0/Documents/report.pdf").assertIsDisplayed()
    composeTestRule.onNodeWithText("media_rw").assertIsDisplayed()
    composeTestRule.onNodeWithText("rw-r--r--").assertIsDisplayed()
}
```

- [ ] **Step 2: Run the focused Compose test and confirm RED**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.components.FileDetailsSheetTest`

Expected: compilation fails because `FileDetailsSheet` does not exist.

- [ ] **Step 3: Implement the read-only Material sheet**

Use `ModalBottomSheet`, a `LazyColumn`, and small label-value rows for Name, Type, Size for files, Modified, Source, Path, Owner when present, and Permissions when present. Use Android `DateFormat.getDateTimeInstance` for the modified value. Make the path selectable and allow long values to wrap. Do not calculate recursive folder size.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(file: FileItem, onDismiss: () -> Unit) {
    val modified = remember(file.lastModified) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(file.lastModified)
    }
    val rows = buildList {
        add("Name" to file.name)
        add("Type" to if (file.isDirectory) "Folder" else file.mimeType)
        if (!file.isDirectory) add("Size" to file.formattedSize)
        add("Modified" to modified)
        add("Source" to file.source.displayLabel)
        add("Path" to file.path)
        file.owner?.takeIf(String::isNotBlank)?.let { add("Owner" to it) }
        file.permissions?.takeIf(String::isNotBlank)?.let { add("Permissions" to it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text("Details", style = MaterialTheme.typography.headlineSmall) }
            items(rows) { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private val FileSource.displayLabel: String
    get() = when (this) {
        FileSource.LOCAL -> "Local storage"
        FileSource.SAF -> "Document tree"
        FileSource.SFTP -> "SFTP"
        FileSource.FTP -> "FTP"
        FileSource.SMB -> "SMB"
        FileSource.WEBDAV -> "WebDAV"
    }
```

- [ ] **Step 4: Run the focused Compose test and confirm GREEN**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.components.FileDetailsSheetTest`

Expected: all available labels and values are visible.

- [ ] **Step 5: Wire Details into the single-selection overflow**

Store `showDetailsFor: FileItem?` with `remember`, assign the single selected item when Details is chosen, close the overflow menu, and render `FileDetailsSheet`. Dismissing the sheet keeps the item selected so the user can immediately take another action.

```kotlin
var showDetailsFor by remember { mutableStateOf<FileItem?>(null) }

if (SelectionToolbarAction.DETAILS in selectionToolbarModel.overflowActions) {
    DropdownMenuItem(
        text = { Text("Details") },
        leadingIcon = { Icon(Icons.Filled.Info, null) },
        onClick = {
            showDetailsFor = selectedItems.single()
            showSelectionMoreMenu = false
        },
    )
}

showDetailsFor?.let { file ->
    FileDetailsSheet(
        file = file,
        onDismiss = { showDetailsFor = null },
    )
}
```

- [ ] **Step 6: Commit Details**

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/FileDetailsSheet.kt app/src/androidTest/java/com/voyagerfiles/ui/components/FileDetailsSheetTest.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt
git commit -m "feat: show selected file details"
```

### Task 6: Final accessibility, documentation, device, and regression verification

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`
- Modify: `fastlane/metadata/android/en-US/full_description.txt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`
- Refresh only if visually representative: `docs/screenshots/browser.png`

**Interfaces:**
- Verifies every interface from Tasks 1 through 5.

- [ ] **Step 1: Add the end-to-end selection-action Compose regression**

Using a temporary local directory containing one text file and one folder, verify:

- One selected file exposes Share, Rename, and Delete as primary action descriptions.
- One selected folder exposes Copy, Rename, and Delete, with Share absent.
- Two selected files expose Copy, Share, and Delete, with Rename absent.
- Details appears only for exactly one selection.
- The local delete dialog contains both outcomes and irreversible wording.

Use these concrete assertions after the test fixture has opened the temporary directory:

```kotlin
composeTestRule.onNodeWithText("notes.txt").performTouchInput { longClick() }
composeTestRule.onNodeWithContentDescription("Share").assertIsDisplayed()
composeTestRule.onNodeWithContentDescription("Rename").assertIsDisplayed()
composeTestRule.onNodeWithContentDescription("Delete").assertIsDisplayed()

composeTestRule.onNodeWithContentDescription("More selection actions").performClick()
composeTestRule.onNodeWithText("Details").assertIsDisplayed()
composeTestRule.onNodeWithText("Cut").assertIsDisplayed()
composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
composeTestRule.onNodeWithText("Select all visible").performClick()

composeTestRule.onNodeWithContentDescription("Rename").assertDoesNotExist()
composeTestRule.onNodeWithContentDescription("Share").assertDoesNotExist()
composeTestRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
```

- [ ] **Step 2: Run all focused changed-area tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.BrowserToolbarModelTest --tests com.voyagerfiles.util.ShareIntentPlanTest --tests com.voyagerfiles.ui.components.DeleteChoiceDialogModelTest`

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.util.FileSharingTest --tests com.voyagerfiles.ui.components.FileDetailsSheetTest --tests com.voyagerfiles.ui.screens.BrowserSelectionActionsTest`

Expected: every focused test passes.

- [ ] **Step 3: Update capability documentation**

Update public wording only for implemented behavior: local and SAF single or multi-file sharing, per-operation Trash or permanent delete, primary single-item rename, List/Compact/Grid modes, and Details. Record remote temporary-file sharing, directory archive sharing, batch rename, and recursive folder size as remaining work only in internal architecture or testing notes, not as shipped claims.

- [ ] **Step 4: Build and install the current debug APK**

Run: `./gradlew assembleDebug`

Run: `adb -s 192.168.27.167:5555 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

Expected: build and install succeed for `com.voyagerfiles.debug`.

- [ ] **Step 5: Exercise disposable local fixtures on the K60**

Create a dedicated `/sdcard/VoyagerDailyActionsTest` folder and verify:

- Share one local file and two mixed-type files; the Android chooser appears and receiving targets can read each URI.
- Share one SAF file when a persisted document-tree grant is available.
- Rename a file and confirm the directory refreshes with the new name.
- Move one file to Trash, restore it, then permanently delete a separate disposable file from the browser choice dialog.
- Switch List to Compact list to Grid, relaunch the app, and confirm the last mode persists.
- Open Details for a file and a folder, rotate portrait to landscape and back, and confirm layout and selection recovery.
- Capture fresh before and after screenshots at the same orientation for visual comparison.
- Inspect `adb logcat -d -b crash` and the app process log for new fatal exceptions.

- [ ] **Step 6: Run the clean full verification gate**

Run: `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease connectedDebugAndroidTest --stacktrace`

Expected: all JVM and device tests pass, lint has zero errors, and debug and release APKs are produced.

- [ ] **Step 7: Review warnings, artifacts, and the final diff**

Inspect the lint XML by warning ID, aggregate test XML counts, run `git diff --check`, confirm no fixture or audit file is tracked, confirm version 1.3.0 remains unchanged during feature work, and review every changed production file for error paths, selection state, and accessibility labels.

- [ ] **Step 8: Remove device fixtures and restore user state**

Remove `/sdcard/VoyagerDailyActionsTest`, restore the device's original rotation settings, leave no instrumentation dialogs open, and confirm `adb shell dumpsys window` shows no Voyager crash dialog.

- [ ] **Step 9: Commit documentation and final regression coverage**

```bash
git add README.md docs/ARCHITECTURE.md docs/TESTING.md fastlane/metadata/android/en-US/full_description.txt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt docs/screenshots/browser.png
git commit -m "docs: document daily file actions"
```

- [ ] **Step 10: Publish only after the milestone is release quality**

Push `codex/daily-file-actions`, open a ready pull request with exact verification evidence, wait for GitHub CI, merge with a merge commit, and re-run the smallest post-merge verification on `master`. Prepare a separate semantic version bump and signed release only if the complete milestone, store metadata, and release notes are coherent and the user-authorized publication checks all pass.
