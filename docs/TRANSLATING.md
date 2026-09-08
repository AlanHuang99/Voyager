# Translating Voyager

Voyager keeps user-facing copy in `app/src/main/res/values/strings.xml`. A translation should preserve the meaning and tone of the default English resources while fitting naturally in the target language.

## Translate with Crowdin

Join [Voyager on Crowdin](https://crowdin.com/project/voyagerandroid) to translate or review strings in the browser. English is the source language. Existing French and Simplified Chinese resources are imported during initial setup so their contributors' work is preserved.

The repository's `crowdin.yml` maps Android resources to `values-%android_code%/strings.xml`, with explicit mappings for French (`values-fr`) and Simplified Chinese (`values-zh-rCN`). Resources marked `translatable="false"` remain in the English source file. Untranslated strings are exported with English source text so incomplete locales can pass Android's missing-translation checks. Crowdin retains their untranslated status. New locales containing only English fallback text are omitted until they include a translation; existing locales and partially translated locales are preserved.

The Crowdin translations workflow uploads English resource changes from `master`. It downloads translations daily, runs unit tests, lint, and debug and release builds, and opens or updates a translation pull request. Translation pull requests require review and are not merged automatically. These checks run in the synchronization workflow itself because pull requests created with `GITHUB_TOKEN` do not trigger the regular pull request workflow.

Maintainers can run the workflow manually from GitHub Actions. For the initial import, enable **Import repository translations into Crowdin** once. Leave it disabled for normal synchronization to avoid reimporting older repository translations. The project ID is `927407`; the token is stored only in the repository's `CROWDIN_PERSONAL_TOKEN` Actions secret. The repository must allow GitHub Actions to create pull requests.

## Add a locale directly

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
