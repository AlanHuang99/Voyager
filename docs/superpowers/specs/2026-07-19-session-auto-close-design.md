# Session Auto-Close Design

## Problem

Voyager keeps browser sessions and their local, SAF, FTP, SFTP, SMB, or WebDAV providers alive for the lifetime of the running app process. Users who leave Voyager in the background may therefore retain network connections longer than intended. Issue #20 requests an optional inactivity timeout that closes sessions automatically.

## Requirements

- Keep automatic closure disabled by default.
- Let users choose 5, 15, 30, or 60 minutes while retaining the last choice when the toggle is disabled.
- Define inactivity as time during which Voyager is fully backgrounded and not visible.
- Use a monotonic clock so wall-clock changes cannot trigger or postpone closure.
- Close every browser session that was already open when Voyager entered the background once the configured interval has elapsed.
- Preserve a session created by an Activity Result callback while Voyager is returning from the system picker.
- Never interrupt an active file operation. If the interval expires during an operation, close sessions immediately after the operation finishes.
- Return an open Browser destination to Home after automatic closure while leaving unrelated Settings, Connections, or Trash navigation intact.
- Disconnect each provider, clear stale clipboard references, and reset browser state without changing display preferences.

## Considered Approaches

### User-input idle timer

Resetting a timer for taps and key events would make visible sessions expire while users are reading or while long provider work is active. It would also require broad event plumbing across Compose. This does not match the safer interpretation raised in the issue discussion.

### Scheduled background task

WorkManager or an alarm could wake the app at the exact deadline, but sessions exist only inside the current process. If the process dies, providers are already gone. Waking a background process solely to close in-memory resources adds complexity without improving correctness.

### Compare background and foreground timestamps

MainActivity records `SystemClock.elapsedRealtime()` in `onStop` and asks the view model to evaluate the interval in `onResume`. Android calls `onStop` when the activity is no longer visible, and elapsed realtime is monotonic and includes deep sleep. This approach needs no background wakeup and is the selected design.

## Design

`SessionAutoCloseTimeout` is a persisted enum with labels and durations for 5, 15, 30, and 60 minutes. `PreferencesManager` stores an enable flag and the selected enum name. `FileBrowserViewModel` exposes both as eager StateFlows and supplies setters for Settings.

`SessionAutoCloseTracker` is a pure Kotlin state machine. It records the most recent background timestamp, compares the elapsed duration on foreground, and returns whether sessions should remain open, close now, or close after an operation. The view model snapshots the IDs of sessions present at `onStop`, so an SAF session created by the returning picker callback before `onResume` is not treated as stale. If the timeout expired while an operation is running, the operation launcher consumes the snapshotted IDs from its `finally` block after changing the operation state to Idle. Disabling the preference cancels deferred closure.

`MainActivity.onStop` reports the monotonic background timestamp. `onResume` reports the foreground timestamp after Activity Result callbacks have had an opportunity to start a picker-driven upload. The Activity remains responsible only for lifecycle observation; timeout policy and session mutation stay in the view model.

Automatic closure removes and disconnects the snapshotted session providers, clears provider-backed clipboard and snackbar state, and preserves any session created while returning from a picker. If no sessions remain, it replaces the active provider with a fresh local provider and clears transient browser contents. It then increments a closure generation. `AppNavigation` observes that generation and navigates to Home only when Browser is the current route and no session remains. The generation is an event counter rather than a one-shot boolean, so each closure is observable without resetting shared state.

## Testing

- Unit-test timeout parsing, labels, and duration values.
- Unit-test below-threshold, exact-threshold, disabled, negative-clock, deferred-operation, consumed-pending, and canceled-pending tracker behavior.
- Add an Android view-model test that persists the setting, opens a local session, simulates an expired background interval, and verifies that sessions and clipboard state are cleared and the closure generation increments.
- Add a Settings Compose test that enables auto-close, changes the timeout, and verifies persistence through the view-model flows.
- Add an AppNavigation Compose test that verifies an automatically closed Browser returns to Home.
- Add an AppNavigation regression test that creates a session between the background and foreground callbacks, then verifies that only the pre-existing session closes and Browser remains open.
- Verify that automatic closure clears stale snackbar state before a later Browser can consume it.
- Run the full unit, lint, debug assembly, release assembly, and connected-device test gates.

## Non-goals

- Sessions are not restored after process death.
- The app does not wake itself at the timeout deadline while backgrounded; it closes retained sessions when it next becomes active.
- Individual sessions do not have separate timeouts.
- Visible user-input inactivity is not measured.
