# Shizuku lab: 0.8.0-explore.1

This branch adds an opt-in, manual, non-root experiment under Settings → Shizuku lab. It evaluates four capabilities before selecting a production enforcement design. It does not connect Shizuku to the allowance engine, remove Accessibility, promise a grayscale Samsung icon, or claim cross-device certification.

## Scope and decisions

| Goal | Experiment | Evidence still required on a phone |
| --- | --- | --- |
| Prevent reopening | Suspend one selected app for 10 seconds | Launcher appearance, tap/deep-link/notification reopening, playback, actual suspension and recovery |
| Stop background playback | Separate stop-app and force-stop actions | Audio/PiP termination, notifications, jobs/alarms, restart after explicit launch |
| Reduce Accessibility dependence | Sample privileged focused-root-task information every 500 ms for 30 seconds | Normal app switches, notification shade, lock screen, multiwindow and PiP; task focus is not proof of user interaction |
| Confirm state and restore access | Inspect suspension/stopped state, token-marked ownership, private recovery journal, daemon rollback | UI/process death, Shizuku loss, permission revocation, reboot, uninstall/reinstall of target, ownership changes |

Existing timer configuration, backups and Accessibility enforcement continue as before. The owner-confirmed earlier integration gate is recorded in DEVICE_VALIDATION.md; it does not certify the new Shizuku path. No Shizuku phone tests have passed yet.

## Try it

1. Save a timer backup. Install this APK as an upgrade using the original signing key. Keep a working recovery route before testing a suspension.
2. Install Shizuku from its official distribution and start it through wireless debugging. Root-backed Shizuku/Sui is deliberately unsupported. This prototype supports the primary Android user only (not work profiles/secondary users).
3. Add a nonessential third-party app in App limits and give it enough remaining time that the existing Accessibility blocker will not obscure the experiment. Save work in that app.
4. Open Settings → Shizuku lab → Connect and check recovery. Grant Timekeeper permission in Shizuku. Choose the test app and inspect its state. Capabilities that cannot be probed are disabled; inspect the diagnostic report.
5. Run a suspension trial. Try opening the app from Home, a deep link or its notification. Check its launcher appearance. Verify access returns and the recovery record disappears after verification. The helper attempts release after 10 seconds using monotonic time, independently of the Timekeeper UI.
6. Play test audio, then compare stop-app and force-stop in separate runs. Relaunch the target explicitly between runs. Both end processes. Stop-app preserves scheduled work; force-stop applies Android's stopped state and can cancel pending work. Neither is a timer with an automatic restart.
7. Start task observation, switch apps, pull the notification shade, try split screen/PiP, lock/unlock, then return within/after 30 seconds. Inspect/copy the report. It contains package/activity names and elapsed timestamps, up to 60 in-memory samples. It does not read screen content or charge usage.
8. Confirm no pending suspension before Disconnect lab. Revoking Shizuku access is separate from the app's Accessibility permission.

## Recovery and its limits

Suspension is Android package-manager state and can outlive the Timekeeper UI, helper, Shizuku server and reboot. There is no unconditional 10-second safety guarantee when privileged execution disappears. Restart Shizuku and reconnect to Timekeeper's lab to recover a pending trial. Never uninstall Timekeeper or clear its data during a pending suspension; a settings backup intentionally does not carry live recovery state.

For an ADB shell attached to this device, the last resort is the exact single-package command shown by the lab:

```sh
pm unsuspend --user 0 com.example.testapp
```

Replace only the package identifier with the affected app. This removes that package's suspension attributed to `com.android.shell`, including a restriction from another shell-based tool. It does not remove a device administrator's independently owned restriction. Do not issue a bulk unsuspend. A computer terminal can use `adb shell pm unsuspend --user 0 <package>` instead.

The helper does not take over a pre-existing suspension. It places a random lease token in suspension launcher extras and releases only a matching token or confirms the app is already unrestricted. Unknown/changed ownership keeps the recovery record and disables further mutation trials. Another shell tool shares the shell suspension identity; marker reads and package-manager mutations are not atomic across those tools. Do not run another freezer on the same target during this experiment. Launcher extras may be unavailable/differently implemented on an OEM build; suspension is then disabled or recovery remains explicit rather than guessing ownership.

The private journal is committed before suspension IPC. It has no wall-clock expiry and is never discarded merely because time passed. Recovery can use this record after process/helper restart, and the UI can adopt a surviving helper's pending record before allowing further tests. A recovery request cancels that token so a late start cannot apply an untracked suspension. Failed storage writes disable further mutations. An unreadable journal is retained; diagnostics/manual recovery remain available. If the target is uninstalled, absence can be verified as already clear.

## Privilege boundary

