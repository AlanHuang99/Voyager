# Storage Access and Removable Media Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep SAF and remote browsing usable after all-files permission denial and reliably expose mounted SD cards and USB storage as direct Home entries when Android provides a filesystem root.

**Architecture:** Replace ad hoc volume scanning on modern Android with a small platform adapter that maps `StorageVolume` values into a pure UI model. MainActivity owns actual permission state, DataStore remembers the user's limited-mode choice, and Home renders only entry points that can work in the current access mode.

**Tech Stack:** Android StorageManager, StorageVolume, DataStore, Jetpack Compose Material 3, JUnit 4.

## Global Constraints

- Support minSdk 26 and targetSdk 35.
- Keep `MANAGE_EXTERNAL_STORAGE` because broad file management is Voyager's core use case.
- Do not require broad access for SAF or remote connections.
- Use `StorageVolume.directory` only on API 30 and newer and retain a tested API 26 through 29 fallback.
- Do not claim inaccessible `Android/data` paths are available.
- Keep prose paragraphs on one physical line.

---

### Task 1: Model storage-volume state independently of Android framework objects

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/util/StorageVolumeInfo.kt`
- Create: `app/src/test/java/com/voyagerfiles/util/StorageVolumeInfoTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt`

**Interfaces:**
- Produces: `StorageVolumeInfo(description, path, isPrimary, isRemovable, state, isReadOnly)`
- Produces: `StorageVolumeInfo.isAvailable` and `StorageVolumeInfo.statusLabel`
- Produces: `FileUtils.getStorageDirectories(context: Context): List<StorageVolumeInfo>`.

- [ ] **Step 1: Write failing state and deduplication tests**

```kotlin
@Test fun mountedReadOnlyVolumeIsVisibleButReadOnly() {
    val volume = StorageVolumeInfo("USB drive", "/storage/ABCD-1234", false, true, Environment.MEDIA_MOUNTED_READ_ONLY)
    assertTrue(volume.isAvailable)
    assertTrue(volume.isReadOnly)
    assertEquals("Read only", volume.statusLabel)
}

@Test fun duplicatePathsPreferSystemDescriptions() {
    val result = mergeStorageVolumes(platformVolumes, fallbackPaths)
    assertEquals(listOf("Internal storage", "USB drive"), result.map { it.description })
}
```

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.StorageVolumeInfoTest`

Expected: storage-volume model is missing.

- [ ] **Step 3: Implement the pure model and merge function**

```kotlin
data class StorageVolumeInfo(
    val description: String,
    val path: String?,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val state: String,
) {
    val isAvailable get() = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    val isReadOnly get() = state == Environment.MEDIA_MOUNTED_READ_ONLY
}
```

- [ ] **Step 4: Add the API adapter**

On API 30 and newer, use `context.getSystemService(StorageManager::class.java).storageVolumes`, `getDescription(context)`, `directory`, `isPrimary`, `isRemovable`, and `state`. On older devices, derive roots from `getExternalFilesDirs(null)` and the existing path helper. Preserve inaccessible volumes with a null path and disabled status only when Android reports them.

- [ ] **Step 5: Run focused tests and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.util.StorageVolumeInfoTest --tests com.voyagerfiles.util.FileUtilsTest`

Expected: storage mapping and existing external-volume tests pass.

```bash
git add app/src/main/java/com/voyagerfiles/util/StorageVolumeInfo.kt app/src/main/java/com/voyagerfiles/util/FileUtils.kt app/src/test/java/com/voyagerfiles/util/StorageVolumeInfoTest.kt app/src/test/java/com/voyagerfiles/util/FileUtilsTest.kt
git commit -m "feat: discover removable storage with platform APIs"
```

### Task 2: Add an explicit limited-access preference

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/StorageAccessModeTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`

**Interfaces:**
- Produces: `enum class StorageAccessMode { FULL, LIMITED, NEEDS_DECISION }`
- Produces: `PreferencesManager.limitedAccessAccepted: Flow<Boolean>` and `setLimitedAccessAccepted(Boolean)`.

- [ ] **Step 1: Write a failing access-mode decision test**

```kotlin
@Test fun deniedPermissionWithAcceptedFallbackUsesLimitedMode() {
    assertEquals(StorageAccessMode.LIMITED, storageAccessMode(hasAllFilesAccess = false, limitedAccepted = true))
}

@Test fun newlyDeniedPermissionNeedsUserDecision() {
    assertEquals(StorageAccessMode.NEEDS_DECISION, storageAccessMode(false, false))
}
```

