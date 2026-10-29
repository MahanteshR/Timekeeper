# Timekeeper 0.7.0 review fixes

Version 0.7.0 (code 10) addresses the 17 findings in the 0.6.0 product review and adds per-app repeat days. The historical review remains unchanged. “Addressed” below means implemented and checked by source/build analysis; device behavior still needs the checks listed after the table.

| Finding | Implemented resolution | Evidence and remaining check |
| --- | --- | --- |
| F01 — emergency consumes a whole interval | Fully borrowed intervals move into the next cooldown rather than becoming available with zero budget. Borrowing cannot exceed the next interval, including across reset days. | Core regression tests cover 1- and 2-minute intervals, late observation, and multi-day debt. Check emergency transitions on a phone. |
| F02 — old removed-app usage returns after reset edit | Expire usage under the old reset and zone before rebasing every stored package, including removed rules. Preserve current-period usage. | Removed-rule expiry and current-usage tests. Check removal/re-add around a custom reset. |
| F03 — backup failure has no retry | Automatic retries use exponential backoff from 5 seconds to 5 minutes and resume retries when pending. Previous verified copies remain. | Host tests verify retries without configuration edits and cancellation. Check revoked and restored document access. |
| F04 — unsaved timer edits disappear | Save timer drafts, repeat days, and open timer dialogs across activity recreation; confirm discarding changed drafts. | All Compose code compiled. Rotation/process recreation still needs UI validation. |
| F05 — incomplete restore preview | Expandable details include all timers, durations, repeat days, enabled state, and all global preferences. Explain strict mode's effect on retained timers. | Codec/restore tests and source review. Check the preview with ten timers. |
| F06 — stalled provider blocks recovery actions | External operations use a separate bounded executor, 15-second waits, cancellation signals and descriptor closure. Restore reads are independent. Cancel, disconnect, and folder changes do not wait for a write mutex. | Host tests simulate an interrupt-resistant provider and late results. A permanently unresponsive provider cannot be forcibly killed by the app; repeated permanent stalls may require provider/app recovery. |
| F07 — folder swap commits too early | Stage new access, create and verify the first copy, commit metadata, then release the old connection. Failed or cancelled switches retain the old connection; late operations cannot commit it. | Verified-copy tests and generation/connection state review. Android document-picker integration needs a phone check. |
| F08 — oversized helper typography | Use a shared 14 sp secondary style for restore restrictions, backup notes, dialog explanations, and validation messages. | Compose/lint pass. Check font scaling and device typeface. |
| F09 — invalid duration combinations | Daily/interval hours are 00–23 with a separate Full day option. Apply dynamic interval bounds and correct minute bounds at the cooldown maximum. Reject blank/invalid input in place. | Five PickerBounds tests, configuration/codec validation, and app compilation. Check wheel gestures and keypad entry. |
| F10 — long cooldown reset/edit errors | Cooldowns retain their original deadline across allowance resets. Edits outside strict mode adjust remaining time immediately; zero clears the old cooldown. Late observation accounts for the original expiry. | Reset, edit, borrowed-interval, and late-observation regression tests. Real reboot check pending. |
| F11 — inconsistent time formatting | Use Android's 12/24-hour formatter with explicit zones, and include Today/Tomorrow/date for availability. | Source/build check. Verify changes to phone time format and different configured zones. |
| F12 — misleading notification/protection state | Refresh permission state while setup/settings are visible, separate desired notifications from Android's effective permission, and check service heartbeat/storage health. | Source/build/lint check. Verify permission revocation and service interruption. |
| F13 — narrow alphabet touch target | Widen the gesture rail to 48 dp and reserve corresponding list space. | Source/layout check. Verify drag highlighting and 200% text size on a phone. |
| F14 — light blue contrast | Change light-theme accent to #0068CE, approximately 4.93:1 on #F4F4F4. | Calculated sRGB contrast. Check disabled and selected states on the device. |
| F15 — unnecessary history/hash work | Coalesce week history at 60 seconds; active protection queries allowance history at 30 seconds while interactive. Deduplicate configurations before serialization/hash work. | Source/build checks and existing usage-history tests. Battery/performance profiling remains a device task. |
| F16 — stale installed apps/icons and locale grouping | Refresh inventory on resume and package changes, invalidate icon caches, lazy-load visible icons, sort with locale collation and normalize section initials. | Source/build check. Verify package changes and non-Latin labels on a phone. |
| F17 — navigation, discovery, copy and portability | Track screen parents, expose restore on a fresh install, distinguish storage/strict errors, shorten and expand backup copy, roll Dashboard Today forward, and condition manufacturer advice. | Source/build check. Full navigation and date-rollover UI walk-through remains pending. |

## Repeat days and migration

- Each app can use Every day, Weekdays, Weekends, or a custom nonempty selection. Display order follows the locale; stored bits use ISO Monday through Sunday.
- A repeat day starts at the configured allowance reset time in the configured zone. On unselected reset days the rule is unrestricted. Cooldown time continues to elapse.
- Existing local timers and version-1 backups default to Every day. Backup format 2 stores the selection. Future unsupported formats and invalid day masks are rejected before mutation.
- Strict mode locks repeat days along with the existing rule settings.

## Validation

- 81 passing JVM tests: 63 core and 18 application host tests.
- Complete current-source compilation, fresh resources/DEX, Android lint with zero errors and 16 warnings, APK ZIP integrity and alignment, stable resource IDs, and version/code checks.
- Original debug signing certificate retained: `27e5091d95fe24cd8fa7e63b1b9805e6faabe25c14262f63d3dc76c73cb25f40`.
- APK size: 9,225,484 bytes. SHA-256: `cb397bdeaeade9ecf1edc7a41e2e65cd7ae7a6e1078ef06409db81187d004655`.
- The isolated API-37 emulator exited before Android booted, so no device/runtime UI pass is claimed. The user's working app and AVD were not cleared or modified for testing.

Remaining device validation is listed in README.md. No root, Shizuku, package suspension, release-signing migration, subscription wall, or separate security audit is included. The existing Accessibility guard still cannot guarantee blocking every launch frame or force-stop another app's background services. Physical cooldown/reboot/recovery validation remains required before adding Shizuku.
