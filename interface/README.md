# CalmGuard for Android

A Kotlin and Jetpack Compose app for phone-motion check-ins and accessibility
controls, with recorded samples for research. Android 9 (API 28) or later is required.

## Phone motion and caregiver texts

1. Open CalmGuard and allow notifications. Phone motion starts automatically.
   The persistent Android notification shows whether the sensor is listening and
   lets you turn off monitoring. **Start phone motion** turns it back on if you stop it.
2. Strong repeated phone shaking starts a 20-second check-in with **I'm OK** in
   both the notification and the app. Tapping **I'm OK** closes the check-in and keeps
   the motion session running. History records the check-in and its outcome.
3. To set up caregiver texts, save up to three agreed recipients in
   **Caregiver settings**. You can open a manual text to the first caregiver from
   Overview. Enable **Automatic caregiver texts** and grant SMS permission if
   you want one SMS to each saved recipient after an unanswered check-in.
   Automatic texting starts off and switches off when the saved numbers change.

The movement check uses the phone's three-axis accelerometer to detect sustained
acceleration reversals. It uses a filter to estimate gravity, a strong-motion threshold
and at least six reversals over roughly two seconds. After **I'm OK**, the next check-in can
start once the phone settles and detects a new shake. A two-minute wait after an
unanswered check-in limits repeat alerts and texts. These are initial engineering
settings. They have no clinical validation or measured
false-alert rate on physical phones. Everyday movement can trigger a check-in, and the
phone may be still or away from its user during a seizure.

When enabled, the motion session can continue while other apps are open. It
starts after a reboot once Android unlocks the app's data and can resume after an
app update or a system interruption. If the accelerometer cannot wake the device
to report readings, keeping the processor awake while the screen is off can use
more battery. Deep sleep and manufacturer battery restrictions can still
interrupt readings. After a force-stop, reopen CalmGuard to resume monitoring.
An interrupted check-in is recorded in History. The next alert needs fresh motion.

An automatic message includes the time and asks the recipient to call. A default
SMS SIM and mobile service are required. Android restricts the automatic SMS
permission on some installs. Opening Messages for manual texting uses its own
permissions. CalmGuard records the Android send outcome when available. The
outcome does not verify that a caregiver received or acted on the text. Numbers
stay on the device, and the app stores event summaries rather than raw live sensor
readings. Messages contain no location or past event history.

## Features

- Overview with live phone-motion controls and persisted Safe Mode preferences.
- Safe Mode controls and non-flashing scripted autoplay, motion and feed-update tests.
- Five real-recording replay clips: UAL other movement and simulated shaking, plus
  WISDM walking, brushing teeth and clapping.
- Actual on-device Random Forest inference with source labels and model scores.
- Play, pause, reset, sample selection and optional replay-triggered test alerts.
- Twenty-second cancellable local alert countdown and a caregiver-name preview.
- Persistent local event history, category filters, CSV export and confirmed deletion.
- Research details showing the held-out evaluation, all four volunteer folds,
  external everyday-movement challenge, source assumptions and limitations.
- Optional display dimming over supported apps, with a persistent Stop notification.
- Optional system-brightness preset with previous-setting restoration.
- Shortcuts to Android's accessibility and display settings.

The research sample alerts stay in the local preview and cannot activate the
live caregiver-text flow. The app has no Internet permission. The display demos
use still media.

## Display controls outside CalmGuard

Version 0.2 adds optional device display controls under Safe Mode. Grant "Display
over other apps" access and notification permission, then tap "Start device display
mode". Brightness settings access is needed only if the optional brightness preset
is selected. No permissions are requested until the corresponding control is used.

The single dimming overlay is non-interactive, takes no input focus, and has opacity
limited to 5–40%. It never captures or reads the screen. A special-use foreground
service keeps a persistent notification with a Stop action while dimming is enabled.
Disabling Safe Mode pauses this display mode and requests brightness restoration;
re-enabling Safe Mode resumes it only if the user previously linked it and permissions
remain available. Explicit Stop unlinks it. Screen lock also stops and unlinks it.

Brightness restoration preserves newer user changes. If permission is revoked,
restoration may require granting access again or changing brightness in Android
settings. If Android kills the process without a lifecycle callback, the overlay
disappears but system brightness may persist until the app is reopened for recovery.

Limitations: overlays can be hidden by apps and system security UI; critical system
windows such as the status bar and keyboard appear above ordinary overlays. Apps
can override system brightness. Android's Remove animations, Extra dim and colour
correction controls vary by device and must be configured in system settings.
CalmGuard cannot universally stop autoplay, change other apps' animation code,
recolour their content or remove flashing. Dimming does not establish seizure safety.
The in-app media/motion toggles still control only the scripted CalmGuard demo.
Phone-specific overlay and brightness behavior requires physical-device testing.

Relevant Android references:
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context)
- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://support.google.com/accessibility/answer/11183305

## Open and run

Open this interface directory in Android Studio and let Gradle sync. Run the app on
an Android device or emulator. The project uses AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20,
Compose BOM 2025.04.01, Java 17 and Android SDK 35.

On this workspace, build-local.ps1 uses the isolated tools downloaded into ../.tools
without changing machine-wide settings:

```powershell
.\build-local.ps1
.\build-local.ps1 -Verify
```

Alternatively, with Java 17 and an Android SDK configured:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

The debug APK is generated at app/build/outputs/apk/debug/app-debug.apk. On the development
machine, the verified installable copy is releases/CalmGuard.apk. APKs are
excluded from Git, so build a new copy after cloning or obtain a published GitHub
Release attachment. See VERIFICATION.md for the development build results.

## Data and model

See ../dataset/README.md and ../dataset/SOURCES.json for attribution and checksums.
The complete WISDM archive and original UAL CSV live in the dataset directory.
The model uses UAL only for training, holds participant 3 out, and evaluates WISDM
separately. See model/README.md and model/evaluation.json. UAL units and nominal
sample timing are assumptions; the model performs poorly for one held-out volunteer.
These are exploratory simulation results. The live phone-motion check does not use
this model.

To rebuild the model, install tools/requirements.txt into an isolated environment,
then run tools/train_model.py. Download scripts are in tools/download_resources.py.
The three files in app/src/main/assets are generated by the training script.

## Verification

Tests cover Kotlin/Python model parity, finite features, sample provenance and held-out
participant separation, settings/history persistence, cancellation and interrupted
test behavior, sample resets, CSV escaping, live accelerometer events, the Android
notification countdown, opt-in SMS requests and end-to-end Compose screen navigation.
The Compose test also captures screenshots under app/build/screenshots for visual QA.
Robolectric checks do not replace testing on a physical Android phone.
