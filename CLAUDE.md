# Timekeeper review handoff

## Project and current status

Timekeeper is a native Android app for foreground app allowances, intervals, cooldowns, repeat days, strict mode and local configuration backup/restore. This exploration branch: 0.8.0-explore.1, code 12. Stable main: 0.7.1, code 11. Kotlin 2.1.20, Compose, AGP 8.9.2, Gradle 8.11.1, JDK 17, minimum Android API 31, compile/target API 35.

On 29 October 2026 the owner accepted the current UI and confirmed that the previously agreed cooldown/reboot/recovery integration gate is complete. See docs/DEVICE_VALIDATION.md. This is an owner-reported result, not an attached automated device-test log. Historical review documents retain the validation state when they were written.

This branch implements a manual, optional non-root Shizuku lab, documented in docs/SHIZUKU_LAB.md. It is not connected to automatic timer enforcement and has no phone validation yet. Review the privilege boundary, suspension ownership and recovery failure paths before production integration. Root, device-owner provisioning and subscriptions remain out of scope. Android compatibility beta follows exploration.

## Build and test

Install JDK 17 and Android SDK Platform 35 / Build Tools 35.0.0. Configure the SDK using ANDROID_HOME or an untracked local.properties containing sdk.dir. The checked-in wrapper pins Gradle 8.11.1 and its distribution SHA-256. Initial dependency downloads require internet access.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use gradlew.bat. Android Studio can open the repository root and use JDK 17. Debug output: app/build/outputs/apk/debug/. No signing key is included. A newly generated debug key will not upgrade the owner's installed APK; use a separate test device/emulator instead of uninstalling the working app.

The delivered 0.7.1 build compiled all current sources and passed 86 JVM tests (68 core, 18 app) and standalone Android lint with zero errors / 16 warnings. It used a direct cached toolchain because native Gradle services were restricted in the authoring sandbox. Do not describe that as a clean-clone Gradle/CI pass. The wrapper enables a normal independent build; verify it in the review environment. No CI result is claimed.

The experimental branch passes 105 host tests (85 core, 20 app) and standalone lint with zero errors / 18 warnings. Its current-source Kotlin/AIDL APK uses the same direct cached toolchain; no Shizuku runtime result or clean Gradle/CI pass is claimed. See docs/SHIZUKU_LAB.md for the pending phone tests.

## Code map

- core/src/main/kotlin/dev/timekeeper/core/: allowance engine, monotonic/reboot-aware deadlines, usage reconciliation, rule changes, strict policy, restore merge/validation and duration bounds.
- core/src/test/: JVM policy and regression tests.
- app/src/main/java/dev/timekeeper/app/data/: local store, Android usage reader, installed apps, backup codec and document-provider I/O.
- app/src/main/java/dev/timekeeper/app/shizuku/ and app/src/main/aidl/: shell-only manual experiment, fixed AIDL surface, token-marked suspension, helper rollback and reconnect recovery.
- app/src/main/java/dev/timekeeper/app/service/: Accessibility monitor and blocking overlay.
- app/src/main/java/dev/timekeeper/app/BlockActivity.kt: blocked-app explanation/emergency flow.
- app/src/main/java/dev/timekeeper/app/ui/: Compose UI, settings, timer editor, dashboard and backup screens.
- app/src/test/: backup codec, verified-copy and cancellable/retrying I/O host tests.
- REVIEW_FIXES.md and UI_REVIEW.md: prior findings, implemented changes, rationale and validation limitations.

## Review priorities

1. Accounting correctness: foreground-only charging, system panels, missing/stale history, remove/re-add, custom reset/time zone, DST, clock changes, reboot and emergency borrowing/debt.
2. Accessibility blocking: exported component boundaries, service/overlay lifecycle, recovery on revocation/interruption and safe app transitions. A brief target-app launch frame and another app's background services are existing limitations of the current mode. Banking apps that reject Accessibility are outside product scope.
3. Persistence/restore: crash/storage failures, complete validation before mutation, strict-mode restrictions, merge semantics and preserving current usage. Backups contain selected app identities/settings; usage history, live deadlines and Android permission grants are excluded. Files are ordinary unencrypted documents in a user-chosen location.
4. Document providers: untrusted/truncated/oversized data, cancellation, bounded waits, late completion, reconnect/disconnect races and retaining the old verified backup connection until a new destination is proven usable.
5. Shizuku lab: private journal committed before suspension IPC, late starts after cancellation, helper/UI/server death, root rejection, per-call UID checks, protected targets, marker API availability, other shell tools sharing the suspension owner, permission revocation and recovery after reboot. Shell code has broad privileges despite the ordinary app lacking INTERNET permission. Task focus must not be treated as proven foreground usage. See docs/SHIZUKU_LAB.md.
6. Privacy/release: manifest permissions and exported components, accidental backup/device-transfer exposure, dependency/build integrity, debug versus release signing, sensitive logging and production readiness. The app does not request INTERNET permission. A separate security/release review is still pending.
7. UI/accessibility: TalkBack names/state/focus, 200% text, narrow/landscape layouts, modal Cancel/Done semantics and keyboard visibility. The owner's current Galaxy UI acceptance is not a cross-device accessibility pass.

Product rules: repeat days follow the configured reset/time zone; unselected reset days are unrestricted. Active cooldowns survive daily resets. Cooldowns support 0–7 days; the Days/Hours/Minutes UI preserves existing whole-minute values. Strict mode locks existing timer changes until its deadline.

## Requested review output

Report findings before making changes. For each substantive finding, include severity, exact file/function, evidence, a concrete trigger or reproduction, user impact and a proposed fix. Separate reproduced failures from hypotheses and distinguish host tests, actual Android runtime tests and source-only checks. Prioritize correctness and release risks over style-only churn. Do not commit credentials, signing keys, SDK paths, usage data, screenshots, build outputs or machine caches.
