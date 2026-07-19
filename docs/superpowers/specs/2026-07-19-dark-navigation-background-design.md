# Dark navigation background design

## Context

Issue #21 reports that navigation from Home to a directory produces a bright flash in dark mode. A physical-device recording of v1.4.0 reproduces the problem. Voyager uses Navigation Compose 2.8.5, whose default host transitions fade the outgoing and incoming destinations over 700 milliseconds. `AppNavigation` supplies no opaque background to `NavHost`, and the activity inherits a light Android window theme. At the midpoint of the crossfade both dark destinations are partially transparent, so the light window layer becomes visible.

## Design

Keep the existing Navigation Compose transitions and draw `MaterialTheme.colorScheme.background` across the full `NavHost` bounds. The background belongs on the host modifier because it is the stable layer beneath every destination during forward and backward navigation. It follows Voyager's selected color scheme automatically, preserves the current motion behavior, and does not require synchronizing Android XML resources with the app's runtime theme selection.

The change is intentionally limited to the navigation host. Destination screens keep their existing `Scaffold` and `Surface` colors, the Android splash and window themes remain unchanged, and no transition duration or easing changes.

## Verification

Add a focused Compose instrumentation regression that renders `AppNavigation` in a dark theme, navigates from Home to Browser, and samples the root during the transition to prove the host background remains dark. Run the JVM unit suite, Android lint, debug build, and minified release build. Install the debug APK on the connected physical device, repeat the Home-to-Browser recording under the Dark theme, and compare transition-frame luminance against the v1.4.0 baseline. Remove the temporary debug package and device artifacts after verification.

## Acceptance criteria

- Home-to-Browser and Browser-to-Home navigation never reveal a light layer under a dark Voyager theme.
- Existing navigation fades remain enabled.
- Light and custom themes use their own Material color-scheme background.
- The focused regression test and complete documented local gate pass.
- A physical-device transition recording confirms the luminance spike is gone.
