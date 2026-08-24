# SMB Share Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an SMB connection with a blank share browse the authenticated server's disk shares as a read-only virtual root while preserving existing direct-share behavior.

**Architecture:** A small path resolver distinguishes the virtual server root from a selected share and maps paths below `/<share>` to the current `DiskShare` implementation. An injected discovery interface wraps Rapid7 DCE/RPC `NetrShareEnum` level 1 over the authenticated session's `IPC$` pipe. Pure unit tests cover path and root rules; an opt-in disposable Samba container covers the released RPC artifact and SMBJ binary compatibility.

**Tech Stack:** Kotlin, SMBJ 0.13.0, Rapid7 dcerpc 0.12.13, JUnit 4, Docker Samba, Gradle dependency reports.

**Spec:** `docs/superpowers/specs/2026-08-24-github-issues-52-58-design.md`

## Global Constraints

- Keep `com.hierynomus:smbj:0.13.0` and `org.bouncycastle:bcprov-jdk18on:1.85` pinned.
- Add `com.rapid7.client:dcerpc:0.12.13` without its transitive SMBJ or Bouncy Castle artifacts.
- A nonblank share retains the current direct-share path model.
- A blank share exposes only disk-tree shares under `/`.
- The virtual root rejects create, rename, delete, copy, move, input-stream, output-stream, and metadata operations.
- No issue is closed if the released RPC artifact fails compilation or authenticated Samba integration.

---

### Task 1: Optional SMB share validation and copy

**Files:**
- Modify: `app/src/test/java/com/voyagerfiles/ui/components/ConnectionFormValidatorTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/ConnectionFormValidator.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt`

**Interfaces:**
- Produces: valid SMB form state for blank `shareName` and explicit discovery-mode supporting text.

- [ ] **Step 1: Replace the old requirement test with a failing optional-share test**

```kotlin
@Test
fun acceptsBlankShareNameForSmbDiscovery() {
    val result = ConnectionFormValidator.validate(ConnectionProtocol.SMB, "server.example", "445", "")
    assertTrue(result.isValid)
    assertNull(result.shareNameError)
}
```

- [ ] **Step 2: Run and verify the red state**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.ConnectionFormValidatorTest --stacktrace`

Expected: FAIL because validation still returns `Share name is required for SMB`.

- [ ] **Step 3: Remove only the blank-share validation error**

```kotlin
val shareNameError: String? = null
```

Keep the property until the localization task replaces string-valued validation with resource IDs. Change the SMB field label to `Share name (optional)` and its supporting text to `Leave blank to browse available shares` when no validation error is present.

- [ ] **Step 4: Run focused tests and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.components.ConnectionFormValidatorTest --stacktrace`

Expected: PASS.

```bash
git add app/src/main/java/com/voyagerfiles/ui/components/ConnectionFormValidator.kt app/src/main/java/com/voyagerfiles/ui/components/Dialogs.kt app/src/test/java/com/voyagerfiles/ui/components/ConnectionFormValidatorTest.kt
git commit -m "feat: allow SMB connections without a share"
```

### Task 2: Virtual-root path model

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/data/remote/smb/SmbBrowsePath.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/remote/smb/SmbBrowsePathTest.kt`

**Interfaces:**
- Produces: `SmbBrowsePath.VirtualRoot` and `SmbBrowsePath.Share(name, relativePath)` through `SmbBrowsePath.parse(path)`.
- Consumes later: all `SmbFileProvider` operations in discovery mode.

- [ ] **Step 1: Write failing path tests**

```kotlin
class SmbBrowsePathTest {
    @Test fun rootIsVirtualAndSharePathsSplitOnce() {
        assertEquals(SmbBrowsePath.VirtualRoot, SmbBrowsePath.parse("/"))
        assertEquals(SmbBrowsePath.Share("Media", ""), SmbBrowsePath.parse("/Media"))
        assertEquals(SmbBrowsePath.Share("Media", "Films\\clip.mp4"), SmbBrowsePath.parse("/Media/Films/clip.mp4"))
    }

