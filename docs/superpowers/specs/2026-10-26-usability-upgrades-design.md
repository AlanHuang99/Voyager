# October usability upgrades

Implement the accepted issue triage for #159 and #157 and integrate translation PR #156. Leave PR #158 unchanged.

## Quick Access sorting

Add the browser's Name, Size, Date, and Type choices and ascending/descending direction to every Quick Access category. Save one category-sort preference separately from the ordinary browser preference, retain alphabetical ascending as the initial default, and allow Date descending for recent files. Date means last modification time. Apply sorting to every published result set, including partial and cancelled scans, with deterministic filename/path tie breaks. Changing sort must not restart the filesystem scan.

## Hide and unhide

The user selected ordinary Hide/Unhide actions. Add those actions for individual direct-local files and folders in row menus and single-selection overflow, including Grid through selection. Hide prepends a dot; Unhide removes leading dots. Both actions use the existing no-overwrite rename operation. Existing Show hidden files controls visibility. Do not offer an unusable target name or a hide action for providers whose hidden-file semantics differ. This feature changes visibility, not access permissions.

## Integration and verification

Reuse existing Compose menus, DataStore preferences, and file-operation handling. Keep Android 8.0 support and add no dependencies. Include English resource strings and preserve existing translations through the repository preparation script. Verify ordering, persistence, scan updates, hidden-file visibility, collision handling, and exact file contents. Run the complete JVM/lint/debug/release gate and focused instrumentation on K60 5ea17b71. Smoke-test a minified build using an isolated test application ID so the user's installed release and its data remain intact. Save device evidence and update user-facing and testing documentation. Work remains local until publication is explicitly authorized.
