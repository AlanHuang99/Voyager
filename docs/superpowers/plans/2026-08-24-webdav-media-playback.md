# WebDAV Media Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream WebDAV audio and video directly to external Android players through expiring opaque content URIs, with seeking and an explicit download fallback.

**Architecture:** A dedicated OkHttp range source owns immutable WebDAV credentials and validates every response. An in-process token store maps random short-lived tokens to one source and safe metadata. A non-exported `ContentProvider` exposes Android proxy file descriptors and relies on URI grants for caller authorization, while browser tap classification limits direct playback to WebDAV media.

**Tech Stack:** Kotlin coroutines, OkHttp 4.12-compatible APIs, Android ContentProvider and ProxyFileDescriptorCallback, Compose, MockWebServer, JUnit 4, Android instrumentation, ADB Wi-Fi debugging.

**Spec:** `docs/superpowers/specs/2026-08-24-github-issues-52-58-design.md`

## Global Constraints

- Direct playback applies only to non-directory WebDAV audio and video files.
- SFTP, FTP, SMB, non-media WebDAV, and explicit Download keep existing download behavior.
- A content URI contains only a cryptographically random token; it contains no remote URL, path, connection ID, username, or password.
- The provider is non-exported, grants individual read permissions, and rejects every write mode.
- Range reads validate status, `Content-Range`, offsets, lengths, and exact returned bounds.
- Empty content, missing length, and unknown length are not converted into the same state.
- Failure to prove usable byte ranges produces an explicit Download or Cancel choice and never silently stages the full file.

---

### Task 1: Authenticated WebDAV range source

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/playback/PlaybackRandomAccessSource.kt`
- Create: `app/src/main/java/com/voyagerfiles/data/remote/webdav/WebDavRangeSource.kt`
- Create: `app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavRangeSourceTest.kt`

**Interfaces:**
- Produces: `PlaybackRandomAccessSource`, `WebDavRangeSource.probe(): Result<WebDavRangeMetadata>`, `read(offset, byteCount): Result<ByteArray>`, and `close()`.
- Produces: `WebDavRangeMetadata(size: Long)` where zero is a valid size and absence is represented by probe failure.

- [ ] **Step 1: Write failing probe and read tests with MockWebServer**

Use a dispatcher that requires the expected Basic `Authorization` header. Return `206` with `Content-Range: bytes 0-0/10` for the probe and exact slices for later ranges. Assert the probe size is 10, `read(3, 4)` sends `Range: bytes=3-6`, and bytes equal `3,4,5,6`. Add cases rejecting `200`, malformed totals, mismatched range starts, truncated bodies, reads beyond EOF, negative offsets, and zero or negative byte counts. Add a valid empty-file probe using `416` with `Content-Range: bytes */0` and assert size `0`.

```kotlin
val source = WebDavRangeSource(
    client = OkHttpClient(),
    url = server.url("/media.bin"),
    authorization = Credentials.basic("tester", "secret"),
)
assertEquals(10L, source.probe().getOrThrow().size)
assertArrayEquals(byteArrayOf(3, 4, 5, 6), source.read(3, 4).getOrThrow())
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.webdav.WebDavRangeSourceTest --stacktrace`

Expected: compilation FAIL because `WebDavRangeSource` does not exist.

- [ ] **Step 3: Implement validated probe and bounded reads**

Build each request with `Range: bytes=start-end` and optional `Authorization`. Keep the probed size as a nullable private property until validation succeeds. Parse headers with strict regular expressions:

```kotlin
private val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
private val emptyContentRange = Regex("bytes \\*/0")
```

Define `PlaybackRandomAccessSource : AutoCloseable` with `fun read(offset: Long, byteCount: Int): Result<ByteArray>`. Make `WebDavRangeSource` implement it. For nonempty probe, require HTTP 206, range 0-0, positive total, and exactly one response byte. For empty probe, accept only HTTP 416 plus `bytes */0`. For reads, require a successful prior probe, `offset in 0 until size`, `byteCount > 0`, calculate the inclusive end with overflow-safe `minOf(size - 1, Math.addExact(offset, byteCount.toLong() - 1))`, require a matching 206 header and total, and read exactly `end - offset + 1` bytes into a bounded array. Track active OkHttp calls in a synchronized set; `close()` cancels them, evicts the connection pool, and makes later reads fail.

- [ ] **Step 4: Run all range-source tests**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.webdav.WebDavRangeSourceTest --stacktrace`

