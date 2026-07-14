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

## Device instrumentation

Connect an Android device through USB or wireless debugging and confirm that `adb devices` reports it as `device` rather than `offline` or `unauthorized`.

```bash
adb connect 192.168.27.167:5555
ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest --stacktrace
```

Instrumentation installs the debug package `com.voyagerfiles.debug`. The current tests exercise thumbnail fallback rendering, unavailable-storage presentation, saved-connection delete confirmation, parent navigation after using search, and Android Keystore encryption.

## Protocol integration tests

The JVM suite starts isolated local FTP, SFTP, and WebDAV servers. It covers authentication, list and metadata operations, recursive copy and delete, download, bounded-memory streams, WebDAV transport URLs, and SFTP host-key pinning and rotation rejection.

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

## Manual Android regression matrix

Use disposable files and keep device orientation unlocked unless a case calls for a fixed orientation.

| Area | Cases |
| --- | --- |
| Permission | Deny full access, continue in limited mode, open a SAF tree, open a remote screen, return to Settings, then grant full access. |
| Storage | Browse internal storage, an available removable volume, and an unavailable or unmounted volume if one is present. |
| Navigation | Enter nested directories, use breadcrumbs and Back, switch sessions during a slow load, rotate the device, and confirm the latest location remains visible. |
| Search and filters | Search case-insensitively, combine search with each type filter, select all visible results, clear filters, and test an empty result. |
| File operations | Create, rename, copy, move, and delete files and directories; attempt a duplicate destination and a move into a descendant; verify the source survives failures. |
| Trash | Move a local file to Trash, restore it, create a restore conflict, permanently delete an entry, empty Trash, and repeat with Trash disabled. |
| Error recovery | Remove or unmount a location while browsing, deny a SAF operation, open an unsupported file, use a bad remote hostname, and verify retry or actionable feedback. |
| Remote | Verify SFTP first-use pinning and changed-key rejection, FTP cleartext confirmation, HTTPS WebDAV on a custom port, HTTP warning, large transfers, and a connection delete confirmation. |
| Layout and accessibility | Test portrait and landscape, large font and display sizes, TalkBack labels, 48 dp touch targets, loading indicators, empty states, selection mode, dialogs, and keyboard focus where available. |

## Useful device commands

Install the universal debug APK without clearing app data:

```bash
adb -s 192.168.27.167:5555 install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Open Android's app-specific all-files access page through Voyager's own permission flow. For a dedicated test device only, the equivalent app-op can be controlled directly:

```bash
adb -s 192.168.27.167:5555 shell appops set com.voyagerfiles.debug MANAGE_EXTERNAL_STORAGE allow
adb -s 192.168.27.167:5555 shell appops set com.voyagerfiles.debug MANAGE_EXTERNAL_STORAGE deny
```

Capture a screenshot and UI hierarchy for a visual or accessibility review:

```bash
adb -s 192.168.27.167:5555 exec-out screencap -p > /tmp/voyager.png
adb -s 192.168.27.167:5555 shell uiautomator dump /sdcard/window.xml
adb -s 192.168.27.167:5555 pull /sdcard/window.xml /tmp/voyager-window.xml
```
