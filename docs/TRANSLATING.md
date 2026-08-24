# Translating Voyager

Voyager keeps user-facing copy in `app/src/main/res/values/strings.xml`. A translation should preserve the meaning and tone of the default English resources while fitting naturally in the target language.

## Add a locale

Copy the default resource file into an Android locale directory, then translate its values. For example, French uses `app/src/main/res/values-fr/strings.xml`, Brazilian Portuguese uses `values-pt-rBR`, and Traditional Chinese for Taiwan uses `values-zh-rTW`.

Do not translate resources marked `translatable="false"`. These include the Voyager brand name, protocol names, and other identifiers that must remain stable. A translated file may omit those entries and inherit them from the default resources.

## Preserve formats and XML

- Preserve numbered placeholders such as `%1$s`, `%2$d`, and `%1$.1f`. The number identifies the argument, while the final letter controls how Android formats it.
- Preserve `%%` as a literal percent sign.
- Keep XML escapes and Android string escapes valid. Apostrophes and literal quotation marks may require a backslash, and `<`, `>`, and `&` must remain valid XML.
- Translate every plural quantity required by the target locale. Voyager's default plurals include `one` and `other`; Android may require additional quantities such as `zero`, `two`, `few`, or `many` for a particular language.
- Keep dynamic filenames, paths, hostnames, error details, and public keys as placeholders. Do not move dynamic values into static prose.

## Verify a translation

Run the local resource, lint, and build gates from the repository root:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Connect a device or emulator and run instrumentation:

```bash
ANDROID_SERIAL=DEVICE ./gradlew connectedDebugAndroidTest --stacktrace
```

Review long translations, plural forms, large text, landscape layouts, and both list and grid browser modes. Android's pseudo-locales are useful for exposing truncation and assumptions about text direction.

For an RTL smoke test on a dedicated test device, enable forced RTL, restart the debug app, and inspect Home, Connections, Browser, Settings, Trash, and dialogs:

```bash
adb -s DEVICE shell setprop debug.force_rtl true
adb -s DEVICE shell am force-stop com.voyagerfiles.debug
adb -s DEVICE shell monkey -p com.voyagerfiles.debug -c android.intent.category.LAUNCHER 1
```

Always restore the device setting and restart Voyager when the review is complete:

```bash
adb -s DEVICE shell setprop debug.force_rtl 0
adb -s DEVICE shell am force-stop com.voyagerfiles.debug
adb -s DEVICE shell monkey -p com.voyagerfiles.debug -c android.intent.category.LAUNCHER 1
```

Include the locale, device or emulator, Android version, and commands used for verification in the pull request.