Expected: PASS, including authentication, empty content, malformed responses, exact byte slices, and close cancellation.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/playback/PlaybackRandomAccessSource.kt app/src/main/java/com/voyagerfiles/data/remote/webdav/WebDavRangeSource.kt app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavRangeSourceTest.kt
git commit -m "feat: read authenticated WebDAV byte ranges"
```

### Task 2: Provider source factory

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/data/remote/webdav/WebDavFileProvider.kt`
- Modify: `app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavFileProviderTest.kt`

**Interfaces:**
- Produces: `PreparedWebDavPlayback(source: WebDavRangeSource, metadata: WebDavRangeMetadata)` and `suspend fun createPlaybackSource(path: String): Result<PreparedWebDavPlayback>`.
- Consumes: immutable `RemoteConnection`, `webDavBaseUrl`, and URL path encoding.

- [ ] **Step 1: Write a failing source-factory test**

Start the existing authenticated local WebDAV fixture with range support, call `provider.createPlaybackSource("/space file.mp3")`, assert the prepared metadata size, and read a middle slice through the prepared source. Assert the request URL encodes the space and authenticates. Add a server mode that ignores range headers and assert source creation fails.

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.data.remote.webdav.WebDavFileProviderTest --stacktrace`

Expected: compilation FAIL because `createPlaybackSource` does not exist.

- [ ] **Step 3: Extract shared URL and authorization construction**

Make `toUrl(path)` internal and use OkHttp `HttpUrl` path-segment building so spaces and reserved characters are encoded once. Add:

```kotlin
internal data class PreparedWebDavPlayback(
    val source: WebDavRangeSource,
    val metadata: WebDavRangeMetadata,
)

internal suspend fun createPlaybackSource(path: String): Result<PreparedWebDavPlayback> =
    withContext(Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient()
            val source = WebDavRangeSource(
                client = client,
                url = toUrl(path).toHttpUrl(),
                authorization = connection.username.takeIf(String::isNotEmpty)?.let {
                    Credentials.basic(connection.username, connection.password)
                },
            )
            val metadata = source.probe().getOrElse { error -> source.close(); throw error }
            PreparedWebDavPlayback(source, metadata)
        }
    }
```

Do not reuse the mutable browser client's session or connection pool.

- [ ] **Step 4: Run WebDAV provider and range tests**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.remote.webdav.*' --stacktrace`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/data/remote/webdav/WebDavFileProvider.kt app/src/test/java/com/voyagerfiles/data/remote/webdav/WebDavFileProviderTest.kt
git commit -m "feat: prepare independent WebDAV playback sources"
```

### Task 3: Opaque expiring token store

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/playback/PlaybackTokenStore.kt`
- Create: `app/src/test/java/com/voyagerfiles/playback/PlaybackTokenStoreTest.kt`

**Interfaces:**
- Produces: `PlaybackEntry(displayName, mimeType, size, source)`, `register`, `lookup`, `remove`, and `clear`.
- Consumes later: `WebDavPlaybackProvider`.

- [ ] **Step 1: Write failing lifecycle tests with a fake source and fake clock**

Assert tokens are URL-safe, at least 256 random bits, unique across 1,000 registrations, and reveal none of the entry's display name, MIME type, URL-like fake-source label, username, or password. Assert lookup refreshes last access, an entry expires after exactly ten inactive minutes, removal and expiry each close the source exactly once, and an unknown token returns null.

```kotlin
val store = PlaybackTokenStore(clock = clock, random = SecureRandom(), inactivityMillis = 10 * 60 * 1000L)
val token = store.register(entry)
assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
clock.advanceBy(10 * 60 * 1000L)
assertNull(store.lookup(token))
assertEquals(1, source.closeCount)
```

