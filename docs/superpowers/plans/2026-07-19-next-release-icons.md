# Next-Release Android Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Voyager's Android launcher, round, monochrome, raster fallback, and Fastlane/store icons with the approved post-v1.5.0 paper-boat package and prove the exact assets build and render correctly.

**Architecture:** Treat the supplied archive as an immutable approved asset set identified by its SHA-256 digest. Add a JVM resource-contract test that validates every approved file hash, adaptive monochrome wiring, density dimensions, and store icon dimensions; then copy only the production assets from the archive, excluding previews and archive documentation. Verify with focused tests, Android resource compilation, the full project gate, installed APK resource inspection, and screenshots from a physical Android 16 device.

**Tech Stack:** Android XML resources, adaptive icons, PNG mipmaps, Kotlin/JUnit 4, Java ImageIO, Gradle/AGP, adb, uiautomator

## Global Constraints

- Use `/home/alan/.codex/attachments/12c26c82-620f-45cb-9f7b-c383f6338104/Voyager Android icon design.zip` only when its SHA-256 is `bf28f5c51fd0709c7871d4cd2f24779bca01788199831868033d8a7b4617e3b2`.
- Apply the icon update after v1.5.0; do not alter or retag v1.5.0.
- Preserve `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` in `app/src/main/AndroidManifest.xml`.
- Commit `ic_launcher_monochrome.xml` and wire both adaptive-icon resources to it.
- Commit only the supplied production resources and Fastlane icon; do not commit `README.md`, `preview-circle.png`, or `preview-squircle.png` from the archive.
- Keep raster fallback dimensions at mdpi 48 px, hdpi 72 px, xhdpi 96 px, xxhdpi 144 px, xxxhdpi 192 px, with both standard and round variants.
- Keep the Fastlane/store icon at 512 × 512 px.
- Do not add authorship markers or hard-wrap prose.

---

### Task 1: Lock the approved resource contract

**Files:**
- Create: `app/src/test/java/com/voyagerfiles/ui/IconResourceContractTest.kt`

**Interfaces:**
- Consumes: repository-relative Android resource paths and the approved asset SHA-256 map
- Produces: `IconResourceContractTest`, which fails unless every launcher and store asset matches the approved package and the adaptive-icon wiring uses the dedicated monochrome drawable

- [ ] **Step 1: Write the failing resource-contract test**

