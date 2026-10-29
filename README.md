# Timekeeper 0.7.1

Android app for daily app allowances, usage intervals, and cooldowns. Includes a screen-time dashboard, strict mode, optional emergency access, and local usage-history reconciliation.

## Review handoff

The owner accepted the 0.7.1 UI and confirmed on 29 October 2026 that the cooldown/reboot/recovery integration gate is complete. See [device validation status](docs/DEVICE_VALIDATION.md). Historical validation notes below describe their original build dates; this update supersedes their pending-gate status. Shizuku is still not implemented. The next step is its optional non-root exploration, with Android compatibility beta afterward. Security/release review is being handed to Claude separately.

For an independent review, clone this repository, read [CLAUDE.md](CLAUDE.md), and run the build/tests below. The handoff includes the code map, product rules, known platform limitations, previous review findings and current validation evidence. No signing keys, generated APKs, usage/backup data, screenshots, SDK paths or machine caches belong in this repository.

## Build

Requires JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.11.1. The project uses Android Gradle Plugin 8.9.2 and Kotlin 2.1.20. Point `ANDROID_HOME` or an untracked `local.properties` at the Android SDK. The checked-in Gradle Wrapper pins Gradle 8.11.1 and verifies its distribution checksum.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat`. Android Studio can open the repository root; select JDK 17. Initial dependency downloads require internet access.

If a previous incremental Kotlin compilation reports missing shared UI declarations, retry with `-Pkotlin.incremental=false`.

The source archive excludes SDK files, caches, build outputs, machine-specific settings, screenshots, and signing keys. Updating an existing installed APK requires the same signing key.

## 0.7.1 UI refinement

- Removes the screen-time/Charts use explanatory footer from the dashboard.
- Replaces wrapping weekday chips with circular day selectors inspired by the supplied Digital Wellbeing screenshot. Every day / Weekdays / Weekends form one joined, single-line segmented control. On narrow windows or enlarged text the day grid uses balanced rows, and the preset strip can scroll horizontally rather than clipping or wrapping its labels.
- Repeat-day edits are local to the dialog until Done. Cancel, Back, and an outside tap discard that dialog's changes; the editor's Save action still commits the complete timer.
- Daily allowance, interval, cooldown, repeat-day, and reset pickers share a fixed title and fixed Cancel/Done action row. Only the body scrolls. Keypad/Wheels switching moves to the duration dialog header. Wheels use larger neutral selected values, quieter neighbouring values, and no separate blue box around each column.
- Cooldowns use Days / Hours / Minutes. The previous maximum of 168 hours is exactly 7 days and remains supported without changing existing settings or backup data. Hours are a 00–23 remainder; selecting 7 days constrains hours/minutes to zero. Long duration summaries display days, and a zero cooldown is labelled Off in the timer editor.
- Keeps platform-default fonts, accessible touch targets, in-place validation, hour/minute wrapping, the separate Full day allowance option, and existing strict/backup/enforcement behavior.

The source-based comparison with Samsung and Android guidance, references, layout decisions, and device-validation checklist are in [UI_REVIEW.md](UI_REVIEW.md). These are custom Compose components informed by public guidance, not Samsung's internal application widgets or a claim of One UI 9 certification.

## 0.7.0 changes

- Adds per-app repeat days, with Every day, Weekdays, and Weekends presets. At least one day is required. Days follow the configured allowance reset time and time zone; unselected reset days are unrestricted. Existing timers and version-1 backups default to Every day. Strict mode also locks repeat days.
- Daily and interval wheels run from 00 to 23 hours and minutes wrap from 59 to 00. A separate Full day (24 hours) option preserves valid 24-hour allowances. Intervals cannot exceed the daily allowance. Cooldowns retain their distinct maximum of 168 hours, with no invalid extra minutes at that maximum. Blank, zero, and out-of-range entries stay in the dialog with a specific error.
- Standardizes helper text at 14 sp, including the strict-mode restore note. Timer drafts and open timer dialogs survive activity recreation, with a discard prompt for unsaved edits. Number-wheel rows accommodate scaled text. The alphabet rail has a 48 dp touch target; light-theme blue now has approximately 4.93:1 contrast on the light card background.
- Fixes emergency borrowing of an entire short interval, cooldown rollover across daily resets, late observation after a reset, immediate cooldown-setting edits, and stale usage returning after a removed timer and reset-time change. Cooldowns continue across reset days; a reset does not shorten a multi-day cooldown.
- Backup failures retry automatically with capped exponential backoff and retry on app resume. Cancelling, disconnecting, restoring, and choosing a different folder remain available while a document provider is stalled. External operations have bounded waits; stale completions cannot change the current connection. A new folder replaces the previous connection only after its first copy is verified and status is committed.
- Backup format 2 includes repeat days and accepts version-1 files. Restore previews every timer's daily allowance, interval, cooldown, enabled state, and repeat days, plus every global setting. The preview explains the effect of strict mode on all retained timers. Backup screens distinguish pending changes from the last successful copy and use expandable details.
- Uses the phone's 12/24-hour preference consistently, including reset, backup, and availability times. Future availability includes a day or date and identifies a differing configured time zone. Permission and protection status refresh while setup/settings are visible; blocked Android notifications have a direct settings action.
- Coalesces dashboard history refreshes and uses smaller allowance-history queries during active protection. Configuration equality is checked before encoding and hashing a backup, avoiding work on every usage tick. Installed apps and icon caches refresh after package changes and app resume; labels use locale-aware sorting and alphabet grouping.
- Back returns to the actual parent screen. Fresh installations have a direct restore entry. Save failures distinguish storage errors from strict-mode restrictions. Dashboard Today follows the new calendar day until a historical date is selected. Device-specific background guidance is conditional on the manufacturer.

The review resolution table and validation limits are in [REVIEW_FIXES.md](REVIEW_FIXES.md). This remains an Accessibility-based debug beta; brief launch-frame exposure and another app's background services are still Android platform limitations.

## 0.6.0 changes

- Settings → Back up and restore can connect a user-selected document folder. Timer changes automatically create dated JSON backup copies after a short debounce. No account, internet permission, or broad storage permission is added. A documents provider may sync the selected folder through its own account.
- Each new backup is read back and checked byte-for-byte before it becomes the latest successful backup. Only then are older copies created by this installation removed, retaining three recent copies. A provider failure preserves the previous copies and displays an error with a retry action. Disconnecting or changing folders leaves existing files intact.
- Restore previews all limits, the reset time/time zone, missing apps, and strict-mode activation before one Apply all timers action. It adds missing limits, updates matching limits, and retains unrelated existing limits. Unavailable apps retain their rules. Timekeeper and the current Home app cannot be imported as limited apps.
- Backups include daily allowance, interval, cooldown setting, enabled state, reset time/time zone, appearance, notification preference, emergency preference, and whether strict mode is currently on. They exclude usage history, live cooldown/strict deadlines, device-clock anchors, and permission grants. A restored strict setting creates a new lock until the next reset on the receiving device.
- Import is rejected while an existing strict lock is active. On an existing installation, recorded usage, accounting cursors, emergency state, and active cooldowns are preserved; changing the reset does not grant a fresh budget. Available Usage Access history is refreshed after restore. An uninstall cannot preserve runtime accounting through a settings-only backup.
- Configuration changes and an entire restore are durably committed before success is reported. Restore can also recover from unreadable local snapshot data. Imported files are bounded to 512 KB/500 rules and validated completely before mutation, including format version, field types, duplicate packages, duration bounds, nesting, UTF-8, and time zone.
- Last-successful-backup status and errors are visible in Settings. Permissions must be granted again after reinstalling. Restoring a file does not reconnect automatic folder access; choose the backup folder again after reinstalling.

### Using backup and restore

1. Open Settings → Back up and restore → Choose backup folder. Create or select a private Timekeeper folder under Documents or an available cloud documents provider. Android may prevent selecting a storage root or the Downloads root.
2. Wait for Backup saved, then verify a dated Timekeeper JSON file exists in that folder. Later timer changes update the folder automatically while Timekeeper runs. Back up now forces another verified copy. A configuration edit that has not finished backing up is not yet recoverable after uninstall.
3. To restore, choose Restore saved timers, select the latest available complete backup, review the preview, and tap Apply all timers. Enable Accessibility, Usage access, and notifications as needed. Reconnect the backup folder if this is a fresh installation.

The manual backup feature does not change the existing Android automatic-backup manifest setting. Backup files are ordinary unencrypted documents containing selected app identities/settings; users control their location and sharing.

## Roadmap

The agreed order is backup/restore, optional non-root Shizuku exploration, then an Android compatibility beta. Banking-app compatibility is out of scope. Security/release review is separate. Shizuku is not implemented and must not be enabled before cooldown, reboot, and recovery tests pass. Reliability testing continues with releases; paid-tier exploration follows the product groundwork.

## 0.5.2 changes

- Uses Compose's platform-default font family throughout the interface. Native views also retain their platform-default typeface. Removed Samsung font-file discovery and the custom fallback family so Android can select the phone's default font and supported weights.
- Text sizes, weights, and spacing remain as before. Manufacturer font settings and appearance still require a phone check; individual Samsung or Google apps may choose their own fonts and typography.

## 0.5.1 changes

- Retains the remaining-time notification while a system panel takes focus and keeps blocked-limit information readable after the handoff to Home or the explanation screen. Tracking pauses while the panel is open; locking the phone or switching to an unrelated app clears the notification.
- Replaces the large Go home button with a secondary Close action. Phone navigation remains available.
- Removes accessibility event batching and the 700 ms Home grace period. A lightweight opaque curtain is built when the service connects; it avoids icon decoding and font discovery during a blocked launch. Window events can identify a focused application before its root becomes available.
- Labels allowance totals as usage since reset and explains that dashboard totals use calendar days. A custom 11:34 reset can include yesterday's use until today's 11:34 reset.
- Guards history imports against stale snapshots, inspects cached cooldowns at the current time, and tests preserving usage when a limit is removed and re-added. Removing a limit intentionally does not erase usage.
- Circular hours wheels match the minutes wheels for daily allowances, intervals, cooldowns, and reset time. Existing duration bounds still apply.
- Uses the compatible receiver registration API on Android 12 and checks notification runtime permission only on Android 13 and later.

The faster guard still reacts to Android window events. It cannot guarantee intercepting an app before Android shows any launch frames. No package suspension or additional privileged mode is introduced.

## 0.5.0 changes

- Circular minute wheels and optional keypad entry, with explicit zero and duration-bound validation.
- Bottom Save action; reducing daily allowance adjusts an oversized interval and explains the change before saving.
- Strict mode remains visibly blue when its switch is locked. New app rules are still allowed.
- Settings displays the version reported by Android instead of a fixed string.
- Fixed-height, text-scale-aware app rows and a single scrollbar gesture handler. The letter indicator follows the visible list during both ordinary scrolling and dragging.
- Uses an installed Samsung One UI font when available, with the Android default as fallback. No font files are distributed or downloaded.
- Blocking uses a short protective overlay during a handoff to Home, then a private activity in a separate task. System panels sit above that activity, with Home underneath it rather than the limited app.

## Validation and remaining device checks

For 0.7.1, all current sources compiled and all 86 JVM tests passed (68 core and 18 app). Five new duration tests cover every supported cooldown's lossless conversion to days/hours/minutes, the 7-day boundary, hour remainders, malformed input, and a partially bounded last day. Standalone Android lint completed with zero errors and 16 existing style/backup-rule/resource/translation warnings. Some lint caches/metrics remain unwritable in the sandbox; analysis exited successfully. Fresh resource/DEX packaging, original-certificate continuity, stable resource IDs, ZIP integrity, alignment, version 0.7.1 / code 11, inclusion of the new production classes, and exclusion of their tests were checked. APK SHA-256: `27ceb0c86ce0916416951ccacaa19b6d737d60e197d0aaa5fccd3ace580e05f0` (9,241,868 bytes).

The new dialogs have not been exercised in a booted Android runtime here. The prior isolated emulator attempt failed before boot, and no real phone is attached. Verify repeat-day layout at the phone's actual display/font settings, cancel/discard/rotation, keypad with the IME open, wheel wrapping, 7-day cooldown bounds, landscape/split-screen and 200% font size. Build/lint checks are not a visual or TalkBack pass.

For 0.7.0, every current core and app Kotlin/Compose source compiled with Kotlin 2.1.20/JDK 17. All 81 JVM tests passed (63 core and 18 app). Standalone Android lint 31.9.2 completed with zero errors and 16 warnings: KTX/style, explicit platform-backup rules, an obsolete resource qualifier, and existing translation suggestions. The sandbox prevented writing some lint caches/metrics; the analysis exited successfully. Build validation used the direct toolchain because ordinary Gradle native services are restricted in this environment. Fresh resources and DEX were packaged, aligned, and signed with the original debug certificate. Version 0.7.0 / code 10, stable resource IDs, ZIP integrity, new production classes, and exclusion of test classes were verified. No new runtime permission or internet permission was added.

An isolated temporary API-37 emulator was attempted, but the emulator exited with signal 4 before Android booted. No runtime UI, system-picker, or provider-integration pass is claimed. Remaining phone checks include repeat-day transitions at a custom reset, 200% text size and rotation, 12/24-hour settings, ordinary and alphabet scrolling after an install/uninstall, denied notifications, stalled/revoked document access, switching folders, ten-timer restore, long cooldowns, reboot, and reinstall recovery on a dedicated test installation. Do not uninstall the user's working app as a test. Pure core reboot/deadline tests do not replace an Android reboot/recovery test and do not satisfy the Shizuku gate.

For 0.6.0, all current core and application sources compiled with Kotlin 2.1.20/JDK 17. All 59 JVM tests passed (46 core and 13 app backup codec/copy tests). New tests cover ten-app restoration, merging existing limits, same-install usage/cooldown preservation, reset changes, strict-mode rejection and reactivation, missing apps, Unicode, invalid/truncated/oversized/newer-format backups, and failed/partial backup writes. Standalone Android lint 31.9.2 against the current sources and fresh compiled jars completed with zero errors and 16 warnings (KTX/style, explicit platform backup rules, obsolete resource qualifier, and existing translation suggestions); the sandbox could not write some Android lint caches/metrics. The ordinary Gradle invocation was blocked by the Mac native-services sandbox restriction, so compilation, tests, resources, DEX, alignment, and signing used the direct toolchain route. Version 0.6.0 / code 9, signature continuity, resource IDs, ZIP integrity, inclusion of backup classes and exclusion of test classes were verified. No new runtime permission was added.

0.6.0 needs real-device checks for the system folder/file pickers, document-provider persistence after reboot, denied/revoked folder access, backup status and rotation, switching theme/text size, restoring ten timers with and without Usage Access, strict-lock expiry, and reinstalling/clearing data on a test device after verifying the external backup. Automated checks do not constitute a completed phone reinstall or provider integration test. Do not uninstall a user's working installation as an automated test.

For 0.5.2, all current core and application sources compiled, the 36 existing JVM tests passed, and the APK was signed with the original debug certificate. Version 0.5.2 / code 8, stable resource IDs, APK alignment and signing certificate were checked. System-default font appearance and changes to phone font settings need a real-device check. Full lint results below refer to 0.5.1.

For 0.5.1, Gradle `:core:test :app:compileDebugKotlin :app:lintDebug` passed with Kotlin 2.1.20 and JDK 17. All 36 JVM tests passed. Full Android lint reported zero errors and 10 warnings (style/API-modernization, translation, and explicit backup-rule configuration). The Mac build also recompiled all core and application sources and reran the 36 tests. The 0.5.1 test APK is signed with the same original debug certificate as 0.5.0; package version, manifest, stable resource IDs, APK alignment, and signature were verified. Phone validation of the changed transitions remains pending.

For the previously delivered 0.5.0 build, 29 JVM tests passed and all application Kotlin/Compose sources compiled. APK signature, resource IDs, alignment, version, and manifest declarations were verified. Targeted Android lint checks covered API use, manifest declarations, exported components, component instantiation, and permissions.

Device checks remain: notification shade while a limited app is active, changing or disabling a limit while the shade is open, circular hours/minutes and keypad entry, removing/re-adding around reset, ordinary/fast alphabet scrolling, 200% text size, installed Samsung font appearance, strict-mode colour, blocked-app reopening, navigation buttons/gestures, emergency access, and daily reset. Core policies have regression tests; Android activity transitions need real-device verification.

This is a debug test build. Release signing and a dependency vulnerability review remain pending. Root, Shizuku, device-owner provisioning, and package suspension are not implemented. The app cannot grey out Samsung launcher icons or guarantee stopping another app's background playback.
