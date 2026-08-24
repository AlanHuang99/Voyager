# TV Navigation and APK Thumbnail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Android TV predictable initial file focus and held-select behavior, and show embedded application icons for readable local APK files.

**Architecture:** A pure Kotlin select-key reducer owns D-pad press/repeat/release decisions and a shared Compose modifier applies it to list and grid items. Browser composition detects television UI mode, requests focus for the first visible item after loading, and keeps search reachable by moving upward. APK artwork is parsed off the main thread through `PackageManager`, cached by file identity, and falls back to the existing generic icon.

**Tech Stack:** Kotlin, Jetpack Compose focus and key input, Android PackageManager, coroutines, JUnit 4, Compose instrumentation, ADB.

**Spec:** `docs/superpowers/specs/2026-08-24-github-issues-52-58-design.md`

## Global Constraints

- A held select key invokes the long-click action exactly once and never invokes the ordinary click on release.
- Enter, numpad Enter, and D-pad center share the same reducer; directional and pointer input remain unchanged.
- Initial focus is forced only in TV mode, only after visible files load, and only onto the first visible file.
- Search remains keyboard/D-pad reachable by navigating upward.
- APK package code is never executed.
- Only readable local filesystem APKs are parsed; SAF and network APKs use the generic Android icon.
- Cache identity includes canonical path, byte size, and last-modified time.

---

### Task 1: Pure remote-select state machine

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandler.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandlerTest.kt`

**Interfaces:**
- Produces: `RemoteSelectAction`, `RemoteSelectState`, and `reduceRemoteSelect(state, event): RemoteSelectResult`.
- Consumes later: Compose `Modifier.remoteSelectActions(onClick, onLongClick)`.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
class RemoteSelectKeyHandlerTest {
    @Test fun shortPressClicksOnRelease() {
        val down = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Down(repeatCount = 0))
        val up = reduceRemoteSelect(down.state, RemoteSelectEvent.Up)
        assertEquals(RemoteSelectAction.Click, up.action)
    }

    @Test fun repeatedDownLongClicksExactlyOnceAndSuppressesReleaseClick() {
        val down = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Down(0))
        val repeated = reduceRemoteSelect(down.state, RemoteSelectEvent.Down(1))
        val duplicate = reduceRemoteSelect(repeated.state, RemoteSelectEvent.Down(2))
        val up = reduceRemoteSelect(duplicate.state, RemoteSelectEvent.Up)
        assertEquals(RemoteSelectAction.LongClick, repeated.action)
        assertNull(duplicate.action)
        assertNull(up.action)
        assertEquals(RemoteSelectState.Idle, up.state)
    }

    @Test fun cancellationClearsAStartedPressWithoutAction() {
        val down = reduceRemoteSelect(RemoteSelectState.Idle, RemoteSelectEvent.Down(0))
        val cancelled = reduceRemoteSelect(down.state, RemoteSelectEvent.Cancel)
        assertNull(cancelled.action)
        assertEquals(RemoteSelectState.Idle, cancelled.state)
    }
}
```

- [ ] **Step 2: Run the test and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest --stacktrace`

Expected: compilation FAIL because the reducer types do not exist.

- [ ] **Step 3: Implement the minimal reducer**

```kotlin
internal enum class RemoteSelectState { Idle, Pressed, LongPressed }
internal sealed interface RemoteSelectEvent {
    data class Down(val repeatCount: Int) : RemoteSelectEvent
    data object Up : RemoteSelectEvent
    data object Cancel : RemoteSelectEvent
}
internal enum class RemoteSelectAction { Click, LongClick }
internal data class RemoteSelectResult(val state: RemoteSelectState, val action: RemoteSelectAction? = null)