- [ ] **Step 2: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.playback.PlaybackTokenStoreTest --stacktrace`

Expected: compilation FAIL because the token-store types do not exist.

- [ ] **Step 3: Implement concurrency-safe token lifecycle**

Use the `PlaybackRandomAccessSource` interface from Task 1. Generate 32 random bytes with `SecureRandom` and encode them using `Base64.getUrlEncoder().withoutPadding()`. Store entries in a `ConcurrentHashMap` with synchronized per-entry close state. `lookup` removes expired entries before returning and updates `lastAccessMillis` only for a live entry. `remove` and `clear` close sources outside map mutation.

- [ ] **Step 4: Run token tests and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.playback.PlaybackTokenStoreTest --stacktrace`

Expected: PASS.

```bash
git add app/src/main/java/com/voyagerfiles/playback/PlaybackTokenStore.kt app/src/test/java/com/voyagerfiles/playback/PlaybackTokenStoreTest.kt
git commit -m "feat: register opaque expiring playback tokens"
```

### Task 4: Read-only streaming ContentProvider

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/playback/WebDavPlaybackProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/androidTest/java/com/voyagerfiles/playback/WebDavPlaybackProviderTest.kt`

**Interfaces:**
- Produces: authority `${applicationId}.webdavplayback`, `register(context, entry): Uri`, safe metadata queries, and seekable proxy descriptors.
- Consumes: `PlaybackTokenStore` and `PlaybackRandomAccessSource`.

- [ ] **Step 1: Write failing provider instrumentation tests**

Register an entry backed by deterministic bytes. Assert the URI has authority `${context.packageName}.webdavplayback`, exactly one opaque path segment, and contains none of the display name or remote details. Query only `OpenableColumns.DISPLAY_NAME` and `SIZE`; assert `getType()` returns the registered media MIME. Open mode `r`, seek to offset 4, read 3 bytes, and assert exact output. Assert mode `w`, unknown token, and an entry expired through the injectable test store all throw `FileNotFoundException`. Close the descriptor and assert the source closes and token can no longer open.

- [ ] **Step 2: Run and verify compilation fails**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.playback.WebDavPlaybackProviderTest --stacktrace`

Expected: compilation FAIL because the provider does not exist.

- [ ] **Step 3: Declare the provider and implement metadata**

```xml
<provider
    android:name=".playback.WebDavPlaybackProvider"
    android:authorities="${applicationId}.webdavplayback"
    android:exported="false"
    android:grantUriPermissions="true" />
```

`query()` returns a `MatrixCursor` containing only requested supported columns. `getType()` and `openFile()` require a live token. `insert`, `delete`, and `update` throw `UnsupportedOperationException`. `openFile()` accepts exactly `"r"`.

- [ ] **Step 4: Implement proxy file descriptors**

Use `StorageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(handlerThread.looper))`. `onGetSize()` returns the nonnegative registered size. `onRead(offset, size, data)` validates arguments, delegates to the source, copies at most `data.size`, and returns the byte count. `onRelease()` removes the token and closes its source. A single provider-owned `HandlerThread` is started in `onCreate()` and stopped during test reset.

- [ ] **Step 5: Run provider and manifest tests**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.playback.WebDavPlaybackProviderTest --stacktrace`

Run: `./gradlew processDebugMainManifest lintDebug --stacktrace`

Expected: provider tests pass, the merged manifest keeps the provider non-exported, and lint reports no unsafe exported component.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/playback/WebDavPlaybackProvider.kt app/src/main/AndroidManifest.xml app/src/androidTest/java/com/voyagerfiles/playback/WebDavPlaybackProviderTest.kt
git commit -m "feat: expose seekable WebDAV playback URIs"
```

### Task 5: Browser tap decision and external intent

