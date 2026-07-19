# Custom Home Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users hide and reorder logical Home sections while preserving Voyager's current layout by default.

**Architecture:** A normalized `HomeLayout` value owns complete section order and visibility. Preferences DataStore updates it atomically, FileBrowserViewModel exposes it, Settings edits it with switches and move buttons, and HomeScreen emits existing section groups in the configured order.

**Tech Stack:** Kotlin 2.1, Android DataStore Preferences, Jetpack Compose Material 3, Kotlin coroutines, JUnit 4, AndroidX Compose UI tests.

## Global Constraints

- Keep the default Home screen visually and behaviorally unchanged.
- Persist stable enum names and tolerate malformed or older stored values.
- Keep all hidden sections recoverable in Settings.
- Preserve storage-permission and dynamic-content gating.
- Use accessible content descriptions for visibility and move controls.
- Keep prose in Markdown as one paragraph per physical line and do not add AI-authorship markers.

### Task 1: Model and persist a normalized Home layout

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/data/model/HomeLayout.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt`
- Test: `app/src/test/java/com/voyagerfiles/data/model/HomeLayoutTest.kt`

- [ ] Write failing tests for default order, duplicate and unknown persisted names, missing future sections, hidden-name parsing, visibility updates, and move boundaries.
- [ ] Run the focused unit test and verify RED.
- [ ] Implement `HomeSection`, `HomeLayout`, serialization, and atomic DataStore updates.
- [ ] Run the focused unit test and verify GREEN.
- [ ] Commit the model and persistence boundary.

### Task 2: Expose an accessible Home layout editor

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/HomeLayoutSettingsTest.kt`

- [ ] Write a failing Compose test that hides Quick Access, moves Folders upward, verifies boundary controls, and confirms the resulting layout in a new view model.
- [ ] Expose `homeLayout`, `setHomeSectionVisible`, and `moveHomeSection` in the view model.
- [ ] Add the ordered Settings rows with accessible switches and up/down controls.
- [ ] Run the focused connected test and verify GREEN.
- [ ] Commit the editor.

### Task 3: Render Home from the configured layout

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/CustomHomeLayoutTest.kt`

- [ ] Write a failing Compose test that hides Quick Access and places Folders before Remote Connections, then verifies absence and vertical ordering.
- [ ] Extract the six existing Home groups into LazyListScope section emitters and iterate visible configured sections.
- [ ] Run the focused connected test and verify GREEN.
- [ ] Commit configurable Home rendering.

### Task 4: Verify and publish

- [ ] Run `git diff --check master...HEAD`.
- [ ] Run `./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx4096m -Dfile.encoding=UTF-8' testDebugUnitTest lintDebug assembleDebug assembleRelease --rerun-tasks --stacktrace`.
- [ ] Run `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --rerun-tasks --stacktrace`.
- [ ] Manually inspect default and customized layouts on the connected Android 16 device.
- [ ] Request an independent diff review, address actionable findings, and repeat affected gates.
- [ ] Push the branch, open a draft pull request with `Fixes #19`, wait for final-head CI, then mark ready and merge.
