# Localization Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Voyager's static user-visible English into Android resources and make ViewModel messages, plurals, enum labels, formatting, and contributor guidance localization-ready without inventing a translation.

**Architecture:** `UiText` represents string resources, plural resources, and genuinely dynamic details across ViewModel/UI boundaries. Android-facing enums and UI models expose resource IDs rather than English. A source contract catches regressions in Compose text, accessibility descriptions, snackbar calls, and string-valued UI enum labels, while instrumentation verifies real resource resolution and plurals.

**Tech Stack:** Android string and plural resources, Kotlin sealed interfaces, Jetpack Compose `stringResource` and `pluralStringResource`, JUnit 4, Android instrumentation, lint.

**Spec:** `docs/superpowers/specs/2026-08-24-github-issues-52-58-design.md`

## Global Constraints

- The only shipped source language in this change is English.
- Do not fabricate translations or create a locale directory without a human translation.
- Protocol names and the Voyager brand are non-translatable.
- All format placeholders use positional indexes when more than one argument exists.
- Counts use `<plurals>` resources; no English `if (count == 1)` suffix construction remains in production UI/ViewModel code.
- Dynamic filenames, paths, hostnames, exception details, generated keys, and document-provider display names remain dynamic values.
- Dynamic exception details appear only with a stable localized message.
- `android:supportsRtl="true"` remains enabled.

---

### Task 1: Hardcoded-text regression contract

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/ui/LocalizationResourceContractTest.kt`

**Interfaces:**
- Produces: a JVM source scan over production Compose/ViewModel files and XML resource integrity checks.

- [ ] **Step 1: Write the failing source contract**

Scan every `.kt` file under `app/src/main/java`. Report file and line for these forbidden patterns outside comments:

```kotlin
private val forbidden = listOf(
    Regex("\\bText\\(\\s*\""),
    Regex("contentDescription\\s*=\\s*\""),
    Regex("placeholder\\s*=\\s*\\{\\s*Text\\(\\s*\""),
    Regex("showSnackbar\\(\\s*\""),
    Regex("SnackbarHostState\\(\\).*showSnackbar\\(\\s*\""),
)
```

Also assert `strings.xml` contains more than `app_name`, contains at least one `<plurals>`, and marks `app_name`, `protocol_sftp`, `protocol_ftp`, `protocol_smb`, and `protocol_webdav` with `translatable="false"`.

- [ ] **Step 2: Run and verify the red state**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.LocalizationResourceContractTest --stacktrace`

Expected: FAIL with current hardcoded `Text`, content-description, and snackbar lines plus missing plurals/protocol resources.

- [ ] **Step 3: Commit the failing contract**

```bash
git add app/src/test/java/com/voyagerfiles/ui/LocalizationResourceContractTest.kt
git commit -m "test: require localized user-facing text"
```

### Task 2: Structured UI text and resource resolution

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/text/UiText.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/text/UiTextTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `UiText.Resource`, `UiText.Plural`, `UiText.Dynamic`, `UiText.resolve(resources)`, and Compose `UiText.asString()`.
- Consumes later: ViewModel snackbar, operation state, validation, warnings, and screen/component copy.

- [ ] **Step 1: Add foundational resources**

```xml
<string name="app_name" translatable="false">Voyager</string>
<string name="protocol_sftp" translatable="false">SFTP</string>
<string name="protocol_ftp" translatable="false">FTP</string>
<string name="protocol_smb" translatable="false">SMB</string>
<string name="protocol_webdav" translatable="false">WebDAV</string>
<string name="unknown_error">Unknown error</string>
<string name="operation_failed">Could not %1$s: %2$s</string>
<plurals name="items_selected">
    <item quantity="one">%d selected</item>
    <item quantity="other">%d selected</item>
</plurals>
<plurals name="items_count">
    <item quantity="one">%d item</item>
    <item quantity="other">%d items</item>
</plurals>
```

- [ ] **Step 2: Write failing resolution tests**

Under `Locale.US`, assert resource resolution formats nested arguments. Under a non-English formatting locale such as `Locale.GERMANY`, assert a resource with an integer formatting argument resolves through Android resources without freezing a previously resolved English string. Assert plurals resolve `0 selected`, `1 selected`, `2 selected` and `0 items`, `1 item`, `2 items`. Assert `UiText.Dynamic("report.pdf")` remains unchanged.