internal fun reduceRemoteSelect(state: RemoteSelectState, event: RemoteSelectEvent): RemoteSelectResult = when (event) {
    is RemoteSelectEvent.Down -> when {
        state == RemoteSelectState.Idle -> RemoteSelectResult(RemoteSelectState.Pressed)
        state == RemoteSelectState.Pressed && event.repeatCount > 0 -> RemoteSelectResult(RemoteSelectState.LongPressed, RemoteSelectAction.LongClick)
        else -> RemoteSelectResult(state)
    }
    RemoteSelectEvent.Up -> RemoteSelectResult(
        state = RemoteSelectState.Idle,
        action = if (state == RemoteSelectState.Pressed) RemoteSelectAction.Click else null,
    )
    RemoteSelectEvent.Cancel -> RemoteSelectResult(RemoteSelectState.Idle)
}
```

- [ ] **Step 4: Run reducer tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandler.kt app/src/test/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandlerTest.kt
git commit -m "feat: classify remote select key presses"
```

### Task 2: Shared Compose key modifier

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandler.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileListItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileGridItem.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandlerTest.kt`

**Interfaces:**
- Consumes: reducer from Task 1.
- Produces: `Modifier.remoteSelectActions(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit)`.

- [ ] **Step 1: Write failing Compose tests for list and grid**

Use `createAndroidComposeRule<ComponentActivity>()`. Render each item with `Modifier.testTag("target").focusRequester(requester).focusable()`, request focus, then use `performKeyInput` to send `KeyDown` and `KeyUp` for `Key.DirectionCenter`. Assert a short sequence records `listOf("click")`. For the repeat path, use `InstrumentationRegistry.getInstrumentation().sendKeySync(android.view.KeyEvent(0L, 10L, ACTION_DOWN, KEYCODE_DPAD_CENTER, 1))`, then send the matching `ACTION_UP`; assert `listOf("long")`. Repeat against `FileGridItem`.

```kotlin
composeTestRule.onNodeWithTag("target").performKeyInput {
    keyDown(Key.DirectionCenter)
    keyUp(Key.DirectionCenter)
}
```

- [ ] **Step 2: Run the device test and verify the red state**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest --stacktrace`

Expected: FAIL because rows do not yet consume remote select key input.

- [ ] **Step 3: Implement the shared modifier and attach it before `combinedClickable`**

Use `onPreviewKeyEvent` for the three select key codes. Convert native repeat count into `RemoteSelectEvent.Down`, convert key-up into `Up`, call exactly one callback for the reducer action, and return `true` only for recognized select-key events when enabled. Store reducer state with `remember` and reset it in `onFocusChanged` when focus is lost.

```kotlin
internal fun Modifier.remoteSelectActions(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    var state by remember { mutableStateOf(RemoteSelectState.Idle) }
    onFocusChanged { if (!it.isFocused) state = RemoteSelectState.Idle }
        .onPreviewKeyEvent { event ->
            if (!enabled || event.key !in remoteSelectKeys) return@onPreviewKeyEvent false
            val input = when (event.type) {
                KeyEventType.KeyDown -> RemoteSelectEvent.Down(event.nativeKeyEvent.repeatCount)
                KeyEventType.KeyUp -> RemoteSelectEvent.Up
                else -> RemoteSelectEvent.Cancel
            }
            reduceRemoteSelect(state, input).also { result ->
                state = result.state
                when (result.action) {
                    RemoteSelectAction.Click -> onClick()
                    RemoteSelectAction.LongClick -> onLongClick()
                    null -> Unit
                }
            }
            true
        }
}
```

- [ ] **Step 4: Run reducer and Compose key tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest --stacktrace`

Expected: PASS for list and grid, with exactly one recorded callback per gesture.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandler.kt app/src/main/java/com/voyagerfiles/ui/components/FileListItem.kt app/src/main/java/com/voyagerfiles/ui/components/FileGridItem.kt app/src/androidTest/java/com/voyagerfiles/ui/components/RemoteSelectKeyHandlerTest.kt
git commit -m "feat: handle remote select keys in file items"
```

