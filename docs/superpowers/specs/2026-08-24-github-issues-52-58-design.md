# GitHub issues 52–58 and pull request 54 design

## Scope

Resolve or explicitly disposition every Voyager issue and pull request open on 2026-08-24, then publish Voyager 1.8.0 after the merged tree passes repository, CI, and K60 verification. The implementation covers pull request #54 and issues #53, #55, #56, #57, and #58. Issue #52 will be closed as out of scope because it requests a security-sensitive server product rather than a file-browser client capability.

The work is divided into focused change sets so certificate policy, Android interaction behavior, remote-provider behavior, and localization can be tested and reviewed independently. Contributor authorship from pull request #54 must remain intact. Public issue and release text must describe only behavior that has been built and verified.

## Current-state evidence

The repository baseline is clean at commit `10ffcbfb75`. `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace` succeeds, and all 53 existing instrumentation tests pass on the K60 running Android 16/API 36. The open reports are therefore isolated behavior gaps and feature requests rather than symptoms of a generally failing build.

The current code maps to the reports as follows:

- Pull request #54 adds a Network Security Configuration that trusts user-installed and system certificate authorities. Voyager currently targets API 35, so user-installed certificate authorities are not trusted by default.
- Issue #57 reaches the search field first during two-dimensional focus discovery because the browser does not assign initial TV focus. `combinedClickable` supplies pointer long-press behavior but does not turn a held remote select key into Voyager's selection action on the reported device.
- Issue #58 explicitly maps APK files to the generic Android icon and has no package-archive icon loader.
- Issue #55 requires an SMB share name in both form validation and `SmbFileProvider.ensureConnected()`, so the provider cannot expose a server-level virtual root.
- Issue #56 routes every remote-file tap to `downloadFile()` and permits direct Android view intents only for local and SAF files.
- Issue #53 has only `app_name` in Android resources while the rest of the user-visible text is embedded in Kotlin.
- Issue #52 has no corresponding service, authentication, foreground-service lifecycle, virtual filesystem, or server hardening layer. Voyager is currently a network client only.

## Change set 1: user certificate authorities

Pull request #54's contributor commit will be retained with its original author. Before applying or merging it, a focused manifest/resource contract test will fail against the current tree because no Network Security Configuration is declared. The contributor change will then make the test pass by declaring `android:networkSecurityConfig` and trusting both `user` and `system` anchors while preserving Voyager's explicit cleartext support for user-configured FTP and HTTP WebDAV.

The configuration is intentionally application-wide because Voyager connects to arbitrary user-specified WebDAV hosts rather than a fixed domain list. It does not disable hostname verification, accept arbitrary leaf certificates, or introduce a custom trust manager. Documentation will explain that a user-installed authority can authenticate HTTPS WebDAV servers and that installing an authority changes the trust decision deliberately.

## Change set 2: Android TV navigation and APK thumbnails

### TV focus and remote long press

Browser composition will accept a TV-mode value whose production default is derived from `Configuration.UI_MODE_TYPE_TELEVISION`. When a TV browser location finishes loading and contains visible items, a `FocusRequester` attached to the first visible item will request focus. This prevents initial D-pad navigation from entering the search field and opening the keyboard. Search remains reachable by navigating upward, so the fix does not remove keyboard-based filtering.

List and grid rows will share a small D-pad select-key state machine. A normal Enter, numpad Enter, or D-pad center press continues to invoke the existing click action. A repeated key-down event from a held select key invokes the existing long-click action once, consumes the matching key-up event, and suppresses the ordinary click. Directional keys and touch behavior remain unchanged. The state machine will be pure Kotlin so repeat, release, cancellation, and duplicate-repeat behavior can be tested without relying only on a particular remote.

Compose instrumentation will render both list and grid paths in forced TV mode, verify initial file focus, verify that the search field does not gain focus on entry, and exercise the normal and long-select action wiring. K60 verification will inject real D-pad key events into the installed debug app and confirm selection behavior, while the forced-TV instrumentation covers the configuration branch that a phone cannot naturally enter.

