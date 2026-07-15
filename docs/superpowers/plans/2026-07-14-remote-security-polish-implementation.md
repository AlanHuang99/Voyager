# Remote Security and Daily-Use Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the highest-risk remote browsing behaviors, improve operation feedback and accessibility, and align user-facing documentation with verified app behavior.

**Architecture:** Keep the current provider interfaces and protocol libraries. Add small protocol-specific configuration helpers, stream through provider-owned handles where each library supports it, exclude sensitive local state from Android backup, and express UI behavior through testable presentation models.

**Tech Stack:** Kotlin, Coroutines, Android Keystore and backup rules, JSch, Commons Net, SMBJ, Sardine, Jetpack Compose Material 3, JUnit 4.

## Global Constraints

- Add no runtime dependency unless an existing protocol library cannot support a required behavior.
- Do not silently weaken TLS or SSH host verification.
- Do not expose passwords, private-key paths, or connection secrets in logs, errors, tests, or backups.
- Keep existing saved connections readable while tightening storage and backup behavior.
- Do not claim a protocol is secure when the user selected cleartext FTP, HTTP WebDAV, or SMB without transport encryption.
- Keep prose paragraphs on one physical line.

---

### Task 1: Keep connection secrets out of Android cloud and device-transfer backups

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/test/java/com/voyagerfiles/security/BackupRulesTest.kt`

- [ ] **Step 1: Add a failing resource test that requires the Room database and preference state to be excluded from backup**
- [ ] **Step 2: Add API 31 data-extraction rules and legacy full-backup rules that exclude `voyager.db`, its sidecars, DataStore files, SSH keys, and known-host state**
- [ ] **Step 3: Reference both rule files from the application manifest and retain `allowBackup` for nonsensitive future state**
- [ ] **Step 4: Run `./gradlew testDebugUnitTest lintDebug` and commit as `fix: exclude connection secrets from device backups`**

### Task 2: Make WebDAV transport explicit

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/model/RemoteConnection.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/webdav/WebDavFileProvider.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavFileProviderTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/local/AppDatabase.kt`

- [ ] **Step 1: Write failing URL tests for HTTPS on a custom port and explicit HTTP**
- [ ] **Step 2: Add an explicit WebDAV HTTPS setting with a Room migration that defaults existing connections to HTTPS when their port is 443 and otherwise preserves their previous inferred behavior**
- [ ] **Step 3: Show the scheme in the connection editor and require a visible confirmation before saving cleartext WebDAV**
- [ ] **Step 4: Run focused provider and migration tests, then commit as `fix: configure WebDAV transport explicitly`**

### Task 3: Verify SFTP hosts with app-owned known-host state

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/repository/FileProviderFactory.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/sftp/SftpFileProvider.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/sftp/SftpFileProviderTest.kt`

- [ ] **Step 1: Add failing tests proving a changed host key is rejected and a first-seen key is persisted**
- [ ] **Step 2: Configure JSch with an app-private `known_hosts` file, `StrictHostKeyChecking=ask`, and a first-use acceptance callback that never accepts a changed key**
- [ ] **Step 3: Convert host-key failures into a specific actionable connection error without exposing key material**
- [ ] **Step 4: Run the embedded SFTP integration suite and commit as `fix: verify SFTP server host keys`**

### Task 4: Remove whole-file buffering from FTP and SMB streams

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/ftp/FtpFileProvider.kt`
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/smb/SmbFileProvider.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/ftp/FtpFileProviderTest.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/smb/SmbFileProviderTest.kt`

- [ ] **Step 1: Add focused tests that transfer payloads larger than the provider buffer and verify resources close correctly after success and failure**
- [ ] **Step 2: Return filter streams backed by FTP data connections and complete the pending FTP command on close**
- [ ] **Step 3: Return SMBJ input and output streams that own and close their file handles instead of copying through byte arrays**
- [ ] **Step 4: Reuse streaming primitives in provider-local copy operations where server-side copy is unavailable**
- [ ] **Step 5: Run protocol integration tests and commit as `perf: stream FTP and SMB file transfers`**

### Task 5: Add consistent operation and recovery feedback

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/ConnectionsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/OperationMessageTest.kt`

- [ ] **Step 1: Add pure tests for operation progress labels, partial failures, and permanent-delete wording**
- [ ] **Step 2: Disable duplicate destructive actions while an operation is active and show an indeterminate progress surface with an accessible state description**
- [ ] **Step 3: Distinguish moved-to-Trash, permanently deleted, conflict, permission, offline, and generic provider errors with actionable messages**
- [ ] **Step 4: Require confirmation before deleting a saved connection and make the consequence explicit**
- [ ] **Step 5: Run focused UI model tests and commit as `fix: make file operation feedback actionable`**

### Task 6: Complete accessibility, documentation, and visual regression review

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/ConnectionsScreen.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/SettingsScreen.kt`
- Modify: `README.md`
- Modify: `docs/TESTING.md`
- Modify: `docs/ARCHITECTURE.md`
- Add or update: `screenshots/*.png`

- [ ] **Step 1: Audit touch targets, content descriptions, traversal order, font scaling, landscape width, loading states, empty states, and error retry actions on the K60**
- [ ] **Step 2: Fix verified accessibility and responsive-layout defects using existing Material 3 tokens and components**
- [ ] **Step 3: Remove unsupported claims from the README and document search, filters, Trash, limited mode, storage-volume behavior, remote security boundaries, and test commands exactly as implemented**
- [ ] **Step 4: Capture representative Home, browser, search, selection, Trash, permission-denied, and error screenshots after implementation**
- [ ] **Step 5: Run `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug assembleRelease` and inspect release artifacts**
- [ ] **Step 6: Commit as `docs: align Voyager documentation and screenshots`**