```kotlin
package com.voyagerfiles.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

class IconResourceContractTest {

    @Test
    fun adaptiveIconsUseDedicatedMonochromeArtwork() {
        val repositoryRoot = repositoryRoot()
        val monochrome = File(repositoryRoot, "app/src/main/res/drawable/ic_launcher_monochrome.xml")
        assertTrue("Dedicated monochrome icon is missing", monochrome.isFile)

        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val adaptiveIcon = File(repositoryRoot, "app/src/main/res/mipmap-anydpi-v26/$name").readText()
            assertTrue("$name must use the dedicated monochrome icon", adaptiveIcon.contains("@drawable/ic_launcher_monochrome"))
        }
    }

    @Test
    fun approvedLauncherAndStoreAssetsAreInstalledExactly() {
        val repositoryRoot = repositoryRoot()
        approvedHashes.forEach { (path, expectedHash) ->
            val file = File(repositoryRoot, path)
            assertTrue("Approved icon asset is missing: $path", file.isFile)
            assertEquals("Approved icon asset changed: $path", expectedHash, sha256(file))
        }
    }

    @Test
    fun rasterFallbacksAndStoreIconHaveRequiredDimensions() {
        val repositoryRoot = repositoryRoot()
        val densities = mapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192,
        )
        densities.forEach { (density, expectedSize) ->
            listOf("ic_launcher.png", "ic_launcher_round.png").forEach { name ->
                assertSquarePng(
                    file = File(repositoryRoot, "app/src/main/res/mipmap-$density/$name"),
                    expectedSize = expectedSize,
                )
            }
        }
        assertSquarePng(
            file = File(repositoryRoot, "fastlane/metadata/android/en-US/images/icon.png"),
            expectedSize = 512,
        )
    }

    private fun assertSquarePng(file: File, expectedSize: Int) {
        val image = ImageIO.read(file)
        assertEquals("Unexpected width for ${file.path}", expectedSize, image.width)
        assertEquals("Unexpected height for ${file.path}", expectedSize, image.height)
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory, checkNotNull(workingDirectory.parentFile))
            .first { File(it, "app/src/main/res").isDirectory && File(it, "fastlane").isDirectory }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val approvedHashes = mapOf(
            "app/src/main/res/drawable/ic_launcher_background.xml" to "050aaa60959707bf93881e30988ac33c591f29a5e2550d9533f2c4722ba6f4ec",
            "app/src/main/res/drawable/ic_launcher_foreground.xml" to "37988bdbc7d663316685ea6e72ea8c669b4a41d680b3f6cdbce2ff82dd2f901a",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml" to "74107e2dd0ba5de423fec028b6d8fca456741ab05b9aa639bd48659ff3ae7afb",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml" to "ada31da9e23f4520f3753e71ca23799a107431473daa3774b6cecac26847260c",
            "app/src/main/res/mipmap-hdpi/ic_launcher.png" to "b96d5ddbdadf2b370ee5df049420624460a4b2e84f075a4d8a5677169c65f819",
            "app/src/main/res/mipmap-hdpi/ic_launcher_round.png" to "e1f02e988530075b89f26012b5cd30ed1d9ae1896fd2e1c4adc7206c464bb3a9",
            "app/src/main/res/mipmap-mdpi/ic_launcher.png" to "2e8dc6d6c0d609546229e32dbf7ab7a10fb15fa9a090a8abd347dcd236131163",
            "app/src/main/res/mipmap-mdpi/ic_launcher_round.png" to "631237135b7cacc44fcbfce8e87190b8dd4ffb624b3bab7966df9e62bb56ec5b",
            "app/src/main/res/mipmap-xhdpi/ic_launcher.png" to "08ecd436c4090d1a274298a1775f90416c3de809a93450197ba0f3d11f04755a",
            "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png" to "6fcdf9b371d5f3549cc89a4546ae47cf54d6052781b1079132f7fc0344bbc1d9",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher.png" to "19e18b63453b48090169a6a5b0981b6dc8c5bb013f9a20cae9ddb622202e7101",
            "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png" to "6f13a7d22c8b27b09a1a96d67e58d0b3e695fba794e0c7a2b12cfae6237cc7b0",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" to "fa8077c48d3cbc2f759303668243bca9e7e627a86c9a14d3bb76155376832f68",
            "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" to "ead980c83bc44cddc6595053137deee24dfc91aeb774d0f74159664995c42405",
            "fastlane/metadata/android/en-US/images/icon.png" to "73cb88c01bc366cb8f793e904f909cb6f8d479a4ceedd3d0f72b85577e6f61f4",
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx4096m -Dfile.encoding=UTF-8' testDebugUnitTest --tests com.voyagerfiles.ui.IconResourceContractTest --stacktrace
```

Expected: FAIL because `ic_launcher_monochrome.xml` is absent, both adaptive resources still point monochrome at the colored foreground, and the approved hashes differ from the current icon set.

- [ ] **Step 3: Commit the red contract test**

```bash
git add app/src/test/java/com/voyagerfiles/ui/IconResourceContractTest.kt
git commit -m "test: define the approved launcher icon contract"
```

### Task 2: Install the approved production assets

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/res/mipmap-mdpi/ic_launcher.png`
- Modify: `app/src/main/res/mipmap-mdpi/ic_launcher_round.png`
- Modify: `app/src/main/res/mipmap-hdpi/ic_launcher.png`
- Modify: `app/src/main/res/mipmap-hdpi/ic_launcher_round.png`
- Modify: `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
- Modify: `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
- Modify: `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- Modify: `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
- Modify: `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- Modify: `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`
- Modify: `fastlane/metadata/android/en-US/images/icon.png`

**Interfaces:**
- Consumes: verified archive SHA-256 and production files under `voyager-icon/app/` and `voyager-icon/fastlane/`
- Produces: exact launcher, round, dedicated monochrome, raster fallback, and store assets asserted by `IconResourceContractTest`

- [ ] **Step 1: Reverify and extract the immutable archive**

```bash
sha256sum '/home/alan/.codex/attachments/12c26c82-620f-45cb-9f7b-c383f6338104/Voyager Android icon design.zip'
icon_asset_dir=$(mktemp -d)
unzip -q '/home/alan/.codex/attachments/12c26c82-620f-45cb-9f7b-c383f6338104/Voyager Android icon design.zip' -d "$icon_asset_dir"
```

Expected SHA-256: `bf28f5c51fd0709c7871d4cd2f24779bca01788199831868033d8a7b4617e3b2`.

- [ ] **Step 2: Copy only approved production resource directories**