**Files:**
- Create: `app/src/main/java/com/voyagerfiles/ui/screens/RemoteFileTapAction.kt`
- Create: `app/src/test/java/com/voyagerfiles/ui/screens/RemoteFileTapActionTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/util/FileUtils.kt`
- Modify: `app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt`

**Interfaces:**
- Produces: `remoteFileTapAction(file): RemoteFileTapAction` and `createRemotePlaybackIntent(uri, file): Intent`.

- [ ] **Step 1: Write failing classifier tests**

```kotlin
@Test fun onlyWebDavMediaStreams() {
    assertEquals(STREAM_WEBDAV, remoteFileTapAction(file("song.mp3", WEBDAV)))
    assertEquals(STREAM_WEBDAV, remoteFileTapAction(file("movie.mp4", WEBDAV)))
    assertEquals(DOWNLOAD, remoteFileTapAction(file("notes.txt", WEBDAV)))
    assertEquals(DOWNLOAD, remoteFileTapAction(file("song.mp3", SFTP)))
    assertEquals(NAVIGATE, remoteFileTapAction(directory("Music", WEBDAV)))
}
```

- [ ] **Step 2: Write a failing Android intent test**

Assert `createRemotePlaybackIntent(contentUri, mediaFile)` returns `ACTION_VIEW`, exact MIME, `FLAG_GRANT_READ_URI_PERMISSION`, and `ClipData` containing the same URI. Assert the opaque URI, not any WebDAV URL, is the intent data.

- [ ] **Step 3: Run and verify compilation fails**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.RemoteFileTapActionTest --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest --stacktrace`

Expected: compilation FAIL because the classifier and intent builder do not exist.

- [ ] **Step 4: Implement classifier and grant-safe intent**

```kotlin
internal fun remoteFileTapAction(file: FileItem): RemoteFileTapAction = when {
    file.isDirectory -> RemoteFileTapAction.NAVIGATE
    file.source == FileSource.WEBDAV && (file.isAudio || file.isVideo) -> RemoteFileTapAction.STREAM_WEBDAV
    else -> RemoteFileTapAction.DOWNLOAD
}

fun createRemotePlaybackIntent(uri: Uri, file: FileItem): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, file.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri(file.name, uri)
}
```

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew testDebugUnitTest --tests com.voyagerfiles.ui.screens.RemoteFileTapActionTest --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.util.FileSharingTest --stacktrace`

Expected: PASS.

```bash
git add app/src/main/java/com/voyagerfiles/ui/screens/RemoteFileTapAction.kt app/src/test/java/com/voyagerfiles/ui/screens/RemoteFileTapActionTest.kt app/src/main/java/com/voyagerfiles/util/FileUtils.kt app/src/androidTest/java/com/voyagerfiles/util/FileSharingTest.kt
git commit -m "feat: classify direct WebDAV media playback"
```

### Task 6: ViewModel registration and explicit fallback UI

**Files:**
- Modify: `app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt`
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/WebDavPlaybackFlowTest.kt`

**Interfaces:**
- Produces: `suspend fun prepareWebDavPlayback(file: FileItem): Result<Uri>`.
- Consumes: active `WebDavFileProvider`, playback token provider, classifier, and external intent builder.

- [ ] **Step 1: Write failing flow tests**

Inject a playback-source preparation seam into `FileBrowserViewModel`. In Compose, tap a WebDAV MP3 and assert successful preparation launches a read-granted media intent. Make preparation fail with range-unsupported error and assert an alert titled `Direct playback unavailable` offers `Download` and `Cancel`; assert no download begins before the user selects Download. Tap SFTP MP3 and WebDAV text and assert the existing download path starts immediately.

- [ ] **Step 2: Run and verify the red state**

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.WebDavPlaybackFlowTest --stacktrace`

Expected: FAIL because all network taps still call `downloadFile()`.

- [ ] **Step 3: Implement playback registration**

Resolve the active provider as `WebDavFileProvider`, obtain `PreparedWebDavPlayback`, and call `WebDavPlaybackProvider.register(application, PlaybackEntry(file.name, file.mimeType, prepared.metadata.size, prepared.source))`. On registration failure, close the source immediately. Reject mismatched source, directory, or inactive file.

