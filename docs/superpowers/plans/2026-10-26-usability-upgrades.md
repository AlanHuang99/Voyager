# October usability upgrades implementation plan

**Goal:** Deliver Quick Access sorting, local Hide/Unhide, and the reviewed translations, verified on K60.
**Architecture:** Keep category sorting in a small pure model and observe its DataStore setting in StorageCategoryViewModel. Reuse the browser sort control and existing local rename path for file visibility actions.
**Tech stack:** Kotlin, Compose, DataStore, Gradle, Android instrumentation.
**Spec:** ../specs/2026-10-26-usability-upgrades-design.md

## Constraints and review focus

Preserve Android 8.0 support, existing user data and translations, current browser sort preferences, and no-overwrite file operations. Leave #158 alone. Check partial scan order, sort persistence across a new ViewModel/process, equal sort keys, hidden-name collisions, and unsupported providers or names.

### Task 1: Translations and Quick Access sorting

- [x] Integrate reviewed PR #156 at `54a69d36cc784704593f1bc491950e3110c60113`.
- [x] Add a failing K60 category Sort-control test using the existing CategoryContent.
- [x] Add `CategorySort` in `data/model/CategorySort.kt`, category sort persistence in `data/local/PreferencesManager.kt`, and sorted published state in `viewmodel/StorageCategoryViewModel.kt`.
- [x] Extract the existing sort dropdown into `ui/components/FileSortMenu.kt` and reuse it in BrowserScreen and CategoryScreen.
- [x] Verify literal order fixtures for all four fields, both directions and ties; test persisted preferences and unchanged browser sorting; test the actual K60 menu.
- [x] Commit the sorting change after focused verification.

### Task 2: Hide and unhide

- [x] Add failing behavior tests for visibility action eligibility and no-overwrite hide/unhide operations.
- [x] Add a local hidden-name helper, row actions, and single-selection overflow actions; use existing rename operations.
- [x] Add strings and run `uv run --no-project python scripts/prepare_translation_export.py`.
- [x] Verify K60 hide/unhide round trips for files and folders, hidden-file visibility, selection/Grid access, collisions, and unchanged bytes.
- [x] Commit the visibility change after focused verification.

### Task 3: Integrated validation

- [x] Update README and docs/TESTING.md for the delivered behavior.
- [x] Run `./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest`.
- [x] Run new and related K60 instrumentation; inspect screenshots, restart persistence, and minified-build behavior.
- [x] Obtain an independent whole-branch review and address material findings with focused regressions.
- [x] Record results and leave a tested debug build installed on K60.

## Progress

Pre-flight: translations add only localized resources; sorting reuses existing English labels; visibility adds new resources after translation integration. No conflicting interfaces.

Task 1 verification: K60 missing-Sort regression failed before implementation. Two JVM sorting tests and all eight K60 category/sorting tests pass. Permission-loss and stale-file behavior remain covered.

Task 2 verification: K60 Hide-menu regression failed before implementation. Visibility and row-menu JVM tests pass, all four K60 visibility cases pass, and all 24 translation-tool tests pass. Existing translations were preserved while adding the two English action labels.

Task 3 local gate: 448 JVM tests passed, 14 opt-in tests skipped, and lint completed with no errors. Debug, release, and instrumentation APKs built successfully. An independent review of `71ca06b..79e5cd1` found no actionable defects and verified preservation of existing translations against immutable commits.

Task 3 device verification: all 48 distinct selected K60 regression tests passed across focused and follow-up runs. An initial TV focus failure passed when repeated alone, with its full class, and in the follow-up batch; concurrent device use was observed. A misnamed class in the follow-up command was rerun under its correct package and passed all four tests. The isolated minified build preserved Date descending after a process restart, rendered the menu in portrait and landscape, and completed file, folder, and Grid Hide/Unhide round trips with exact byte preservation. Temporary device fixtures and the minified test app were removed. The installed debug APK's SHA-256 matches the tested local artifact. No publication was performed.
