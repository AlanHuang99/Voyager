# Testing

Run commands from the repository root with JDK 17 and an Android SDK configured.

## Automated gates

Run the fast JVM suite:

```bash
./gradlew testDebugUnitTest --stacktrace
```

Run Android lint and build both variants:

```bash
./gradlew lintDebug assembleDebug assembleRelease --stacktrace
```

Run the same complete gate used by CI:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace
```

Reports are written under `app/build/reports/`. APKs are written under `app/build/outputs/apk/`.

## Localization and RTL

The JVM localization contract checks that production Compose copy uses Android resources, resource names are unique, multi-argument formats use indexed placeholders, plurals define `one` and `other`, and translatable values are nonempty. Android lint separately checks hardcoded text, missing translations, and invalid resource formats.

```bash
./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.LocalizationResourceContractTest lintDebug --stacktrace
```

Run the locale-resolution instrumentation on a connected device:

```bash
ANDROID_SERIAL=DEVICE ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.text.UiTextTest --stacktrace
```

Use the forced-RTL commands and cleanup procedure in [TRANSLATING.md](TRANSLATING.md) for visual review. Always restore `debug.force_rtl` to `0` and restart the debug app after testing.

## Device instrumentation

Connect an Android device through USB or wireless debugging and confirm that `adb devices` reports it as `device` rather than `offline` or `unauthorized`.

```bash
adb mdns services
adb connect DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT
ANDROID_SERIAL=DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT ./gradlew connectedDebugAndroidTest --stacktrace
```

Instrumentation installs the debug package `com.voyagerfiles.debug`. The current tests exercise direct Android file-open and WebDAV playback intents, seekable read-only WebDAV proxy descriptors, the explicit playback-download fallback and Open with chooser, APK installer permission, document upload metadata, generated SFTP public-key export, image, bounded video and embedded OOXML previews, Trash payload previews, first-page PDF, and embedded local APK thumbnails, Android TV initial focus, held-select and protocol-menu keyboard behavior, pull-to-refresh in list/grid/empty/error states, same-path session switches during refresh, bookmark persistence and removal, forward/Back theme-background rendering, byte totals, transfer speed, determinate and indeterminate operation progress, archive naming, direct and selection-based ZIP extraction, archive-tap confirmation and cancellation, unavailable-storage presentation, saved-connection and file-delete confirmations, single and multiple share intents, contextual selection actions, first-selection haptic dispatch, selection-toolbar contrast, permanent local deletion, compact-view persistence, file details, parent navigation after using search, selection-control accessibility labels, and Android Keystore encryption.

Run the issue-focused device coverage with:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.playback.WebDavPlaybackProviderTest,com.voyagerfiles.ui.screens.WebDavPlaybackFlowTest,com.voyagerfiles.util.FileSharingTest,com.voyagerfiles.util.UploadSourceFactoryTest,com.voyagerfiles.ui.components.ApkThumbnailLoaderTest,com.voyagerfiles.ui.components.FileThumbnailTest,com.voyagerfiles.ui.components.GeneratedPublicKeyDialogTest,com.voyagerfiles.ui.components.PdfThumbnailLoaderTest,com.voyagerfiles.ui.components.RemoteSelectKeyHandlerTest,com.voyagerfiles.ui.screens.BrowserOperationProgressTest,com.voyagerfiles.ui.screens.BrowserSelectionActionsTest,com.voyagerfiles.ui.screens.BrowserTvFocusTest,com.voyagerfiles.viewmodel.ArchiveOperationsTest,com.voyagerfiles.viewmodel.DocumentUploadTest --stacktrace
```