- [ ] **Step 3: Run and verify compilation fails**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.text.UiTextTest --stacktrace`

Expected: compilation FAIL because `UiText` does not exist.

- [ ] **Step 4: Implement recursive structured resolution**

```kotlin
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any> = emptyList()) : UiText
    data class Dynamic(val value: String) : UiText
}

fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Resource -> resources.getString(id, *args.map { it.resolveArgument(resources) }.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, quantity, *args.map { it.resolveArgument(resources) }.toTypedArray())
    is UiText.Dynamic -> value
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current.resources)

private fun Any.resolveArgument(resources: Resources): Any =
    if (this is UiText) resolve(resources) else this
```

- [ ] **Step 5: Run resolution tests and commit**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.text.UiTextTest --stacktrace`

Expected: PASS.

```bash
git add app/src/main/java/com/voyagerfiles/ui/text/UiText.kt app/src/androidTest/java/com/voyagerfiles/ui/text/UiTextTest.kt app/src/main/res/values/strings.xml
git commit -m "feat: add locale-aware UI text values"
```

### Task 3: ViewModel snackbar and operation messages

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/OperationMessages.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/OperationState.kt`
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/TransferProgress.kt`
- Modify: `app/src/test/java/com/voyagerfiles/viewmodel/OperationMessagesTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Changes: `snackbarMessage: StateFlow<UiText?>`, `OperationState.Running.label: UiText`, and `TransferProgress.label: UiText`.
- Produces: `OperationMessages.failure(@StringRes operationName: Int, error: Throwable): UiText`.

- [ ] **Step 1: Write failing structured-message tests**

```kotlin
@Test fun failureKeepsStableCopyAndDynamicDetailSeparate() {
    assertEquals(
        UiText.Resource(
            R.string.operation_failed,
            listOf(UiText.Resource(R.string.operation_download), UiText.Dynamic("disk full")),
        ),
        OperationMessages.failure(R.string.operation_download, IOException("disk full")),
    )
}

@Test fun missingFailureDetailUsesLocalizedUnknownError() {
    val message = OperationMessages.failure(R.string.operation_rename, IOException())
    assertTrue((message as UiText.Resource).args.last() is UiText.Resource)
}
```

- [ ] **Step 2: Run and verify the red state**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.viewmodel.OperationMessagesTest --stacktrace`

Expected: compilation FAIL because operation messages return `String`.

- [ ] **Step 3: Add exact operation and outcome resources**

Add stable operation names for create folder, create file, upload, Trash, delete, rename, compress, extract, copy, move, paste, download, save connection, delete connection, empty Trash, and bookmarks. Add outcome resources for folder/file created, upload count, renamed filename, created/extracted archive, copied/cut counts, paste success, download start/count/destination, connection saved/updated/deleted, Trash outcomes, operation already running, partial failure counts, and the fixed validation/unavailable messages currently passed to `showSnackbar`.

Use plural resources named `files_uploaded`, `items_trashed`, `items_deleted`, `items_copied`, `items_cut`, `items_downloading`, `items_downloaded`, and `operations_succeeded` with `one` and `other` quantities and positional placeholders.

- [ ] **Step 4: Convert ViewModel state without resolving resources**

Change `_snackbarMessage` and `showSnackbar` to `UiText`. Replace every static call with `UiText.Resource`, every count with `UiText.Plural`, and every filename/error detail with nested `UiText.Dynamic`. Replace operation label strings with resource-backed `UiText` at call sites. Do not call `getString()` inside the ViewModel.

```kotlin
private fun showSnackbar(message: UiText) { _snackbarMessage.value = message }

showSnackbar(
    UiText.Plural(
        id = R.plurals.items_downloaded,
        quantity = count,
        args = listOf(count, UiText.Resource(R.string.downloads_folder)),
    )
)
```

- [ ] **Step 5: Resolve only at Compose boundaries**

Update `BrowserScreen`, `TrashScreen`, and operation-progress composables to call `asString()` for collected `UiText`. Preserve the existing `clearSnackbar()` event lifecycle.

- [ ] **Step 6: Run ViewModel and operation UI tests**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.viewmodel.*' --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.BrowserOperationProgressTest --stacktrace`

