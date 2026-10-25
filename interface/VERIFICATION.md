# Prototype verification

Version 0.2.0 verified on 25 October 2026 (Asia/Karachi) using the isolated Java 17 / Android SDK 35 build tools.

- Debug APK assembled successfully.
- 17 tests passed: 3 model/data tests, 5 state/alert tests, 8 device-display tests
  and 1 Compose interaction test.
- Kotlin features and forest scores match Python/scikit-learn parity vectors.
- Interaction test visits the overview, Safe Mode, samples, event history, local alert
  countdown, device-display permission controls and research details. Seven screenshots
  were captured; new device controls and updated navigation were visually inspected.
- Device tests cover permission gating, approved foreground-service startup,
  screen-lock unlinking, Stop action, bounded touch-through overlay parameters,
  brightness recovery across session recreation, permission revocation and preservation
  of newer user brightness changes.
- Android lint completed with 0 errors and 26 non-blocking warnings (dependency
  update notices, KTX suggestions, backup-rule modernization and a false positive
  concerning component-based stopService Intent matching).
- UAL CSV SHA-256 matches the publisher's checksum.
- WISDM archive passes ZIP CRC checks; 51 watch accelerometer files are extracted.
- Original EEG file remains outside the motion model.

Model findings: primary held-out participant 3 has 100% simulated-movement sensitivity,
95.9% specificity and F1=0.95 at threshold 0.70 (136 windows). Four separate volunteer
holdouts have sensitivities 83.3%, 8.7%, 81.3% and 100%. The WISDM challenge has 7,672
above-threshold windows out of 94,771 (8.1%). These results do not validate clinical
seizure detection, event-level alert sensitivity or false alarms per day.

No physical phone, Android emulator or wristband was used for these checks. UI tests
run under Robolectric with native graphics, so physical-device testing remains useful.
The app uses recorded movement samples and never sends messages. The optional
device-display feature requests overlay, notification and (only for the optional
brightness preset) settings access. It does not capture or inspect the screen.
Real-device overlay, notification and brightness behavior has not been validated.

Installable prototype: releases/CalmGuard-prototype.apk (debug signed, Android 9+).
Source project and Gradle wrapper are in this interface directory.

APK signing verification passed (APK Signature Scheme v2).
SHA-256: d2b67389c3438263e7911459e0dd55ccc0b547ae15c633191ee89e2332177545