Category indexing has JVM coverage in `StorageCategoryIndexTest` for mixed file types across multiple volume roots, `.m4a` audio classification, hidden-file inclusion, unreadable and disconnected locations, overlapping roots, depth/result limits, stale or replaced files, permission denial/revocation and cancellation. `StorageCategoryIndexAndroidTest` checks actual device files, existing APK/video fixtures, Android MIME resolution, file-open intent creation and removal. Its live scan case requires the test package’s `MANAGE_EXTERNAL_STORAGE` appop set to `allow` before instrumentation starts; it discovers a temporary file through the real volume adapter, revalidates removal and clears the ViewModel on an access-loss signal. A denied run skips that one case. `CategoryScreenTest` covers explicit counts, paths, cancellation, refresh and permission recovery. Run the device cases with `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.data.index.StorageCategoryIndexAndroidTest,com.voyagerfiles.ui.screens.CategoryScreenTest`. Multi-volume JVM fixtures cover mounted and read-only roots; a physical removable volume is needed to verify an actual SD/USB mount on a device.

## Protocol integration tests

Duplicate discovery has focused JVM coverage in `DuplicateScannerTest` and `DuplicateRemovalTest` for size/hash discrimination, empty files, unreadable/changed/missing coverage, cancellation, link/Trash exclusions, scan limits, keeper revalidation, and Trash move/restore. `DuplicatesScreenTest` uses disposable device files to verify explicit permanent deletion, keeper preservation, missing-keeper rejection, navigation paths containing spaces and plus signs, system Back, and foreground-service startup failure recovery.

Tone instrumentation has separate granted and denied `WRITE_SETTINGS` cases. Configure the debug package appop externally before instrumentation. Tests that set tones must save both original ringtone and notification URIs, restore them in teardown, and independently read them back afterward. Never revoke the storage appop from inside instrumentation. Use disposable audio and preserve the production package and its data.

Root access has JVM coverage for literal quoted and newline-bearing names, bounded output, command timeouts and session closure, error propagation, path aliases, stale saves, mode preservation, symlink and hard-link rejection, and interrupted writes. Its isolated Docker test uses a digest-pinned Alpine container with a root-only disposable directory and a read-only root filesystem. It proves UID 0 can operate where UID 65534 is denied, and that a failed read-only save preserves the original:

```bash
VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests '*Root*Test'
```

Run `RootFileProviderAndroidTest` and `RootAccessUiTest` for Android toybox compatibility, explicit root confirmation, visible denial, ordinary local browsing, staged editor saves, discard, and permanent-delete confirmation. The command compatibility and editor fixtures deliberately use an unprivileged shell in the debug app cache; they do not claim successful superuser access. Successful privileged Android operation must also be checked on a rooted disposable device, including SELinux labels and owner/mode preservation. On the September 8 K60, `su -c id` returned `Permission denied`; only denial and unprivileged Android command/UI behavior can be verified there.

```bash
ANDROID_SERIAL=DEVICE ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.data.repository.RootFileProviderAndroidTest,com.voyagerfiles.ui.screens.RootAccessUiTest
```

Folder-shortcut launch instrumentation has separate granted and denied storage-access cases. Set the debug package’s `MANAGE_EXTERNAL_STORAGE` appop before starting instrumentation; changing it during a test can kill the instrumentation process. Run `FolderShortcutLaunchTest` once with `allow` and once with `ignore`, then restore the prior mode externally. Each run skips the cases requiring the other access mode. The granted cases cover repeated intents, Activity recreation, and a removed destination; the denied case verifies the access prompt. Native pin confirmation and launching the resulting icon also require a launcher check.

The JVM suite starts isolated local FTP, SFTP, and WebDAV servers. It covers authentication, list and metadata operations, recursive copy and delete, exact terminal progress for known and unknown sizes, time and byte publication thresholds, bounded-memory streams, WebDAV HTTP request-body upload progress, WebDAV transport URLs, and SFTP host-key pinning and rotation rejection.