- [ ] **Step 4: Route both list and grid through one tap handler**

Create a local `handleRemoteFileTap(file)` function in `BrowserScreen`. On `STREAM_WEBDAV`, launch a coroutine, prepare the URI, and start the intent. Map `ActivityNotFoundException` to `No app can play this media`. On range/preparation failure, retain the `FileItem` in `playbackFallbackFor` and show the Download/Cancel dialog. On Download, call `viewModel.downloadFile(file.path)` once.

- [ ] **Step 5: Run flow, file-intent, and WebDAV tests**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.remote.webdav.*' --tests com.voyagerfiles.ui.screens.RemoteFileTapActionTest --stacktrace`

Run: `ANDROID_SERIAL=$(adb devices | awk '$2 == "device" {print $1; exit}') ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.playback.WebDavPlaybackProviderTest,com.voyagerfiles.ui.screens.WebDavPlaybackFlowTest,com.voyagerfiles.util.FileSharingTest --stacktrace`

Expected: PASS for successful direct playback, explicit fallback, non-media download, and URI security.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/voyagerfiles/viewmodel/FileBrowserViewModel.kt app/src/main/java/com/voyagerfiles/ui/screens/BrowserScreen.kt app/src/androidTest/java/com/voyagerfiles/ui/screens/WebDavPlaybackFlowTest.kt
git commit -m "feat: stream WebDAV media on file tap"
```

### Task 7: Documentation and real K60 playback

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`
- Modify: `README.md`

**Interfaces:**
- Produces: documented URI security boundary, byte-range requirement, fallback behavior, and device procedure.

- [ ] **Step 1: Document the verified behavior**

Describe WebDAV-only audio/video streaming, byte-range seeking, opaque expiring URIs, non-exported provider grants, and the Download fallback. State that other remote protocols continue to download before opening.

- [ ] **Step 2: Start disposable authenticated WebDAV media on the workstation**

Create a `mktemp -d` fixture directory. Generate a short PCM WAV with `uv run` and create a short MP4 by recording two seconds of the K60 screen, then pull it into the fixture directory. Use `apply_patch` to create an ignored `app/build/k60-webdav/wsgidav.yaml` whose `provider_mapping` root is the resolved absolute fixture path, whose `http_authenticator` accepts Basic and rejects Digest, and whose `simple_dc.user_mapping` contains only user `voyager` with disposable password `voyager-test`. Start `uvx --from wsgidav wsgidav --config app/build/k60-webdav/wsgidav.yaml`. Verify `curl --user voyager:voyager-test -H 'Range: bytes=1-3' http://127.0.0.1:18080/audio.wav` returns HTTP 206 and exactly three body bytes before configuring Voyager with the workstation's LAN address and the same disposable credentials.

- [ ] **Step 3: Verify play and seek on K60 over Wi-Fi debugging**

Rediscover the K60 `_adb-tls-connect._tcp` endpoint, verify model `23013RK75C`, install the debug universal APK, force-stop, relaunch, connect to the disposable WebDAV endpoint, tap audio and video, seek forward and backward, and confirm no complete media copy appears in Downloads or Voyager cache. Stop the server and remove fixtures afterward.

- [ ] **Step 4: Run focused final gate and commit docs**

Run: `./gradlew testDebugUnitTest --tests 'com.voyagerfiles.data.remote.webdav.*' --tests com.voyagerfiles.playback.PlaybackTokenStoreTest --tests com.voyagerfiles.ui.screens.RemoteFileTapActionTest --stacktrace`

Run: `ANDROID_SERIAL=K60_SERIAL ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.playback.WebDavPlaybackProviderTest,com.voyagerfiles.ui.screens.WebDavPlaybackFlowTest --stacktrace`

Expected: PASS with real play and seek recorded for issue #56.

```bash
git add README.md docs/ARCHITECTURE.md docs/TESTING.md
git commit -m "docs: explain direct WebDAV media playback"
```