- [ ] **Step 2: Run focused test and confirm RED**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.StorageAccessModeTest`

Expected: access-mode symbols are missing.

- [ ] **Step 3: Implement the pure access-mode function and DataStore key**

```kotlin
fun storageAccessMode(hasAllFilesAccess: Boolean, limitedAccepted: Boolean): StorageAccessMode = when {
    hasAllFilesAccess -> StorageAccessMode.FULL
    limitedAccepted -> StorageAccessMode.LIMITED
    else -> StorageAccessMode.NEEDS_DECISION
}
```

- [ ] **Step 4: Run focused test and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.StorageAccessModeTest`

Expected: all access-mode tests pass.

```bash
git add app/src/main/java/com/voyagerfiles/data/local/PreferencesManager.kt app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/test/java/com/voyagerfiles/ui/screens/StorageAccessModeTest.kt
git commit -m "feat: remember limited storage access"
```

### Task 3: Make permission denial recoverable

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/app/MainActivity.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/PermissionScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`

**Interfaces:**
- `AppNavigation(viewModel, hasAllFilesAccess, onRequestAllFilesAccess)`
- `PermissionScreen(onRequestPermission, onContinueLimited)`
- `HomeScreen(..., hasAllFilesAccess, onRequestAllFilesAccess)`.

- [ ] **Step 1: Update the first-run permission surface**

```kotlin
Button(onClick = onRequestPermission) { Text("Grant full access") }
TextButton(onClick = onContinueLimited) { Text("Continue with limited access") }
Text("Limited access still supports document trees and remote servers.")
```

- [ ] **Step 2: Always render navigation after a user chooses limited mode**

```kotlin
when (storageAccessMode(permissionGranted, limitedAccepted)) {
    StorageAccessMode.NEEDS_DECISION -> PermissionScreen(...)
    StorageAccessMode.FULL, StorageAccessMode.LIMITED -> AppNavigation(...)
}
```

- [ ] **Step 3: Gate only direct-path Home entries**

When `hasAllFilesAccess` is false, hide internal storage, removable-volume cards, Quick Access direct paths, and common direct folders. Keep Document Tree, Remote Connections, active SAF or remote sessions, Settings, and a clear `Grant full storage access` card.

- [ ] **Step 4: Add permission recovery to Settings**

Settings shows current access as `Full file access` or `Limited access` and provides a button that opens the system all-files screen. Do not represent the Android special-access screen as a runtime permission dialog.

- [ ] **Step 5: Build and run permission-focused tests**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`

Expected: tests pass, lint has no new errors, and debug APK builds.

- [ ] **Step 6: Commit permission recovery**

```bash
git add app/src/main/java/com/voyagerfiles/app/MainActivity.kt app/src/main/java/com/voyagerfiles/ui/screens/PermissionScreen.kt app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt
git commit -m "feat: support browsing with limited storage access"
```

### Task 4: Present storage volumes as a reliable Home section

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/HomeStorageSectionTest.kt`

**Interfaces:**
- `StorageVolumeCard(volume: StorageVolumeInfo, storageInfo: StorageInfo?, onClick: () -> Unit)`.

- [ ] **Step 1: Write a failing Compose test for unavailable and read-only volumes**

```kotlin
composeTestRule.setContent {
    StorageSection(volumes = listOf(unavailableUsb, readOnlySd), onOpen = {})
}
composeTestRule.onNodeWithText("Unavailable").assertExists()
composeTestRule.onNodeWithText("Read only").assertExists()
composeTestRule.onNodeWithText("USB drive").assertIsNotEnabled()
```

- [ ] **Step 2: Run the test and confirm RED**

Run: `./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.screens.HomeStorageSectionTest`

Expected: storage section test fails because the composable or state labels are absent.

- [ ] **Step 3: Implement the Storage section**

Render a `Storage` heading, one card per platform volume, a removable-media icon for removable volumes, usage only for the primary mounted volume, and disabled state with a status label for unavailable roots. Keep the Document Tree card immediately after Storage as the compatibility fallback.

- [ ] **Step 4: Run instrumentation test and confirm GREEN**

Run: `./gradlew connectedDebugAndroidTest --tests com.voyagerfiles.ui.screens.HomeStorageSectionTest`

Expected: the storage section exposes correct labels and enabled state.

- [ ] **Step 5: Commit the Home storage experience**

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt app/src/main/java/com/voyagerfiles/util/FileUtils.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/HomeStorageSectionTest.kt
git commit -m "feat: surface removable media on home"
```
