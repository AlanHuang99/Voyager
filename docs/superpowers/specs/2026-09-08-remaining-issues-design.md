# Remaining GitHub issues

The September 8 triage left 23 open issues and counter PR #79. The user authorized implementing all remaining fixes and features, device testing, and GitHub changes. Each subsystem will receive focused regression coverage before the integrated unit, lint, debug, release, and K60 gates. Android 8/API 26 remains the minimum supported version. Existing user files, credentials, bookmarks, and settings must be preserved.

## Remote access and opening

SMB will advertise encryption support and use it when negotiated, preserving SMB2 compatibility. A real Samba matrix will cover required/optional encryption, older dialects, wrong credentials, discovery, and direct-share operations (#84). WebDAV descriptors will acquire independent leases on a token: closing one descriptor releases only that lease, an idle token expires after ten minutes, and explicit revocation prevents future opens without disrupting other tokens. Active reads refresh activity and expired idle sources are swept periodically. WebDAV files of any MIME type can use the read-only seekable provider; unsupported range servers retain explicit Download/Cancel fallback (#56, #87).

SFTP connection editing will expose the exact host/port saved fingerprints and a confirmed Forget action that removes only those pins. It will not silently replace a changed host key or erase other connections (#80). Connection protocol selection must support D-pad/Enter, item selection, default-port updates, dismissal, and restored focus (#66).

## Transfers

A foreground transfer service will own the lifetime of user-started work and publish ongoing progress plus a cancel action. Work and state must survive Activity recreation; explicit process termination must never be reported as success. Cancellation will reach stream reads/writes, close resources, remove incomplete targets, retain move sources, and propagate CancellationException rather than continuing a batch (#71, #78). Counters will explicitly describe completed items and retain a terminal completion/failure state rather than adding one to completedItems (#72, #79).

Destination conflicts will suspend a batch for Replace, Skip, or Cancel, with source/destination size and time and an apply-to-all option. Replacement will stage a new sibling, preserve the existing destination until copying succeeds, and roll back failed promotion where the provider supports rename. Type conflicts and replacing a source with itself must fail without deleting data (#77).

## Browsing and local actions

Bookmark labels/icons will reflect persisted state, and Home will expose bookmark removal. Pull-to-refresh will share the normal reload path. Long-press drag selection will use stable visible item identities across list/compact/grid, support edge scrolling, and preserve haptics and keyboard selection. Navigation transitions will follow direction, retain opaque theme backgrounds, and respect disabled animations (#69, #74, #88, #76).

Video thumbnails will use bounded local/SAF metadata decoding. Office previews will use bounded embedded OOXML preview images with icon fallback when absent. Trash will construct preview items using stored payload paths and original metadata (#62, #83, #70). Quick-access categories will list matching files across accessible storage while retaining ordinary folder navigation. Duplicate discovery will hash size-matched local files, keep missing/unreadable items distinct, require explicit selection, and revalidate content before deletion (#68, #73).

Local folder pinning will validate launch destinations and preserve permission checks. Ringtone/notification-tone actions will copy selected local/SAF audio into an appropriate MediaStore destination before invoking Android settings; unsupported formats or denied settings access must produce actionable feedback (#75, #64).

## Root and repository infrastructure

Root browsing will be explicitly enabled, use a separate privileged provider, quote every path argument, stream file bytes, and never escalate ordinary local browsing. Root denial and read-only mounts must remain visible failures; tests will use disposable paths (#67). GitHub Discussions will provide a feedback channel (#82). Crowdin configuration will map Android strings to locale resources and retain resource/build checks. Hardcoded Home display labels will move into resources. Crowdin activation requires an actual project and token configured through repository secrets, and will not be claimed complete without a verified connection (#86).

## Delivery

Implementation is split into reviewable subsystem commits and pull requests. Issues close only after their requested behavior is implemented and verified. The rejected counter PR will be closed as superseded once its replacement is merged. Public descriptions will distinguish device/server checks from untested environments. No release version or tag is needed to deliver these fixes.
