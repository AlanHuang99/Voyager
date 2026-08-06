# GitHub issues 39–45 design

## Scope

Resolve every open Voyager issue as of 2026-08-06 and disposition pull request #46. Delivery is split into three focused pull requests so correctness, user interaction, and transfer-progress changes remain independently reviewable. Each pull request must add regression coverage, pass the complete local gate, and receive K60 device verification where Android platform behavior is involved.

## Change set 1: file opening and archives

Issues #39, #42, and #43 form one file-interaction change set. APK taps continue to use Android's normal view intent with a content URI, but Voyager declares `REQUEST_INSTALL_PACKAGES`, as required for callers targeting Android 8 or later. The contribution from pull request #46 is retained with its original author. Ordinary taps continue to honor Android defaults. A new single-selection “Open with” action wraps the same readable view intent in an Android chooser for local and SAF files, while remote files continue to require download first. Tapping a supported archive no longer attempts an external viewer; it opens a confirmation dialog describing the extraction destination, and confirmation invokes the existing safe archive service. Unsupported archive types keep the existing actionable message and are not presented as extractable.

The implementation will centralize open-intent construction and archive-tap classification so list and grid layouts cannot diverge. Failures remain user-visible through the existing snackbar path. Tests will cover the manifest permission, direct versus chooser intents, action eligibility, archive-tap classification, confirmation UI, successful extraction, unsupported archives, and K60 package-installer launch.

## Change set 2: selection feedback and contrast

Issues #40 and #41 form one selection-mode polish change set. Entering selection mode from an empty selection emits one long-press haptic; selecting or deselecting additional rows does not emit extra transition haptics. This is owned by the browser-level selection callback so list and grid behavior is identical. The selection top app bar explicitly sets navigation, title, and action icon content colors to `onPrimaryContainer` rather than relying on Material defaults that can become low-contrast under neutral dynamic-color palettes. Selected rows and grids retain their existing background treatment.

Tests will cover the empty-to-nonempty transition policy as a pure model, list and grid callback wiring, selection-toolbar semantics, and rendered selection-toolbar colors under a controlled low-chroma theme. K60 verification will use the device's dynamic gray palette and confirm one tactile response when selection begins.

## Change set 3: SAF correctness and transfer progress

Issues #44 and #45 form one transfer change set. Cross-provider uploads, copies, and moves must never derive destination identifiers by string concatenation. They first ask the destination provider to create the file or directory and then use the returned `FileItem.path`, which is valid for filesystem paths, network paths, and opaque SAF document URIs. On failure, cleanup uses that returned path; moves delete the source only after the complete destination succeeds. Existing conflict behavior remains non-overwriting.

The shared streaming layer will report the active item, bytes transferred, optional trustworthy byte total, and monotonic elapsed time. Upload sources query `OpenableColumns.SIZE` when available. Remote downloads report recursively encountered files without performing an expensive preflight traversal. The ViewModel derives a stable average transfer rate from transferred bytes and elapsed monotonic time, throttles UI publication by both byte and time thresholds, and shows filename, item count, formatted byte progress, percentage when a total is known, and formatted speed. Unknown totals remain indeterminate and missing sizes are never converted to zero.

Tests will use a provider with opaque child identifiers to reproduce the SAF failure before the fix, verify returned destination identifiers and cleanup, cover upload and recursive-download progress, validate speed and byte formatting, and exercise Compose accessibility state. Device verification will copy and move disposable files into a real SAF document tree. SMB behavior will be covered by the shared coordinator tests and the opt-in integration fixture when valid disposable credentials are configured.

## Delivery and release

Each change set is developed test-first on a `codex/` branch, reviewed, pushed, and opened as a pull request. Green pull requests are merged in order: file interaction, selection polish, then transfer correctness and progress. Pull request #46 is closed only after its authored change is preserved in the merged history. Issues close through explicit `Closes` references or a verified post-merge close action.

After all three pull requests land, the release workflow follows the repository release documentation. Version declarations, changelog, metadata, minified release build, full JVM and lint gates, device instrumentation, release APK installation, and a final K60 smoke test must agree before a tag or GitHub release is published. Release notes remain factual and map directly to verified changes.
