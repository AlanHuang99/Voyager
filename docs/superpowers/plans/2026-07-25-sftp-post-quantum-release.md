# SFTP Post-Quantum Compatibility and Voyager 1.6.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore SFTP compatibility with hardened OpenSSH servers that offer only hybrid post-quantum key exchanges, then publish the already merged file-workflow features and this fix as Voyager 1.6.0.

**Architecture:** Keep the existing JSch-based SFTP provider and supply the supported cryptographic implementation that JSch checks at runtime. Prove compatibility against an ephemeral OpenSSH 10.3 container restricted to the exact ML-KEM and SNTRUP family reported in issue #36, then use the repository's tag-driven signed-release workflow and verify the published APK independently.

**Tech Stack:** Kotlin 2.1, Android API 26-35, JSch 2.28.5, Bouncy Castle 1.85, JUnit 4, Docker with OpenSSH 10.3, GitHub Actions, Android SDK build-tools.

## Global Constraints

- Android 8.0 API 26 remains the minimum.
- SFTP host-key trust-on-first-use and changed-key rejection remain unchanged.
- Hybrid post-quantum key exchange support must not enable deprecated SSH algorithms.
- Runtime dependencies must be open source, GPLv3-compatible, and acceptable to F-Droid.
- The Docker regression test must use production `SftpFileProvider`, production-generated keys, real SSH negotiation, and exact byte round trips.
- `versionCode` must increase from 13 to 14 and `versionName` must become `1.6.0`; the public tag must be exactly `v1.6.0`.
- Public release claims must be supported by local output or GitHub Actions output.
- Keystores, credentials, APKs, logs, and temporary server state must not be committed.

---

### Task 1: Reproduce and Fix Hybrid Post-Quantum SFTP Negotiation

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/sftp/SftpDockerIntegrationTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `SftpFileProvider`, `SshKeyGenerator`, and `ArchiveService`
- Produces: an opt-in Docker test that restricts OpenSSH to `mlkem768x25519-sha256,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com`
- Produces: runtime JSch and Bouncy Castle dependencies that make those algorithms available on Android and the JVM

- [ ] **Step 1: Restrict the real OpenSSH fixture to the reported algorithm family**

Create an executable temporary `/etc/sftp.d/` startup script in `SftpDockerIntegrationTest` that appends this server configuration before `sshd` starts:

```text
KexAlgorithms mlkem768x25519-sha256,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com
```

Mount the script read-only into the existing `atmoz/sftp:alpine` container and rename the test to state that it authenticates against a post-quantum-only server. Preserve generated-key authentication, upload/download byte comparison, listing, ZIP creation, and ZIP extraction.

- [ ] **Step 2: Run the Docker test and verify RED**

Run:

```bash
VOYAGER_RUN_DOCKER_TESTS=true ./gradlew --no-daemon --max-workers=2 testDebugUnitTest --tests com.voyagerfiles.data.remote.sftp.SftpDockerIntegrationTest --rerun-tasks --stacktrace
```

Expected: FAIL with `Algorithm negotiation fail` for `algorithmName="kex"` because the resolved Bouncy Castle 1.75 runtime cannot provide the configured hybrid algorithms.

- [ ] **Step 3: Add the minimal supported runtime dependencies**

Use the latest verified JSch patch release and explicitly override SMBJ's older transitive Bouncy Castle:

```kotlin
// SFTP/SSH - JSch fork (BSD/ISC)
implementation("com.github.mwiede:jsch:2.28.5")

// Hybrid post-quantum SSH key exchange - Bouncy Castle (MIT)
implementation("org.bouncycastle:bcprov-jdk18on:1.85")
```

Do not modify JSch's secure default algorithm list and do not re-enable SHA-1 or CBC algorithms.

- [ ] **Step 4: Run the Docker test and verify GREEN**

Run the same Docker command. Expected: the production provider negotiates one of the restricted hybrid algorithms, authenticates with the generated key, transfers the probe bytes exactly, and completes the remote ZIP round trip.

- [ ] **Step 5: Run focused SFTP, SMB, key-generation, and archive tests**

Run:

```bash
./gradlew --no-daemon --max-workers=2 testDebugUnitTest --tests 'com.voyagerfiles.data.remote.sftp.*' --tests 'com.voyagerfiles.data.remote.smb.*' --tests 'com.voyagerfiles.data.archive.*' --stacktrace
```

Expected: all focused tests pass with no cryptographic class-loading or provider conflicts.

### Task 2: Document and Fully Verify the Compatibility Fix

**Files:**
- Modify: `docs/TESTING.md`
- Modify: `docs/ARCHITECTURE.md`

**Interfaces:**
- Produces: reproducible testing instructions and an accurate SFTP cryptography boundary description

- [ ] **Step 1: Document the hardened-server fixture**