For a physical-device WebDAV playback check, serve disposable audio and video fixtures from an authenticated server reachable over the device's Wi-Fi network. First verify the server returns `206 Partial Content`, an exact `Content-Range`, and the requested byte count for a request such as `Range: bytes=1-3`. Install the universal debug APK, connect Voyager to that endpoint, and tap both fixtures and a seekable PDF document. In the external player, seek forward and backward and confirm the server records authenticated nonsequential range requests. Confirm that neither Android's Downloads folder nor Voyager's app cache gains a complete media copy. A server that ignores ranges must produce Voyager's `Direct opening unavailable` dialog; Cancel leaves the file untouched, while Download starts the existing transfer flow.

An opt-in Docker test restricts an OpenSSH-backed SFTP container to the `mlkem768x25519-sha256`, `sntrup761x25519-sha512`, and `sntrup761x25519-sha512@openssh.com` hybrid post-quantum key exchanges. It verifies that an SSH key generated by Voyager authenticates, uploads and downloads a probe file with exact byte equality, and creates and extracts a ZIP through the production remote provider:

```bash
VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SftpDockerIntegrationTest --stacktrace
```

The test uses the `atmoz/sftp:alpine` image, publishes a random loopback port, validates the restricted `sshd` configuration before startup, and always force-removes its uniquely named container during teardown.

SMB tests require a disposable writable share. Set every required variable before running the focused suite:

```bash
export VOYAGER_SMB_HOST=server.example
export VOYAGER_SMB_PORT=445
export VOYAGER_SMB_SHARE=test-share
export VOYAGER_SMB_USERNAME=tester
export VOYAGER_SMB_PASSWORD='test-password'
export VOYAGER_SMB_DOMAIN='optional-domain'
./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbFileProviderTest --stacktrace
```

The SMB test creates a uniquely named directory and removes it during teardown. Use a test share, not irreplaceable data.

Run the digest-pinned authenticated Samba discovery and binary-compatibility gate with:

```bash
VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbDockerIntegrationTest --stacktrace
```

The test starts `dperson/samba@sha256:66088b78a19810dd1457a8f39340e95e663c728083efa5fe7dc0d40b2478e869` on a random loopback port, discovers two writable disk shares through RPC, verifies exact bytes, exercises direct-share mode, reconnects repeatedly, and always force-removes its uniquely named container. To verify the same RPC path on an Android device, expose an equivalent disposable Samba fixture to the device and pass its address to the opt-in instrumentation test:

```bash
ANDROID_SERIAL=DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.data.remote.smb.SmbAndroidIntegrationTest -Pandroid.testInstrumentationRunnerArguments.smbHost=SERVER_ADDRESS -Pandroid.testInstrumentationRunnerArguments.smbPort=SERVER_PORT --stacktrace
```

## SAF write regression

Use Android's document-tree picker to create or select a disposable `VoyagerSafTest` folder. From a direct-local browser session, copy a small file and a directory containing a nested file, switch to the document-tree session, and paste them. Repeat with Cut so the source is removed only after the destination completes. Verify exact file bytes at the destination, no existing item is overwritten, a deliberately interrupted transfer leaves no partial destination, and logcat contains neither `rbDocOpen` nor `EPERM`. Remove all disposable fixtures when finished.

## Manual Android regression matrix

Use disposable files and keep device orientation unlocked unless a case calls for a fixed orientation.

For external editing, open a writable local text file with an editor through Open with, save changes, and verify the source bytes. A read-only local file must not receive write permission, and Share must remain read-only. The remote-key instrumentation fixture explicitly leaves touch mode before requesting focus so its result does not depend on whether earlier tests used touch input.