### Task 3: TV initial focus

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserTvFocusTest.kt`

**Interfaces:**
- Produces: `BrowserScreen(viewModel, onNavigateBack, isTelevision)` with a production default derived from `LocalConfiguration`.
- Consumes: `state.visibleFiles` and stable path keys.

- [ ] **Step 1: Write the failing forced-TV focus test**

Launch a local browser fixture containing `first.txt` and `second.txt`, call `BrowserScreen(..., isTelevision = true)`, wait for loading to finish, and assert the clickable node containing `first.txt` is focused while the search field is not focused. Repeat after changing the ViewModel to grid mode.

```kotlin
composeTestRule.onNode(hasText("first.txt") and hasClickAction()).assertIsFocused()
composeTestRule.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).assertIsNotFocused()
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserTvFocusTest --stacktrace`

Expected: compilation FAIL because the forced-TV parameter and search test tag do not exist.

- [ ] **Step 3: Implement TV detection and first-item focus**

```kotlin
@Composable
fun BrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: () -> Unit,
    isTelevision: Boolean = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION,
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val firstVisiblePath = state.visibleFiles.firstOrNull()?.path
    LaunchedEffect(isTelevision, state.isLoading, firstVisiblePath, state.viewMode) {
        if (isTelevision && !state.isLoading && firstVisiblePath != null) {
            firstItemFocusRequester.requestFocus()
        }
    }
```

Pass `Modifier.focusRequester(firstItemFocusRequester).focusable()` only to the first visible row/card in either layout. Add `Modifier.testTag(BROWSER_SEARCH_TEST_TAG)` to `BrowserSearchField`. Do not remove the search field from focus traversal.

- [ ] **Step 4: Run TV and existing navigation instrumentation**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserTvFocusTest,com.voyagerfiles.ui.screens.BrowserBackNavigationTest --stacktrace`

Expected: PASS in forced list and grid modes; existing phone navigation tests remain green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserTvFocusTest.kt
git commit -m "feat: focus the first file on Android TV"
```

### Task 4: APK archive icon loader

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/components/ApkThumbnailLoader.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/components/ApkThumbnailLoaderTest.kt`

**Interfaces:**
- Produces: `ApkThumbnailLoader.load(context, file, maxWidth, maxHeight): Result<Bitmap>` and `clear()`.
- Consumes: local `FileItem`, Android `PackageManager`, and drawable-to-bitmap conversion.

- [ ] **Step 1: Write failing loader tests**

Copy the instrumentation package's own installed APK from `context.applicationInfo.sourceDir` into `context.cacheDir/fixture.apk`, create a local `FileItem`, and assert `load(...).getOrThrow()` returns nonzero dimensions bounded by 96 by 96. Also assert a corrupt `.apk`, `FileSource.SAF`, and `FileSource.WEBDAV` each return failure.

```kotlin
val fixture = context.cacheDir.resolve("apk-icon-fixture.apk")
File(context.applicationInfo.sourceDir).copyTo(fixture, overwrite = true)
val bitmap = ApkThumbnailLoader.load(context, localItem(fixture), 96, 96).getOrThrow()
assertTrue(bitmap.width in 1..96)
assertTrue(bitmap.height in 1..96)
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.ApkThumbnailLoaderTest --stacktrace`

Expected: compilation FAIL because `ApkThumbnailLoader` does not exist.

- [ ] **Step 3: Implement safe parsing and bounded caching**

Validate `file.source == LOCAL`, `file.isApk`, `!file.isDirectory`, and a readable regular file. Use `packageManager.getPackageArchiveInfo(path, 0)`, assign both `applicationInfo.sourceDir` and `applicationInfo.publicSourceDir`, call `applicationInfo.loadIcon(packageManager)`, draw the drawable into an ARGB bitmap bounded by requested size, and cache by:

```kotlin
private data class CacheKey(
    val canonicalPath: String,
    val byteSize: Long,
    val lastModified: Long,
    val maxWidth: Int,
    val maxHeight: Int,
)
```

Use a synchronized access-order `LinkedHashMap` capped at 48 entries. Return `Result.failure` for every validation or parsing failure and never execute package components.