Update `docs/TESTING.md` so the opt-in Docker test explicitly states that the container accepts only ML-KEM/SNTRUP hybrid key exchanges and tests generated-key login, byte transfer, and remote archive operations. Update `docs/ARCHITECTURE.md` to state that JSch uses Bouncy Castle for hybrid post-quantum key exchange while retaining JSch's secure defaults.

- [ ] **Step 2: Run the complete local Gradle gate**

Run:

```bash
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace
```

Expected: `BUILD SUCCESSFUL`; release minification completes with no missing Bouncy Castle classes.

- [ ] **Step 3: Run the physical-device instrumentation suite**

Confirm the connected device and execute:

```bash
adb devices -l
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest --stacktrace
```

Expected: all instrumentation tests pass on the connected Android device.

- [ ] **Step 4: Smoke-test a minified release build on the device**

Temporarily create a non-debuggable release-derived build type with the `.smoke` application ID suffix and debug signing. Build it through R8 and resource shrinking, install it beside the production app, verify `versionCode` and `versionName` with `dumpsys package`, connect it to the hybrid-only OpenSSH fixture, then uninstall it and remove the temporary build type.

### Task 3: Prepare Voyager 1.6.0

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `fastlane/metadata/android/en-US/changelogs/14.txt`
- Create: `metadata/en-US/changelogs/14.txt`
- Modify: `README.md` only if dependency or feature wording is inaccurate after the final implementation

**Interfaces:**
- Produces: `versionCode = 14`
- Produces: `versionName = "1.6.0"`
- Produces: matching neutral release notes under the 500-character F-Droid limit

- [ ] **Step 1: Bump Gradle version fields**

Change only the version declarations:

```kotlin
versionCode = 14
versionName = "1.6.0"
```

- [ ] **Step 2: Add synchronized changelogs**

Describe the user-visible archive creation/extraction, PDF thumbnails, file-handler defaults, transfer progress, accessible SFTP public keys, and hardened OpenSSH compatibility. Prefix the fastlane copy with `Release 1.6.0:` and the repository metadata copy with `Voyager 1.6.0:`. Keep each file at or below 500 characters.

- [ ] **Step 3: Perform the clean version verification**

Run:

```bash
./gradlew clean assembleDebug
```

Use Android build tools to verify the debug APK reports version code 14 and version name 1.6.0. Re-run the complete local Gradle gate after the clean build.

### Task 4: Publish, Verify, and Synchronize the Release

**Files:**
- Modify after the tag commit is known: `metadata/com.voyagerfiles.yml`

**Interfaces:**
- Produces: merged GitHub PR fixing issue #36
- Produces: annotated tag `v1.6.0`
- Produces: signed GitHub release APKs and checksums through `.github/workflows/release.yml`
- Produces: in-repository F-Droid metadata for version code 14 using the actual release commit SHA

- [ ] **Step 1: Review and commit the scoped release change**

Inspect `git status`, the complete diff, dependency resolution, and ignored files. Stage only the plan, Docker test, Gradle dependency/version changes, documentation, and changelogs. Commit with:

```text
fix: support hardened SFTP servers
```

- [ ] **Step 2: Push and open a ready pull request**

Push `codex/fix-sftp-negotiation-release`, open a PR against `master`, include the root cause, red-green evidence, full local checks, and `Fixes #36`, then monitor all checks.

- [ ] **Step 3: Merge only after green CI and verify `master`**

Merge the PR without force-pushing, wait for the post-merge `master` Android CI run, and confirm its head SHA is the merge commit and its conclusion is `success`.

- [ ] **Step 4: Tag the verified merge commit**

Create an annotated `v1.6.0` tag at the verified `master` merge commit and push only that tag. Confirm the release workflow resolves `versionName = "1.6.0"` and `versionCode = 14`.

- [ ] **Step 5: Monitor the release workflow to completion**

Wait for the `Android Release and Pages` workflow. If it fails, inspect the complete failing log, fix the root cause on a new patch commit and tag only after the corrected tree is green. Do not overwrite a published tag.

- [ ] **Step 6: Verify public release assets independently**

Download every published APK and `SHA256SUMS.txt` into a temporary directory. Verify checksums, run `apksigner verify --print-certs`, compare the signing SHA-256 fingerprint with `AllowedAPKSigningKeys`, and use `aapt2 dump badging` to confirm version code 14 and version name 1.6.0. Confirm the GitHub release and Pages URLs resolve.

- [ ] **Step 7: Synchronize the in-repository F-Droid mirror**

On a new branch from the released `master`, add the 1.6.0 build block to `metadata/com.voyagerfiles.yml` with the actual release merge SHA and set `CurrentVersion: 1.6.0` and `CurrentVersionCode: 14`. Commit, push, merge through a second green PR, and do not create a manual fdroiddata merge request because `AutoUpdateMode: Version` and `UpdateCheckMode: Tags` handle routine releases.

- [ ] **Step 8: Recheck public state**

Confirm issue #36 is closed, no new open issues or PRs require action, both release and post-sync CI are green, `v1.6.0` is the latest release, and the working trees contain no uncommitted release artifacts.