    @Test fun rejectsTraversalAndEmptyInteriorSegments() {
        listOf("", "Media", "/../Media", "/Media/../secret", "/Media//file")
            .forEach { path -> assertFailsWith<IllegalArgumentException> { SmbBrowsePath.parse(path) } }
    }

    @Test fun parentReturnsVirtualRootAtShareBoundary() {
        assertEquals("/", SmbBrowsePath.parentOf("/Media"))
        assertEquals("/Media", SmbBrowsePath.parentOf("/Media/Films"))
        assertNull(SmbBrowsePath.parentOf("/"))
    }
}
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbBrowsePathTest --stacktrace`

Expected: compilation FAIL because `SmbBrowsePath` does not exist.

- [ ] **Step 3: Implement strict parsing**

```kotlin
internal sealed interface SmbBrowsePath {
    data object VirtualRoot : SmbBrowsePath
    data class Share(val name: String, val relativePath: String) : SmbBrowsePath

    companion object {
        fun parse(path: String): SmbBrowsePath {
            require(path.startsWith('/')) { "SMB paths must start with /" }
            if (path == "/") return VirtualRoot
            val segments = path.removePrefix("/").split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid SMB path" }
            return Share(segments.first(), segments.drop(1).joinToString("\\"))
        }

        fun parentOf(path: String): String? = when (val parsed = parse(path)) {
            VirtualRoot -> null
            is Share -> if (parsed.relativePath.isEmpty()) "/" else path.substringBeforeLast('/').ifEmpty { "/" }
        }
    }
}
```

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbBrowsePathTest --stacktrace`

Expected: PASS.

```bash
git add app/src/main/java/com/voyagerfiles/data/remote/smb/SmbBrowsePath.kt app/src/test/java/com/voyagerfiles/data/remote/smb/SmbBrowsePathTest.kt
git commit -m "feat: model the SMB server virtual root"
```

### Task 3: Released DCE/RPC discovery adapter

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/voyagerfiles/data/remote/smb/SmbShareDiscovery.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/remote/smb/SmbShareDiscoveryTest.kt`

**Interfaces:**
- Produces: `data class SmbDiscoveredShare(name: String, remark: String?)`, `fun interface SmbShareDiscovery`, and `DceRpcSmbShareDiscovery`.
- Consumes: authenticated `com.hierynomus.smbj.session.Session`.

- [ ] **Step 1: Add the dependency with explicit exclusions**

```kotlin
implementation("com.rapid7.client:dcerpc:0.12.13") {
    exclude(group = "com.hierynomus", module = "smbj")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

- [ ] **Step 2: Write failing type-filter tests against an internal mapper**

```kotlin
@Test fun keepsDiskTreesAndRemovesSpecialFlags() {
    val input = listOf(
        RawSmbShare("Media", 0x00000000, "files"),
        RawSmbShare("Hidden$", 0x80000000.toInt(), "admin disk"),
        RawSmbShare("Printer", 0x00000001, "printer"),
        RawSmbShare("IPC$", 0x00000003, "ipc"),
    )
    assertEquals(listOf("Hidden$", "Media"), diskShares(input).map { it.name })
}
```

The lower 16 bits determine the base type, and `0` is a disk tree; high special bits do not turn a disk share into a non-disk type.

- [ ] **Step 3: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbShareDiscoveryTest --stacktrace`

Expected: compilation FAIL because discovery mapping types do not exist.

- [ ] **Step 4: Implement the adapter with the verified released API**

```kotlin
internal fun interface SmbShareDiscovery {
    fun discover(session: Session): List<SmbDiscoveredShare>
}

internal object DceRpcSmbShareDiscovery : SmbShareDiscovery {
    override fun discover(session: Session): List<SmbDiscoveredShare> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).getShares1()
            .map { RawSmbShare(it.netName, it.type, it.remark) }
            .let(::diskShares)
    }
}
```

Define `RawSmbShare` internally and implement `diskShares` as `(type and 0xFFFF) == 0`, nonblank trimmed names, case-insensitive distinct names, and case-insensitive sorted output. The released `RPCTransport` does not expose `close()`, so the provider calls discovery once per authenticated session, caches the resulting virtual-root entries, and relies on `Session.close()` during provider disconnect to close the `IPC$` tree and pipe handles. The Docker test must exercise repeated provider connect/disconnect cycles to verify that lifecycle.

- [ ] **Step 5: Compile, test, and inspect dependency resolution**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbShareDiscoveryTest assembleDebug --stacktrace`