- Shizuku API/provider 13.1.5; automatic Sui initialization disabled in a custom provider. Client and helper reject root UID 0; helper accepts only shell UID 2000 and the primary user.
- An AIDL user service exposes only configure, inspect, timed suspension, token release, two fixed stop operations, bounded observation and report. There is no arbitrary command string, generic Binder proxy, filesystem API or networking endpoint.
- Every experiment checks the Binder caller is Timekeeper's UID. The helper is not an exported Android service. The Shizuku provider uses the upstream `INTERACT_ACROSS_USERS_FULL` restriction.
- Mutation targets must be among the app's current limited packages and must be enabled, primary-user, third-party apps. Timekeeper, Shizuku, shell, Settings, SystemUI and all resolved Home apps are protected. Syntax validation precedes fixed argument-list process execution; there is no shell interpolation.
- Calls have bounded client waits. Fixed commands have a 4-second process deadline and capped output. A timed-out Binder call may finish later, so suspension retains its journal and recovery uses token cancellation.
- The app has no INTERNET permission or network requests. The privileged helper inherently has shell permissions; the absence of the ordinary app's INTERNET permission must not be presented as confinement of shell code. User-initiated Open Shizuku can open the browser to the official download page.
- Only the private journal persists Shizuku state. Capabilities, state inspection and observation samples stay in memory. Nothing uploads automatically. A copied diagnostic report identifies apps/activities; review before sharing.

## Required device evaluation (all pending)

Record Android/One UI build, Shizuku version/UID, APK version and diagnostic capability results. For each scenario record success/failure, actual recovery time and package-manager state after recovery.

| Scenario | Required result |
| --- | --- |
| Shizuku missing/stopped/denied/root-backed | Clear status, no mutation, ordinary timer mode continues |
| System, Home, disabled, another-user or pre-suspended target | Rejected without changing existing state |
| Normal 10-second trial | Restricts reopening, then verifies release; measure launcher/playback effects |
| Close UI / rotate / kill Timekeeper process mid-trial | Daemon recovery continues; reconnect verifies journal and actual state |
| Stop Shizuku / revoke permission mid-trial | Pending state remains visible; restart/regrant/reconnect restores only the owned restriction |
| Reboot mid-trial | Existing mode tested separately; restart Shizuku and reconnect recovers package suspension; no false automatic-recovery claim |
| Force-stop Timekeeper mid-trial | Helper recovery tested; reopening Timekeeper and reconnect verifies release |
| Remove/re-add timer or uninstall target mid-trial | Recovery remains possible; unrelated restrictions untouched; absent app treated as clear |
| Another shell tool changes ownership | No blind unsuspend; persistent recovery explanation/manual path |
| Binder timeout, late completion, repeated taps, failed journal commit | No untracked second suspension; tests remain blocked pending verification |
| stop-app versus force-stop | Record audio, PiP, notifications, stopped flag and next explicit launch separately |
| Task samples with shade, lock, multiwindow/PiP | Measure false foreground classifications before designing an Accessibility replacement |
| Disconnect then ordinary timer use | No lingering helper trial; existing cooldown/accounting remains correct |

No attached device was available during authoring. The lab is evaluation tooling, not production enforcement. A production design must first settle durable scheduling and recovery when Shizuku is absent, shell-owner coexistence and foreground accounting. Do not merge it as a guarantee that apps stay blocked/recover automatically through reboot.

## Build evidence and dependencies

The experimental APK compiles current Kotlin, generated AIDL and four upstream Shizuku AARs through the cached direct Android toolchain, using the original debug signing certificate. This is not a clean-clone Gradle/CI result. Independently run the standard Gradle checks in README.md. Validation: 105 host tests passed (85 core, 20 app); standalone Android lint reported 0 errors / 18 warnings. Host tests cover policy, ownership, monotonic rollback, failed releases, partial apply, reconnect, late-start cancellation and journal corruption. Android Binder/permission/OEM behavior is not covered by host fakes.

Pinned Maven Central AAR SHA-256 values checked against published checksums:

| Artifact 13.1.5 | SHA-256 |
| --- | --- |
| api | `4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b` |
| provider | `b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3` |
| aidl (transitive) | `33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963` |
| shared (transitive) | `4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635` |

Upstream MIT notice is included as app/src/main/assets/licenses/Shizuku-API.txt. The SDK/JARs/signing keys are not vendored.

Primary references used for the design:

- [Shizuku API and UserService documentation](https://github.com/RikkaApps/Shizuku-API)
- [Official Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
- [AOSP PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)
- [AOSP SuspendPackageHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/SuspendPackageHelper.java)
- [AOSP ILauncherApps](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ILauncherApps.aidl)
- [AOSP LauncherAppsService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)
- [AOSP ActivityManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)
- [Android 15 stopped-state changes](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)
