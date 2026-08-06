# Selection Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one tactile response when selection mode begins and restore reliable dynamic-theme contrast in the selection toolbar.

**Architecture:** A pure transition function defines when haptics occur, and `BrowserScreen` applies it before forwarding selection changes to the ViewModel. Selection-toolbar Material colors are explicit at the point where the top app bar is constructed.

**Tech Stack:** Kotlin, Jetpack Compose Foundation and Material 3, JUnit 4, AndroidX Compose UI tests.

## Global Constraints

- Emit one haptic only for the empty-to-nonempty selection transition.
- List, compact-list, and grid modes use the same callback.
- Selection navigation, title, and action icons use `onPrimaryContainer` over `primaryContainer`.
- Keep existing row-selection colors and accessibility descriptions unchanged.

---

### Task 1: Selection transition policy

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/SelectionFeedbackTest.kt`
- Create: `app/src/main/java/com/voyagerfiles/ui/screens/SelectionFeedback.kt`

**Interfaces:**
- Produces: `shouldPerformSelectionHaptic(selectedPaths: Set<String>, toggledPath: String): Boolean`.

- [ ] **Step 1: Write failing table-driven tests**

```kotlin
@Test
fun hapticOccursOnlyWhenTheFirstItemBecomesSelected() {
    assertTrue(shouldPerformSelectionHaptic(emptySet(), "/first"))
    assertFalse(shouldPerformSelectionHaptic(setOf("/first"), "/second"))
    assertFalse(shouldPerformSelectionHaptic(setOf("/first"), "/first"))
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.SelectionFeedbackTest --stacktrace`

Expected: compilation FAIL because the function is missing.

- [ ] **Step 3: Implement the minimal policy**

```kotlin
internal fun shouldPerformSelectionHaptic(
    selectedPaths: Set<String>,
    toggledPath: String,
): Boolean = selectedPaths.isEmpty() && toggledPath !in selectedPaths
```

- [ ] **Step 4: Run and verify the test passes**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.SelectionFeedbackTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/SelectionFeedback.kt app/src/test/java/com/voyagerfiles/ui/screens/SelectionFeedbackTest.kt
git commit -m "test: define selection haptic policy"
```

### Task 2: Browser haptic wiring

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`

**Interfaces:**
- Consumes: `shouldPerformSelectionHaptic` and `LocalHapticFeedback`.

- [ ] **Step 1: Add a Compose test that exercises the first-selection callback in list and grid mode**

Use the existing real `BrowserScreen` fixture, enter selection from an empty set, and assert the toolbar appears with `1 selected` in both view modes. This protects the shared callback wiring while physical haptic output remains a device assertion.

- [ ] **Step 2: Run the focused device test before wiring**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: the new grid/list transition test FAILS if it references the new shared callback semantics.

- [ ] **Step 3: Wire one browser-level callback**

```kotlin
val hapticFeedback = LocalHapticFeedback.current

fun toggleSelection(path: String) {
    if (shouldPerformSelectionHaptic(state.selectedFiles, path)) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    viewModel.toggleSelection(path)
}
```

Pass `::toggleSelection` from both list and grid click/long-click paths.

- [ ] **Step 4: Run unit and device tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.SelectionFeedbackTest --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "feat: add selection-mode haptic feedback"
```

### Task 3: Dynamic-color toolbar contrast

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt`

**Interfaces:**
- Produces: explicit `TopAppBarColors` with `primaryContainer` and `onPrimaryContainer`.

- [ ] **Step 1: Add a low-chroma theme rendering test**

Render the selection toolbar with a custom `lightColorScheme(primaryContainer = Color(0xFFAAAAAA), onPrimaryContainer = Color(0xFF101010))`, assert the title and all action semantics remain displayed, and capture the toolbar image for pixel inspection in the test assertion helper. The helper must assert that action-icon foreground pixels differ from the container by a WCAG contrast ratio of at least 3:1.

- [ ] **Step 2: Run and verify the contrast test fails**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: FAIL because action icons inherit the low-contrast default observed in issue #41.

- [ ] **Step 3: Set all selection-toolbar content colors explicitly**

```kotlin
colors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
```

- [ ] **Step 4: Run the contrast and selection tests**

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserSelectionActionsTest --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/BrowserSelectionActionsTest.kt
git commit -m "fix: restore selection toolbar contrast"
```

### Task 4: Verification and PR

**Files:**
- Modify: `docs/TESTING.md`

- [ ] **Step 1: Add dynamic-gray and haptic checks to the manual matrix**

Document that one haptic occurs when the first item is selected and that toolbar actions remain readable with the K60 gray dynamic palette.

- [ ] **Step 2: Run the complete local and device gates**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace`

Run: `ANDROID_SERIAL=192.168.27.182:37713 ./gradlew connectedDebugAndroidTest --stacktrace`

Expected: both commands complete successfully with zero failures.

- [ ] **Step 3: Perform K60 visual and tactile verification**

Install and restart the debug APK, use the gray Material You palette, long-press one file in list and grid modes, confirm one tactile response per transition into selection mode, and capture a screenshot showing readable close, title, rename/share/delete, and overflow controls.

- [ ] **Step 4: Commit, review, push, and open PR**

```bash
git add docs/TESTING.md
git commit -m "docs: cover selection feedback and contrast"
git push -u origin codex/selection-feedback
gh pr create --base master --head codex/selection-feedback --title "Improve selection feedback and contrast" --body "Closes #40\nCloses #41"
```
