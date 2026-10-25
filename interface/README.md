# CalmGuard Android prototype

A Kotlin and Jetpack Compose Android app for exploring the research proposal using
recorded movement samples. Android 9 (API 28) or later is required.

## Features

- Overview with persisted Safe Mode preferences.
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

The app has no network access. It does not connect to a wristband, collect live
movement or location, send SMS, or provide background seizure monitoring. Test
alerts remain on the screen. No real flashing test media is included.

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
machine, the verified installable copy is releases/CalmGuard-prototype.apk. APKs are
excluded from Git, so build a new copy after cloning or obtain a published GitHub
Release attachment. See VERIFICATION.md for the development build results.

## Data and model

See ../dataset/README.md and ../dataset/SOURCES.json for attribution and checksums.
The complete WISDM archive and original UAL CSV live in the dataset directory.
The model uses UAL only for training, holds participant 3 out, and evaluates WISDM
separately. See model/README.md and model/evaluation.json. UAL units and nominal
sample timing are assumptions; the model performs poorly for one held-out volunteer.
This is an exploratory simulation prototype, not a clinical seizure detector.

To rebuild the model, install tools/requirements.txt into an isolated environment,
then run tools/train_model.py. Download scripts are in tools/download_resources.py.
The three files in app/src/main/assets are generated by the training script.

## Verification

Tests cover Kotlin/Python model parity, finite features, sample provenance and held-out
participant separation, settings/history persistence, cancellation and interrupted
test behavior, sample resets, CSV escaping and end-to-end Compose screen navigation.
The Compose test also captures screenshots under app/build/screenshots for visual QA.
Robolectric checks do not replace testing on a physical Android phone.
