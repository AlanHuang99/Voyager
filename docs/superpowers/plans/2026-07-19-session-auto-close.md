# Session Auto-Close Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a safe, optional inactivity timeout that closes all browser sessions after Voyager has remained fully backgrounded.

**Architecture:** Persist an enable flag and timeout enum, evaluate background intervals with a pure state machine using elapsed realtime, let FileBrowserViewModel own session cleanup and operation deferral, and let navigation react to a closure generation while Browser is visible.

**Tech Stack:** Kotlin 2.1, Android DataStore Preferences, Android lifecycle callbacks, SystemClock, Jetpack Compose Material 3, Kotlin coroutines, JUnit 4, AndroidX Compose UI tests.

## Global Constraints

- Default to disabled and retain the selected interval when disabled.
- Measure only time between Activity `onStop` and `onResume` using a monotonic clock.
- Do not interrupt active operations.
- Disconnect every provider that belonged to a session present at `onStop`, preserve sessions created by returning Activity Result callbacks, and clear provider-backed clipboard references.
- Preserve view, sort, hidden-file, and theme preferences.
- Keep prose in Markdown as one paragraph per physical line and do not add AI-authorship markers.

### Task 1: Model timeout preferences and state transitions

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/data/model/SessionAutoCloseTimeout.kt`
- Create: `app/src/main/java/com/voyagerfiles/viewmodel/SessionAutoCloseTracker.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt`
- Test: `app/src/test/java/com/voyagerfiles/data/model/SessionAutoCloseTimeoutTest.kt`
- Test: `app/src/test/java/com/voyagerfiles/viewmodel/SessionAutoCloseTrackerTest.kt`

- [ ] Write failing tests for enum fallback, durations, threshold boundaries, disabled behavior, deferred closure, pending consumption, cancellation, and clock rollback.
- [ ] Run the focused unit tests and verify RED.
- [ ] Implement the enum, pure tracker, DataStore flows, and setters.
- [ ] Run the focused unit tests and verify GREEN.
- [ ] Commit the model boundary.

### Task 2: Close sessions safely through the view model

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/viewmodel/SessionAutoCloseTest.kt`

- [ ] Write failing Android tests that enable auto-close, open a session, populate clipboard state, simulate the threshold, and assert complete cleanup plus a generation increment, then verify closure waits for an active operation.
- [ ] Expose preference StateFlows and setters, snapshot session IDs at `onStop`, add lifecycle entry points, defer closure while `OperationState.Running`, and implement one-pass provider cleanup.
- [ ] Run the focused Android test and verify GREEN.
- [ ] Commit view-model cleanup.

### Task 3: Add lifecycle, Settings, and navigation integration

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/app/MainActivity.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/SessionAutoCloseSettingsTest.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/SessionAutoCloseNavigationTest.kt`

- [ ] Write failing Compose tests for the toggle, timeout selection, persisted flows, Browser-to-Home navigation after closure, and preservation of a session created by a returning picker result.
- [ ] Report `elapsedRealtime()` from MainActivity `onStop` and `onResume`.
- [ ] Add a Sessions settings section with an accessible toggle and interval dropdown.
- [ ] Observe the closure generation and navigate Home only from Browser.
- [ ] Run focused connected tests and verify GREEN.
- [ ] Commit lifecycle and UI integration.

### Task 4: Verify and publish

- [ ] Run `git diff --check master...HEAD`.
- [ ] Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --rerun-tasks --stacktrace`.
- [ ] Run `ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --rerun-tasks --stacktrace`.
- [ ] Exercise Settings and background timeout behavior on the connected device with a short injected threshold or deterministic instrumentation coverage, and inspect logs for uncaught failures.
- [ ] Push the branch, open a draft pull request with `Fixes #20`, wait for CI, then mark ready and merge only after all gates pass.
