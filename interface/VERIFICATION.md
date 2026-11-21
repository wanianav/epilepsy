# Android build verification

## Version 0.3.2

- All 34 automated tests passed. They cover automatic start when the app opens,
  boot and update broadcasts, a system service restart, resuming after an explicit
  Start, and keeping the sensor active with the screen off.
- The update ran on the connected Android 13 phone. Opening CalmGuard started
  monitoring without tapping **Start phone motion**. The phone continued reporting
  accelerometer readings with the screen off, and the session was still active
  after the test.
- The isolated Android emulator resumed monitoring after a real reboot.
  Android recorded that the boot broadcast started the foreground service.
  The emulator also verified that **Stop phone motion** stayed in effect when
  reopening CalmGuard and that **Start phone motion** enabled monitoring again.
- Android lint completed with zero errors and 34 warnings.
- The debug-signed Android 9+ APK is at `releases/CalmGuard.apk`. It uses the
  same signing key as the preceding build and passed Android Signature Scheme
  v2 verification. Its SHA-256 is
  `89f283090f6fffe48e9d74df0f070381753bee9d96e99cccab40f14bfc6d0779`.

## Version 0.3.1

- All 30 automated tests passed. The repeat-motion tests confirm that
  acknowledgement leaves the sensor registered, continued shaking produces one
  check-in, and a fresh shake after the phone settles starts the next one.
  Tests also cover the repeat-alert limit after an unanswered check-in.
- On the connected Android 13 phone, two shakes started two check-ins in one
  motion session. Both acknowledgements left the service active. The second
  check-in began 9.4 seconds after acknowledgement of the first. Caregiver SMS
  was off during this test.
- Android lint completed with zero errors and 32 warnings.

## Version 0.3.0

Verified on 21 November 2026 (Asia/Karachi) with Java 17 and Android SDK 35 build tools.

- The Android 9+ APK assembled and passed signature verification. The package
  name and signing key match version 0.2.0 for an in-place update.
- All 28 automated tests passed, covering phone motion, service lifecycle, SMS
  handling, sample data and model parity, display controls and the Compose UI.
- A separate Android emulator ran the app with injected accelerometer readings.
  Shaking while CalmGuard was in the background posted a system check-in
  notification. Opening CalmGuard displayed the live countdown. Tapping
  **I'm OK** cleared the notification and wrote the cancellation to History.
  Stopping the session removed the persistent phone-motion notification.
  The emulator's non-wake-up sensor paused on screen-off and resumed on screen-on.
- The signed build also ran on a physical Android 13 phone. Shaking the phone
  started a movement check-in. Tapping **I'm OK** two seconds later cleared the
  alert, and History recorded the cancellation. Automatic caregiver texting
  was off during this test.
- Tests exercise an approved SMS request on expiry, Android's no-service
  result, cancellation, changed recipients, stopped sessions, screen-off
  pause for a non-wake-up sensor and interruption recovery. SMS tests use
  simulated Android sending, without contacting a recipient.
- Kotlin features and forest scores match Python/scikit-learn parity vectors.
- Interaction tests also cover caregiver number setup and the offline alert demo.
  The live motion screen and countdown were inspected on the emulator.
- Device tests cover permission gating, approved foreground-service startup,
  screen-lock unlinking, Stop action, bounded touch-through overlay parameters,
  brightness recovery across session recreation, permission revocation and preservation
  of newer user brightness changes.
- Android lint completed with zero errors and 32 non-blocking warnings.
- Earlier dataset verification matched the UAL CSV SHA-256 to the publisher's
  checksum and checked the WISDM ZIP and 51 extracted watch files.
- Original EEG file remains outside the motion model.

Model findings: primary held-out participant 3 has 100% simulated-movement sensitivity,
95.9% specificity and F1=0.95 at threshold 0.70 (136 windows). Four separate volunteer
holdouts have sensitivities 83.3%, 8.7%, 81.3% and 100%. The WISDM challenge has 7,672
above-threshold windows out of 94,771 (8.1%). These results do not validate clinical
seizure detection, event-level alert sensitivity or false alarms per day.

The automated app tests include Robolectric. Battery effects, false alerts
during everyday activity, caregiver SMS over a carrier and clinical seizure
detection remain unverified.