Run: `./gradlew app:dependencyInsight --configuration debugRuntimeClasspath --dependency smbj && ./gradlew app:dependencyInsight --configuration debugRuntimeClasspath --dependency bcprov-jdk18on && ./gradlew app:dependencyInsight --configuration debugRuntimeClasspath --dependency dcerpc`

Expected: tests pass; resolved SMBJ is exactly 0.13.0, Bouncy Castle exactly 1.85, and dcerpc exactly 0.12.13.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/voyagerfiles/data/remote/smb/SmbShareDiscovery.kt app/src/test/java/com/voyagerfiles/data/remote/smb/SmbShareDiscoveryTest.kt
git commit -m "feat: discover SMB disk shares over RPC"
```

### Task 4: Provider discovery mode and root protection

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/smb/SmbFileProvider.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/remote/smb/SmbDiscoveryModeTest.kt`

**Interfaces:**
- Consumes: `SmbBrowsePath` and `SmbShareDiscovery`.
- Produces: `SmbFileProvider(connection, shareDiscovery = DceRpcSmbShareDiscovery)` with direct and discovery modes.

- [ ] **Step 1: Write failing provider rule tests through injectable session/share seams**

Construct the provider with a fake discovery returning `Media` and `Documents`. Assert discovery `listFiles("/")` returns two directory `FileItem`s with paths `/Media` and `/Documents`, source `SMB`, size `0`, and remarks excluded from paths. Assert `exists("/")` is true and `getFileInfo("/")` returns a directory representing the virtual server root. Assert a nonblank configured share never calls discovery. Assert every mutating/stream operation aimed at `/` fails with `SmbVirtualRootException`.

```kotlin
private val forbidden = listOf<suspend (SmbFileProvider) -> Result<*>>(
    { it.createDirectory("/", "new") },
    { it.createFile("/", "new.txt") },
    { it.delete("/") },
    { it.rename("/", "renamed") },
    { it.copy("/", "/Media") },
    { it.move("/", "/Media") },
    { it.getInputStream("/") },
    { it.getOutputStream("/") },
)
```

- [ ] **Step 2: Run and verify the red state**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbDiscoveryModeTest --stacktrace`

Expected: FAIL because blank shares are rejected by `ensureConnected()` and no virtual-root exception exists.

- [ ] **Step 3: Split authenticated-session and selected-share connection state**

Replace `ensureConnected()` with `ensureSession()` and `ensureShare(name)`. Track `activeShareName`; close the prior `DiskShare` before switching names. Determine mode once with `connection.shareName?.trim().orEmpty()`.

```kotlin
private val configuredShare = connection.shareName?.trim().orEmpty()
private val isDiscoveryMode get() = configuredShare.isEmpty()

private fun resolve(path: String): ResolvedSharePath = if (!isDiscoveryMode) {
    ResolvedSharePath(configuredShare, toSmbPath(path))
} else when (val parsed = SmbBrowsePath.parse(path)) {
    SmbBrowsePath.VirtualRoot -> throw SmbVirtualRootException()
    is SmbBrowsePath.Share -> ResolvedSharePath(parsed.name, parsed.relativePath)
}
```

For the first `listFiles("/")` call in a discovery-mode authenticated session, call `ensureSession()`, discovery, cache the immutable sorted result, and map shares to virtual directories. Later root refreshes reuse that session result; `disconnect()` clears it so reconnecting performs a fresh enumeration. Return virtual directory metadata for `/` and `/<share>`, and true from `exists` for those paths. For all other methods call `resolve(path)` before accessing a `DiskShare`. Reject delete, rename, stream, copy-source, and move-source operations whose resolved relative path is empty because a share entry is not a writable filesystem item. Allow create and copy/move destinations at `/<share>` because they write inside the selected share root. Reject any destination of `/`. `disconnect()` closes the active share, session, connection, and client exactly once and clears every field.

- [ ] **Step 4: Run provider unit and existing integration tests**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.remote.smb.*' --stacktrace`

