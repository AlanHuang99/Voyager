# Dark Navigation Background Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Voyager's navigation crossfade while preventing its light Android window background from appearing between dark destinations.

**Architecture:** Draw the active Material color scheme's background across the complete `NavHost` bounds so the crossfade always composites over an opaque, theme-aware layer. Cover the behavior with a Compose instrumentation test that pauses the Home-to-Settings transition at its midpoint and compares an unobstructed edge pixel with the Dark theme background.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose Material 3, Navigation Compose 2.8.5, AndroidX Compose UI testing, JUnit 4, Gradle 8.11.1.

## Global Constraints

- Preserve the existing Navigation Compose enter, exit, pop-enter, and pop-exit fades.
- Use `MaterialTheme.colorScheme.background`; do not add a hardcoded color or Android resource that can diverge from runtime theme selection.
- Do not change destination `Scaffold` or `Surface` colors, transition timing, transition easing, splash styling, or Android window styling.
- Keep the Android 8.0 API 26 minimum and introduce no dependencies.
- Run commands from the repository root.

---

### Task 1: Theme-aware navigation underlay

**Files:**
- Create: `app/src/androidTest/java/com/voyagerfiles/ui/screens/NavigationBackgroundTest.kt`
- Modify: `app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt:3-33`

**Interfaces:**
- Consumes: `AppNavigation(viewModel: FileBrowserViewModel, hasAllFilesAccess: Boolean, onRequestAllFilesAccess: () -> Unit)` and `VoyagerTheme(appTheme: AppTheme, customColorScheme: ColorScheme? = null, content: @Composable () -> Unit)`.
- Produces: The existing `AppNavigation` interface with an opaque `MaterialTheme.colorScheme.background` layer applied to its `NavHost`; no public signature changes.

- [ ] **Step 1: Write the failing transition regression**

Create `app/src/androidTest/java/com/voyagerfiles/ui/screens/NavigationBackgroundTest.kt` with the following content:

```kotlin
package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.theme.DarkColors
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationBackgroundTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun darkNavigationCrossfadeKeepsHostBackgroundDark() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            VoyagerTheme(appTheme = AppTheme.DARK) {
                AppNavigation(
                    viewModel = viewModel,
                    hasAllFilesAccess = false,
                    onRequestAllFilesAccess = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.mainClock.advanceTimeBy(350L)

        val image = composeTestRule.onRoot().captureToImage()
        val edgePixel = image.toPixelMap()[1, image.height / 2]
        assertColorNear(DarkColors.background, edgePixel)
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        val tolerance = 0.03f
        assertTrue(
            "Expected $expected but captured $actual at the navigation host edge",
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.NavigationBackgroundTest --stacktrace
```

Expected: FAIL in `darkNavigationCrossfadeKeepsHostBackgroundDark` because the captured edge pixel is materially lighter than `DarkColors.background` at the midpoint of the default 700 millisecond crossfade.

- [ ] **Step 3: Add the theme-aware navigation background**

Update `app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt` with these imports:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
```

Replace the current host invocation with:

```kotlin
NavHost(
    navController = navController,
    startDestination = Screen.Home.route,
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
) {
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
ANDROID_SERIAL=192.168.27.167:5555 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.voyagerfiles.ui.screens.NavigationBackgroundTest --stacktrace
```

Expected: PASS with one test and zero failures.

- [ ] **Step 5: Run the complete local gate**

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --stacktrace
```

Expected: `BUILD SUCCESSFUL` with unit tests, Android lint, debug APK assembly, and minified release APK assembly all successful.

- [ ] **Step 6: Verify the physical-device transition**

Install `app/build/outputs/apk/debug/app-universal-debug.apk` as `com.voyagerfiles.debug`, select the Dark theme, grant debug-only storage access, record Home-to-Internal-storage navigation, and compare frames with the v1.4.0 baseline. The maximum blank-background luminance during the transition must remain within 0.03 of `DarkColors.background`, with no gray-white midpoint spike. Remove `com.voyagerfiles.debug` and all `/sdcard/voyager-*` test artifacts afterward.

- [ ] **Step 7: Commit the implementation**

```bash
git add app/src/androidTest/java/com/voyagerfiles/ui/screens/NavigationBackgroundTest.kt app/src/main/java/com/voyagerfiles/ui/screens/Navigation.kt
git commit -m "fix: keep dark background during navigation"
```