| Area | Cases |
| --- | --- |
| Permission | Deny full access, continue in limited mode, open a SAF tree, open a remote screen, return to Settings, then grant full access. |
| Storage | Browse internal storage, an available removable volume, and an unavailable or unmounted volume if one is present. |
| Navigation | Enter nested directories, use breadcrumbs and Back, switch sessions during a slow load, rotate the device, and confirm the latest location remains visible. |
| Search and filters | Search case-insensitively, combine search with each type filter, select all visible results, clear filters, and test an empty result. |
| File opening and thumbnails | Open a local or SAF file, choose an Android handler as the default where the OS offers that choice, reopen the file, and confirm the default is honored; use Open with on one selected file and confirm the chooser appears; open an APK, allow Voyager as an installation source if prompted, confirm the system package installer opens, then cancel installation; verify image and first-page PDF thumbnails in list and grid layouts, including invalid PDF fallback; verify a readable local APK shows its embedded application icon while corrupt, SAF, and remote APKs retain the generic Android icon. |
| File operations | Create, rename, copy, move, and delete files and directories; share one and several local or SAF files; inspect Details; attempt a duplicate destination and a move into a descendant; verify the source survives failures; copy and move both a file and a nested directory into a disposable SAF tree; verify determinate byte progress and speed for known sizes and indeterminate progress without `0%` for unknown totals. |
| Archives | Create ZIP files from one file, several items, an empty directory, and a nested directory; tap a supported archive and verify Cancel leaves the folder unchanged while Extract creates a new extraction root; extract ZIP, TAR, TGZ/TAR.GZ, TBZ2/TAR.BZ2, GZ, and BZ2 fixtures; repeat on a SAF or disposable remote provider; verify conflicts do not overwrite data; verify corrupt, encrypted, traversal, link, and RAR inputs fail with an actionable message and leave no partial extraction root. |
| Trash | For a direct-local selection, verify both Trash and permanent choices; move a file to Trash, restore it, create a restore conflict, permanently delete an entry, empty Trash, and repeat with Trash disabled. |
| Error recovery | Remove or unmount a location while browsing, deny a SAF operation, open an unsupported file, use a bad remote hostname, and verify retry or actionable feedback. |
| Remote | Generate an SFTP key, copy and save its public key, authenticate with it while leaving the password blank, verify first-use pinning and changed-key rejection, verify FTP cleartext confirmation, HTTPS WebDAV on a custom port, HTTP warning, HTTPS WebDAV signed by a disposable user-installed CA succeeds for the matching hostname and fails for a hostname mismatch, direct WebDAV audio and video playback seeks through authenticated ranges without creating a complete Downloads or cache copy, a no-range server offers Download or Cancel, uploads and recursive downloads report filename, item count, bytes, and speed, and deleting a saved connection requires confirmation. |
| Layout and accessibility | Test portrait and landscape, large font and display sizes, List, Compact list, and Grid persistence, TalkBack labels, 48 dp touch targets, loading indicators, empty states, selection mode, view menus, details sheets, dialogs, and keyboard focus where available; on Android TV, verify List, Compact list, and Grid initially focus the first visible file, D-pad Up still reaches search, a short center or Enter press opens the item, and a held press enters selection exactly once without also opening it; in each layout, verify that the first selected item produces one haptic response and later selection changes do not, then apply a low-chroma gray dynamic palette and confirm the selection close button, count, actions, and overflow icon remain readable. |

## Useful device commands

Install the universal debug APK without clearing app data:

```bash
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Open Android's app-specific all-files access page through Voyager's own permission flow. For a dedicated test device only, the equivalent app-op can be controlled directly:

```bash
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT shell appops set com.voyagerfiles.debug MANAGE_EXTERNAL_STORAGE allow
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT shell appops set com.voyagerfiles.debug MANAGE_EXTERNAL_STORAGE deny
```

Capture a screenshot and UI hierarchy for a visual or accessibility review:

```bash
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT exec-out screencap -p > /tmp/voyager.png
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT shell uiautomator dump /sdcard/window.xml
adb -s DEVICE_ADDRESS:WIRELESS_DEBUGGING_PORT pull /sdcard/window.xml /tmp/voyager-window.xml
```

The opt-in `SmbDockerIntegrationTest` matrix checks optional encryption, required SMB3 encryption, and SMB2-only servers, including wrong-password rejection, discovery, direct shares, and exact read/write bytes. On September 8, 2026, the K60 SMB instrumentation also passed against a disposable Samba 4.12.2 server with `server min protocol = SMB3_00` and `smb encrypt = required`.