Expected: PASS with structured messages and unchanged user-visible English.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/main/java/com/voyagerfiles/ui/screens/TrashScreen.kt app/src/test/java/com/voyagerfiles/viewmodel app/src/main/res/values/strings.xml
git commit -m "refactor: localize browser operation messages"
```

### Task 4: Resource-backed enums, validation, and warning models

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/model/RemoteConnection.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/FileFilter.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/FileItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/HomeLayout.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/model/SessionAutoCloseTimeout.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/theme/AppTheme.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserCreateMenu.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/DeleteDialogModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/DeleteChoiceDialog.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/DeleteDialogModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/ConnectionFormValidator.kt`
- Modify: related JVM tests under `app/src/test/java/com/voyagerfiles/data/model`, `ui/components`, and `ui/screens`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Changes user-facing enum/model properties from `String` to `@StringRes Int` or `UiText`.
- Preserves dynamic data fields such as `TrashEntry.displayName` and document metadata as `String`.

- [ ] **Step 1: Write failing resource-ID model assertions**

Assert `ConnectionProtocol.SFTP.displayNameRes == R.string.protocol_sftp`, `ViewMode.GRID.labelRes == R.string.view_mode_grid`, `FileTypeFilter.IMAGES.labelRes == R.string.filter_images`, `HomeSection.REMOTE.labelRes == R.string.home_remote_connections`, `SessionAutoCloseTimeout.MINUTES_15.labelRes == R.string.session_timeout_15_minutes`, and each create/delete/warning action exposes the expected resource or structured text rather than English.

- [ ] **Step 2: Run focused model tests and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.model.*' --tests 'com.voyagerfiles.ui.components.*' --tests 'com.voyagerfiles.ui.screens.*' --stacktrace`

Expected: compilation FAIL because the `labelRes`/`displayNameRes` properties do not exist.

- [ ] **Step 3: Add and wire exact label resources**

Add resources for all protocol names, view modes, file filters, Home sections, session timeouts, app themes, create actions, delete/trash choices, connection field validation, FTP/HTTP transport warnings, SMB discovery supporting text, and settings color groups. Mark protocol resources non-translatable. Use resource IDs in the model types and resolve them with `stringResource` only in Compose.

```kotlin
enum class ViewMode(@StringRes val labelRes: Int) {
    LIST(R.string.view_mode_list),
    COMPACT(R.string.view_mode_compact_list),
    GRID(R.string.view_mode_grid),
}

data class ConnectionFormValidation(
    @StringRes val hostErrorRes: Int? = null,
    @StringRes val portErrorRes: Int? = null,
    @StringRes val shareNameErrorRes: Int? = null,
)
```

- [ ] **Step 4: Run model, validation, and settings tests**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.model.*' --tests 'com.voyagerfiles.ui.components.*' --tests 'com.voyagerfiles.ui.screens.*' --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.HomeLayoutSettingsTest,com.voyagerfiles.ui.screens.SessionAutoCloseSettingsTest --stacktrace`

Expected: PASS with the same English labels resolved from resources.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/data/model app/src/main/java/com/voyagerfiles/ui/theme/AppTheme.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserCreateMenu.kt app/src/main/java/com/voyagerfiles/ui/components app/src/test/java/com/voyagerfiles/data/model app/src/test/java/com/voyagerfiles/ui app/src/main/res/values/strings.xml
git commit -m "refactor: use resources for UI model labels"
```

### Task 5: Compose screens and components

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/ArchiveNameDialog.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/DeleteChoiceDialog.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileDetailsSheet.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileGridItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/FileListItem.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/ConnectionsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/PermissionScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/TrashScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `UiText`, resource-backed models, and plurals.
- Produces: no static English passed directly to Compose `Text`, placeholder, accessibility, dialog, or snackbar APIs.

- [ ] **Step 1: Add screen/component resources by current UI grouping**

Use prefixes `browser_`, `connection_`, `home_`, `permission_`, `settings_`, `trash_`, `dialog_`, `action_`, `content_desc_`, `details_`, and `playback_`. Preserve every current English phrase exactly unless the approved issue change already alters it. Use format resources for filenames, paths, connection names, current view labels, section names, and palette names. Use plurals for selection, clipboard, upload/download, and delete counts.

- [ ] **Step 2: Replace static Compose text and accessibility literals**

Use `stringResource`, `pluralStringResource`, and resource-backed model IDs. For selection checkboxes use:

```kotlin
contentDescription = stringResource(
    if (isSelected) R.string.content_desc_deselect_file else R.string.content_desc_select_file,
    file.name,
)
```

For selection counts use:

```kotlin
Text(pluralStringResource(R.plurals.items_selected, state.selectedFiles.size, state.selectedFiles.size))
```

Keep `Text(file.name)`, `Text(path)`, exception details, generated public keys, and other truly dynamic values as direct dynamic text.

- [ ] **Step 3: Run the hardcoded-text contract to find every remaining static line**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.LocalizationResourceContractTest --stacktrace`