### APK thumbnails

A dedicated `ApkThumbnailLoader` will use Android `PackageManager.getPackageArchiveInfo()` for readable local APK paths, assign the archive path to both `ApplicationInfo.sourceDir` and `ApplicationInfo.publicSourceDir`, and load the packaged application icon. Results will be cached by canonical path, byte size, and last-modified timestamp so scrolling does not repeatedly parse unchanged archives. Loading will run off the main thread and produce a bounded bitmap sized for the requested Compose thumbnail.

Local APKs with valid icons render the packaged icon in list and grid layouts. Corrupt APKs, missing files, directories, SAF files, and network files retain the generic Android icon. Voyager will not copy an entire SAF or remote APK into cache merely to obtain an icon. Tests will create a disposable valid APK fixture from a built test artifact, assert that the archive icon path renders, and assert fallback behavior for invalid and non-local files.

## Change set 3: SMB share discovery

An SMB connection with a nonblank share name will keep its existing direct-share behavior. A connection with a blank share name will enter discovery mode at `/`, where `SmbFileProvider` exposes a read-only virtual directory containing discoverable disk-tree shares. Navigating into `/<share>` connects to that share and maps the remaining path to the existing `DiskShare` operations. The virtual root itself rejects create, rename, delete, copy, move, and stream operations with an actionable error; normal operations inside a selected share remain available.

Discovery will use Rapid7 `dcerpc` 0.12.13 and the Server Service `NetrShareEnum` level-1 call over the authenticated SMB session's `IPC$` pipe. Only disk-tree share types will be displayed. The dependency's transitive older SMBJ and Bouncy Castle artifacts will be excluded or resolved to Voyager's pinned versions so discovery cannot silently downgrade the existing client or add a second cryptography provider. Dependency resolution, licenses, APK contents, and APK size deltas will be inspected before delivery.

The connection form will label the SMB share as optional and explain that leaving it blank browses available shares. Validation will continue to require a valid host and port. Provider tests will cover direct-share compatibility, virtual-root listings, path splitting, read-only root enforcement, connection cleanup, and an authenticated disposable Samba integration fixture that returns at least one disk share.

If the maintained released RPC artifact proves binary-incompatible with Voyager's pinned SMBJ during the mandatory compile and integration checks, the change set stops rather than downgrading SMBJ. In that case issue #55 will receive the exact compatibility evidence and remain open instead of shipping an unmaintained or insecure dependency combination.

## Change set 4: direct WebDAV media playback

WebDAV audio and video taps will attempt direct external playback by default. Other WebDAV files and all files from SFTP, FTP, and SMB retain the existing download-on-tap behavior. The existing explicit Download selection action remains available for WebDAV media, so users can choose a local copy.

Voyager will expose playback through a new read-only, non-exported `ContentProvider` that grants individual opaque content URIs to the selected external application. A URI contains a cryptographically random, expiring token and never contains the WebDAV URL, path, username, password, or connection identifier. The provider supports `OpenableColumns.DISPLAY_NAME`, `OpenableColumns.SIZE`, and MIME type queries and rejects write modes, unknown tokens, expired tokens, and ungranted callers.

Each token owns a dedicated WebDAV streaming source rather than borrowing the mutable browser session. The source performs authenticated HTTP range reads through OkHttp and backs Android's proxy file descriptor callbacks, allowing compatible media players to seek without staging the full file. Voyager probes range support before launching the external player. If the server does not support usable byte ranges or the probe fails, Voyager reports that direct playback is unavailable and offers the existing download path instead of exposing credentials or silently downloading the entire file.

Tokens expire after a short inactivity window and are removed when playback closes or registration fails. Provider callbacks run on background threads, honor close/cancellation, bound every read, and close HTTP responses deterministically. Unit tests will cover range headers, 206 validation, authentication, exact byte slices, server rejection, and cancellation. Instrumentation will cover URI grants, metadata, read-only enforcement, token expiry, exact streamed bytes, direct-intent MIME type, and the fallback decision. K60 testing will play and seek within disposable audio/video fixtures served by a real host-side WebDAV endpoint.