- [ ] **Step 4: Run loader tests**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.ApkThumbnailLoaderTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/ApkThumbnailLoader.kt app/src/androidTest/java/com/voyagerfiles/ui/components/ApkThumbnailLoaderTest.kt
git commit -m "feat: load icons from local APK archives"
```

### Task 5: APK thumbnails in file layouts

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileThumbnail.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/components/FileThumbnailTest.kt`

**Interfaces:**
- Consumes: `ApkThumbnailLoader.load`.
- Produces: `APK_THUMBNAIL_TEST_TAG` and generic fallback through `FILE_ICON_TEST_TAG`.

- [ ] **Step 1: Write failing rendering tests**

Render the copied valid APK through `FileThumbnailOrIcon` and wait for `APK_THUMBNAIL_TEST_TAG`. Render corrupt local, SAF, and WebDAV APKs and assert `FILE_ICON_TEST_TAG` exists while the APK thumbnail tag does not.

- [ ] **Step 2: Run and verify the red state**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.FileThumbnailTest --stacktrace`

Expected: FAIL because valid local APKs still render the generic Android vector.

- [ ] **Step 3: Add the APK branch before the generic icon branch**

Use `produceState` with keys `file.source`, `file.path`, `file.size`, `file.lastModified`, and pixel dimensions. Call `ApkThumbnailLoader.load` inside `withContext(Dispatchers.IO)`, render the returned bitmap with `ContentScale.Fit`, and preserve `fileIcon(file)` as the immediate and failure fallback.

```kotlin
if (file.isApk && file.source == FileSource.LOCAL) {
    val thumbnail by produceState<Bitmap?>(null, file.path, file.size, file.lastModified, sizePixels) {
        value = withContext(Dispatchers.IO) {
            ApkThumbnailLoader.load(context, file, sizePixels, sizePixels).getOrNull()
        }
    }
    Surface(modifier = modifier.size(iconSize), shape = MaterialTheme.shapes.small) {
        if (thumbnail != null) {
            Image(
                bitmap = checkNotNull(thumbnail).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag(APK_THUMBNAIL_TEST_TAG),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(8.dp).testTag(FILE_ICON_TEST_TAG),
            )
        }
    }
}
```

- [ ] **Step 4: Run thumbnail and selection layout tests**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.components.ApkThumbnailLoaderTest,com.voyagerfiles.ui.components.FileThumbnailTest,com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: PASS in both list and grid render paths.

- [ ] **Step 5: Document and commit**

Add APK embedded-icon and Android TV D-pad cases to `docs/TESTING.md`, then run `git diff --check` and commit:

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/FileThumbnail.kt app/src/androidTest/java/com/voyagerfiles/ui/components/FileThumbnailTest.kt docs/TESTING.md
git commit -m "feat: show application icons for local APK files"
```

### Task 6: K60 and issue verification

**Files:**
- Verify only.

- [ ] **Step 1: Rediscover the K60 Wi-Fi endpoint and run focused tests**

Run: `adb mdns services`

Connect only to the current `_adb-tls-connect._tcp` endpoint advertising model `23013RK75C`, verify `adb -s DEVICE shell getprop ro.product.model` returns `23013RK75C`, then run both focused instrumentation classes with that serial.

- [ ] **Step 2: Install, force-stop, launch, and inject real keys**

Install `app-universal-debug.apk`, force-stop `com.voyagerfiles.debug`, launch `.app.MainActivity`, browse a disposable folder, and inject:

```bash
adb -s DEVICE shell input keyevent 23
adb -s DEVICE shell input keyevent --longpress 23
```

Expected: a short center press opens the focused item, a held center press enters selection once without also opening it, and a valid APK displays its embedded application icon.

- [ ] **Step 3: Record issue evidence**

Capture the focused-test command results and K60 model/API/serial verification for the eventual #57 and #58 closure comments. Do not close either issue until the combined pull request is merged into `master`.