```bash
cp -a "$icon_asset_dir/voyager-icon/app/src/main/res/drawable/." app/src/main/res/drawable/
cp -a "$icon_asset_dir/voyager-icon/app/src/main/res/mipmap-anydpi-v26/." app/src/main/res/mipmap-anydpi-v26/
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  cp -a "$icon_asset_dir/voyager-icon/app/src/main/res/mipmap-$density/." "app/src/main/res/mipmap-$density/"
done
cp -a "$icon_asset_dir/voyager-icon/fastlane/metadata/android/en-US/images/icon.png" fastlane/metadata/android/en-US/images/icon.png
```

- [ ] **Step 3: Verify GREEN and ensure review-only assets are absent**

Run:

```bash
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx4096m -Dfile.encoding=UTF-8' testDebugUnitTest --tests com.voyagerfiles.ui.IconResourceContractTest --stacktrace
git status --short
rg --files | rg 'preview-(circle|squircle)\.png|voyager-icon/README\.md' && exit 1 || true
```

Expected: focused test PASS; only the test, production resource files, store icon, and plan are changed; no archive previews or README are present.

- [ ] **Step 4: Commit the approved assets**

```bash
git add app/src/main/res/drawable/ic_launcher_background.xml app/src/main/res/drawable/ic_launcher_foreground.xml app/src/main/res/drawable/ic_launcher_monochrome.xml app/src/main/res/mipmap-anydpi-v26 app/src/main/res/mipmap-*/ic_launcher*.png fastlane/metadata/android/en-US/images/icon.png
git commit -m "feat: refresh Voyager launcher icons"
```

### Task 3: Verify builds, packaged resources, and physical-device appearance

**Files:**
- Verify: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Verify: installed package `com.voyagerfiles.debug`
- Verify: Android launcher and App info screenshots captured in a temporary directory

**Interfaces:**
- Consumes: approved icon resources from Task 2
- Produces: automated, packaged-APK, and Android 16 visual evidence that the next-release icon is complete

- [ ] **Step 1: Run the full clean local gate**

```bash
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx4096m -Dfile.encoding=UTF-8' clean testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace
```

Expected: 129 unit tests total after adding the three icon contract tests to the verified 126-test baseline, with the existing two skips and zero failures; lint, debug assembly, and minified release assembly all succeed.

- [ ] **Step 2: Inspect packaged launcher resources**

```bash
/home/alan/Android/Sdk/build-tools/34.0.0/aapt2 dump resources app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | rg 'mipmap/ic_launcher|mipmap/ic_launcher_round|drawable/ic_launcher_(background|foreground|monochrome)'
```

Expected: packaged resources include standard, round, background, foreground, and dedicated monochrome entries.

- [ ] **Step 3: Install and restart the debug APK on Android 16**

```bash
adb -s 192.168.27.167:5555 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s 192.168.27.167:5555 shell am force-stop com.voyagerfiles.debug
adb -s 192.168.27.167:5555 shell monkey -p com.voyagerfiles.debug -c android.intent.category.LAUNCHER 1
```

Expected: installation and launch succeed without changing the production `com.voyagerfiles` package.

- [ ] **Step 4: Capture standard adaptive and launcher rendering**

```bash
adb -s 192.168.27.167:5555 shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.voyagerfiles.debug
device_icon_dir=$(mktemp -d)
adb -s 192.168.27.167:5555 exec-out screencap -p > "$device_icon_dir/app-info.png"
adb -s 192.168.27.167:5555 shell input keyevent KEYCODE_HOME
adb -s 192.168.27.167:5555 exec-out screencap -p > "$device_icon_dir/launcher.png"
```

Expected: App info and launcher surfaces show the softened paper boat without clipping, transparent gaps, incorrect mask padding, or stale artwork.

- [ ] **Step 5: Verify Android 13+ monochrome behavior**

Inspect launcher/themed-icon support and its current setting with:

```bash
adb -s 192.168.27.167:5555 shell dumpsys package com.voyagerfiles.debug | rg 'versionCode=|versionName='
adb -s 192.168.27.167:5555 shell settings list secure | rg -i 'theme|icon'
```

If the launcher exposes themed icons, enable the setting temporarily, capture the launcher, confirm the single-color paper-boat silhouette, and restore the previous value. If the launcher does not expose themed icons, record that limitation and use the compiled dedicated monochrome resource plus the resource-contract test as authoritative evidence.

- [ ] **Step 6: Inspect the store icon at original resolution**

Open `fastlane/metadata/android/en-US/images/icon.png` at original detail and confirm the 512 px icon matches the supplied squircle preview and contains no scaling or transparency defects.

- [ ] **Step 7: Run final hygiene and commit any verification-only test refinements**

```bash
git diff --check
git status --short
git log --oneline master..HEAD
```

Expected: clean diff, no screenshots or temporary archive files tracked, no diagnostic logs, and only intentional commits.