Expected: PASS. Any reported production source line must be converted or, if truly non-visible such as an animation label/test tag, narrowed out by a specific pattern that cannot hide user copy.

- [ ] **Step 4: Run all screen/component instrumentation**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.voyagerfiles.ui --stacktrace`

Expected: PASS. Update test selectors to resource-resolved English only where the production accessibility/text contract intentionally changed, not to weaken assertions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/ui app/src/main/res/values/strings.xml app/src/androidTest/java/com/voyagerfiles/ui
git commit -m "refactor: move Compose copy into Android resources"
```

### Task 6: Translation guide, RTL, and completeness gate

**Files:**
- Create: `docs/TRANSLATING.md`
- Modify: `README.md`
- Modify: `docs/TESTING.md`
- Modify: `app/src/androidTest/java/com/voyagerfiles/ui/text/UiTextTest.kt`

**Interfaces:**
- Produces: contributor workflow for locale directories, placeholders, plurals, and RTL checks.

- [ ] **Step 1: Write the translation guide**

Document that contributors copy `app/src/main/res/values/strings.xml` into `values-<language>/strings.xml`, never translate entries marked `translatable="false"`, preserve `%1$s`/`%2$d` indexes and XML escaping, supply every required plural quantity for the locale, and test layouts with pseudo-locales and RTL. Include these commands:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
ANDROID_SERIAL=DEVICE ./gradlew connectedDebugAndroidTest --stacktrace
adb -s DEVICE shell setprop debug.force_rtl 1
adb -s DEVICE shell am force-stop com.voyagerfiles.debug
```

Also explain how to restore `debug.force_rtl` to `0` and restart the app.

- [ ] **Step 2: Add resource completeness and format tests**

Parse the default `strings.xml` and assert resource names are unique, every formatted string uses indexed placeholders when it has multiple arguments, every plural has `one` and `other`, and all translatable screen resources are nonempty. Resolve representative filename, count, percentage, host, and error-detail formats under US and German formatting configurations.

- [ ] **Step 3: Run localization gates**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.LocalizationResourceContractTest lintDebug --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.text.UiTextTest --stacktrace`

Expected: PASS with no `HardcodedText`, `MissingTranslation`, invalid-format, or plural failures.

- [ ] **Step 4: K60 RTL smoke and cleanup**

Enable force RTL on the K60, force-stop and relaunch the debug app, inspect Home, Connections, list/grid Browser, selection toolbar, Settings, Trash, and dialogs for clipping or reversed icon semantics, then restore force RTL to `0` and relaunch. Capture screenshots only in a temporary directory and remove them after review.

- [ ] **Step 5: Commit**

```bash
git add docs/TRANSLATING.md README.md docs/TESTING.md app/src/androidTest/java/com/voyagerfiles/ui/text/UiTextTest.kt
git commit -m "docs: add Voyager translation guidance"
```

### Task 7: Issue #53 evidence

**Files:**
- Verify only.

- [ ] **Step 1: Run fresh complete localization verification**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace`

Run: `ANDROID_SERIAL=K60_SERIAL ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.text.UiTextTest --stacktrace`

Expected: PASS.

- [ ] **Step 2: Prepare the issue response**

State that all source copy, plurals, model labels, ViewModel messages, and the contribution guide are now localization-ready, that no translation was fabricated because no target language was supplied, and invite the reporter's offered translation. Close only after the implementation reaches `master`.
