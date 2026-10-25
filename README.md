# CalmGuard epilepsy research prototype

Kotlin and Jetpack Compose Android prototype for exploring in-app accessibility
controls, sample-based movement classification, local test alerts and optional
device display dimming. It is a research demonstration, not a clinical seizure detector.

## Android app

Open the [interface](interface/) directory in Android Studio. See
[setup and features](interface/README.md), [verification results](interface/VERIFICATION.md)
and [model details](interface/model/README.md).

The app includes a small exported Random Forest and prerecorded sample clips, so
it runs offline without downloading the full training datasets or connecting a wristband.

## Datasets

Source references, attribution and checksums are in [dataset/README.md](dataset/README.md)
and [dataset/SOURCES.json](dataset/SOURCES.json).

The large WISDM archive and extracted watch recordings are kept locally and excluded
from Git. Restore the archive from the public source with Python 3.12 or later:

```powershell
python interface/tools/download_resources.py wisdm
```

The training script extracts the watch subset when rebuilding the model. See
[model documentation](interface/model/README.md) for processing and evaluation assumptions.

## APK distribution

Generated APK and app-bundle files are excluded from Git. Build the APK with Android
Studio or Gradle as described in the app setup instructions. Share installable builds
as GitHub Release attachments rather than committing them to the repository.
