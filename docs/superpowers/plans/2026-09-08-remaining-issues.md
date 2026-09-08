# Remaining issues implementation plan

**Goal:** Implement and verify every issue remaining after the September 8 GitHub triage.

**Architecture:** Retain the provider abstraction and Compose UI. Add explicit lifetime management for transfers and streaming descriptors, bounded preview/index services, and a separate privileged provider. Deliver in independently testable batches.

**Tech stack:** Kotlin, Android API 26+, Compose Material 3, coroutines, existing protocol libraries, JUnit, K60 Android instrumentation, and disposable protocol servers.

**Spec:** [Remaining issues design](../specs/2026-09-08-remaining-issues-design.md).

## Remote batch

### Task 1: #84

- [ ] Configure SMB encryption support in SmbFileProvider. Extend SmbDockerIntegrationTest with required SMB3 encryption and SMB2 compatibility cases; verify wrong-password rejection, discovery, and exact read/write bytes. Run `VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests '*SmbDockerIntegrationTest'` and K60 SMB instrumentation.

### Task 2: #56

- [ ] Add token acquire/release leases and idle sweep to PlaybackTokenStore and WebDavPlaybackProvider. Tests must open the same URI twice, close one descriptor, seek/read through the second, reopen after both close, expire idle entries, and revoke explicitly. Run token JVM tests and WebDavPlaybackProviderTest on K60.

### Task 3: #87

- [ ] Extend RemoteFileTapAction, FileUtils intent validation, and FileBrowserViewModel preparation to WebDAV documents. Test PDF seek/open and explicit range-failure fallback with no Downloads copy.

### Task 4: #80

- [ ] Add saved-host fingerprint inspection and targeted removal in the SFTP provider/editor. Test host/port isolation, unknown hosts, and rejection before confirmed reset.

### Task 5: #66

- [ ] Add focused keyboard handling to the protocol menu and test D-pad/Enter choice, port updates, Back, and focus restoration.


## Transfer batch

### Task 6: #71/#78

- [ ] Add foreground transfer service, retained operation owner/state, manifest permissions, notification progress/cancel, and UI cancel. Test Activity recreation/backgrounding, cooperative cancellation, cleanup, and source preservation on the K60 and real servers.

### Task 7: #72/#79

- [ ] Label counts as completed, preserve terminal status, and cover first/final/failed/nested items. Update progress UI tests and supersede PR #79 after merging.

### Task 8: #77

- [ ] Add conflict decisions and transactional sibling staging/rollback through FileOperationCoordinator. Test Replace/Skip/Cancel/apply-to-all, self-copy, type conflict, failed write/promotion, and move source preservation across local/SAF/remote providers.


## Browser and file-action batch

### Task 9: #69

- [ ] Derive bookmark label/icon from saved state; add accessible Home removal; test both entry points and asynchronous persistence.

### Task 10: #74

- [ ] Wire pull-to-refresh to the directory load guard; verify top-of-list gesture, failures, list/grid, and selection.

### Task 11: #88

- [ ] Implement long-press drag selection with stable identities and edge auto-scroll; test forward/reverse drags, grid/list, and filtered results.

### Task 12: #76

- [ ] Add directional navigation transitions while preserving opaque theme backgrounds and animation-scale behavior; rerun NavigationBackgroundTest and device navigation.

### Task 13: #62/#83/#70

- [ ] Add bounded video/OOXML preview loaders and reuse preview rendering for Trash payloads. Verify malformed/absent previews, MIME fallback, restore/delete invalidation, and list/grid rendering.

### Task 14: #68

- [ ] Add all-storage category browsing with explicit storage coverage, stale-entry handling, and permission behavior. Preserve folder/bookmark navigation and test mixed file types across directories.

### Task 15: #73

- [ ] Add cancellable duplicate scan, content hashing, explicit selection, and pre-delete revalidation. Test equal-size distinct files, changed files, unreadable files, and preserving at least one copy.

### Task 16: #75

- [ ] Add pinned local-folder shortcuts and validated launch handling. Test removed folders, permission denial, and repeated launch on K60.

### Task 17: #64

- [ ] Add local/SAF audio ringtone and notification-tone actions using Android MediaStore/settings. Test denied access, unsupported input, and restoring the device's original tone after verification.


## Root and infrastructure batch

### Task 18: #67

- [ ] Implement opt-in root provider, robust quoted commands, bounded streaming, and permission/error handling. Verify disposable privileged files on the K60 where root is available; ordinary local browsing must remain unprivileged.

### Task 19: #82

- [ ] Enable GitHub Discussions, verify the destination and categories, document feedback links, and close the issue with the working link.

### Task 20: #86

- [ ] Add Crowdin Android resource mapping and workflow documentation, extract hardcoded Home labels, validate locale/placeholder mapping, and connect an actual project using repository secrets. Record any missing external account requirement explicitly.


## Integration and delivery

- [ ] Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` and complete K60 instrumentation after each shared-behavior batch.
- [ ] Exercise the minified release build on the K60 using a separate test package or the correct signing configuration; preserve the user's production installation and data.
- [ ] Update architecture, testing, translation, and README documentation to match implemented behavior.
- [ ] Open reviewable PRs, wait for CI, merge verified changes, update/close corresponding issues, and keep local master synchronized.