## Change set 5: localization foundation

Every static user-visible label, action, dialog, content description, warning, empty state, and error message will move into Android string resources. Counts will use plural resources rather than English suffix construction. Protocol names and the Voyager brand will be marked or treated as non-translatable where appropriate.

ViewModel-originated messages will use a structured `UiText` representation containing either a string resource with formatting arguments or a genuinely dynamic value. Compose resolves `UiText` through the current `Resources`, which prevents the ViewModel from freezing English text into state and keeps formatting locale-aware. Validation and warning models will expose resource identifiers rather than English sentences. Enum display labels used by UI will be resolved at the UI boundary rather than stored as English in data models.

The initial source language remains English because issue #53 does not identify a target locale and translations must not be fabricated. A translation guide will document resource naming, placeholders, plurals, RTL expectations, and validation commands so the reporter and other contributors can add locale directories safely. Completing this foundation resolves the architectural blocker described in #53; the issue response will explicitly invite the offered translation contribution.

Tests will verify resource completeness, formatting arguments, plural behavior for zero, one, and many, UI text resolution under a non-English formatting locale, and absence of the known hardcoded user-visible patterns from production Compose sources. The full lint and instrumentation gates will catch missing or invalid resources.

## Issue 52 disposition

Issue #52 requests that Voyager host SMB, SFTP, and FTP servers for phone storage. This is not an extension of the existing provider clients. A responsible implementation would require authenticated server protocols, a foreground-service lifecycle, durable host keys and credentials, Android storage and SAF authorization boundaries, network-interface binding controls, notification and shutdown behavior, resource limits, audit logging, and protocol-specific security maintenance. The request emphasizes SMB, for which Voyager has no compatible maintained Android server dependency.

The issue will be labeled `enhancement` and `wontfix`, then closed with a neutral explanation that Voyager remains a file-browser client and that exposing phone storage as network services is outside its current security boundary. The response will not claim that such an application is impossible, only that it is a separate product and is not safe to bolt onto this client during an issue-maintenance batch.

## Error handling and security invariants

- User certificate support changes trust anchors only; hostname verification and normal TLS chain validation remain enabled.
- D-pad long press invokes exactly one selection transition and never also opens the item on release.
- APK parsing never executes package code and never turns an unreadable archive into a crash.
- SMB discovery never treats the virtual server root as a writable filesystem location and never downgrades the pinned SMB or cryptography libraries.
- WebDAV playback URIs reveal no credentials or remote identifiers, allow reads only, expire, and grant access only through Android URI permissions.
- Unknown, missing, and zero file sizes remain distinct throughout streaming and localization formatting.
- Dynamic exception details are shown only alongside a localized stable message and are not treated as translatable source text.

## Delivery sequence

1. Record this design and a test-first implementation plan.
2. Land the user-CA contribution with its regression coverage and approve its GitHub Actions run.
3. Land the TV navigation and APK thumbnail change set, closing #57 and #58.
4. Land SMB discovery after dependency and Samba integration verification, closing #55 only if the compatibility gate passes.
5. Land direct WebDAV media playback, closing #56.
6. Land the localization foundation and contributor guide, closing #53 with an invitation for real translations.
7. Close #52 with the approved `enhancement` and `wontfix` disposition.
8. Rebase or merge each verified change set into `master`, wait for required GitHub checks, and confirm the final master tree locally and on the K60.
9. Prepare version 1.8.0 with synchronized Gradle, changelog, Fastlane/F-Droid metadata, README, architecture, testing, and release documentation.
10. Run the complete local gate, complete instrumentation suite, minified release build, dependency inspection, APK metadata checks, installation, and K60 smoke test.
11. Push the signed-off release commit, create and push tag `v1.8.0`, wait for the release workflow, verify published APK assets and GitHub Pages, and publish factual release notes linked to the resolved issues.

No pull request is merged, issue is closed, or release is published until its relevant verification evidence is fresh and successful.