Expected: pure virtual-root tests pass. Existing environment-driven direct-share integration remains skipped without credentials or passes unchanged when configured.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/data/remote/smb/SmbFileProvider.kt app/src/test/java/com/voyagerfiles/data/remote/smb/SmbDiscoveryModeTest.kt
git commit -m "feat: browse SMB servers without a configured share"
```

### Task 5: Disposable authenticated Samba compatibility gate

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/data/remote/smb/SmbDockerIntegrationTest.kt`
- Modify: `docs/TESTING.md`
- Modify: `docs/ARCHITECTURE.md`

**Interfaces:**
- Consumes: `SmbFileProvider` discovery and direct share operations.
- Produces: opt-in `VOYAGER_RUN_DOCKER_TESTS=true` compatibility test.

- [ ] **Step 1: Write the opt-in Docker integration test**

Start `dperson/samba@sha256:66088b78a19810dd1457a8f39340e95e663c728083efa5fe7dc0d40b2478e869` with a random loopback port, user `voyager`, password `voyager-test`, and writable mounted shares `media` and `documents`. Poll discovery mode until ready, assert both share names are returned, enter `/media`, write and read exact probe bytes, return to `/`, enter `/documents`, and assert it is independently accessible. Always force-remove the unique container in `finally`.

```kotlin
assumeTrue(System.getenv("VOYAGER_RUN_DOCKER_TESTS") == "true")
docker("run", "--detach", "--name", containerName, "--publish", "127.0.0.1::445", "--mount", "type=bind,src=${media.absolutePath},dst=/media", "--mount", "type=bind,src=${documents.absolutePath},dst=/documents", SAMBA_IMAGE, "-u", "$USERNAME;$PASSWORD", "-s", "media;/media;yes;no;no;$USERNAME", "-s", "documents;/documents;yes;no;no;$USERNAME")
```

- [ ] **Step 2: Run the mandatory compatibility test**

Run: `VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbDockerIntegrationTest --stacktrace`

Expected: PASS with authenticated RPC enumeration and exact read/write bytes. If compilation or runtime RPC linkage fails, stop this change set, revert its unmerged implementation commits only, record the exact error for issue #55, and leave #55 open as required by the specification.

- [ ] **Step 3: Inspect release runtime dependencies and APK size**

Run: `./gradlew app:dependencies --configuration releaseRuntimeClasspath > /tmp/voyager-release-runtime.txt && rg -n 'dcerpc|smbj|bcprov|commons-io|commons-lang3|guava' /tmp/voyager-release-runtime.txt`

Run: `./gradlew assembleRelease --stacktrace && find app/build/outputs/apk/release -name '*.apk' -printf '%f %s bytes\n' | sort`

Expected: one version each of SMBJ and Bouncy Castle, no duplicate cryptography provider, and a recorded APK size delta for release notes review.

- [ ] **Step 4: Document and commit**

Document the discovery-mode virtual root, read-only boundary, Docker command, and dependency pins. Then:

```bash
git add app/src/test/java/com/voyagerfiles/data/remote/smb/SmbDockerIntegrationTest.kt docs/TESTING.md docs/ARCHITECTURE.md
git commit -m "test: verify SMB discovery against Samba"
```

### Task 6: Issue evidence

**Files:**
- Verify only.

- [ ] **Step 1: Run focused SMB gate from a clean provider state**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.remote.smb.*' --stacktrace`

Run: `VOYAGER_RUN_DOCKER_TESTS=true ./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.smb.SmbDockerIntegrationTest --stacktrace`

Expected: both pass freshly.

- [ ] **Step 2: Record closure evidence**

Save the exact resolved versions, Docker image digest, test commands, and results for the eventual #55 comment. Do not close the issue before the combined pull request is merged.
