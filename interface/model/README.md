# Movement model

The deployed model is a 64-tree Random Forest (maximum depth 6, minimum leaf size 3,
balanced class weights, random seed 42). It uses twelve acceleration summary features
and a fixed demonstration threshold of 0.70. The score is an uncalibrated model output,
not a clinical seizure probability.

UAL participants 0, 1 and 2 supply training data. Participant 3 is held out for the
reported primary evaluation and UAL app replay clips. No timestamps, participant IDs,
run IDs, or labels enter the feature vector. Windows never overlap or cross source
label/participant boundaries. Runs split on clock reversal and gaps over two seconds.

The source describes approximately 100 Hz acquisition, but timestamps are bursty.
Five consecutive readings are averaged, then forty reduced readings form a window.
This is a nominal sample-order assumption, not verified two-second physiology.
UAL acceleration units are assumed to be g based on observed scale; the source
description does not establish units or sensor placement. WISDM uses m/s² and is
converted to g. These uncertainties limit cross-source interpretation.

WISDM is excluded from training. All 51 watch-accelerometer recordings supply a
negative-only external challenge. Records are delimited by semicolons; a malformed
record is excluded and counted. The reported positive-window rate must not be
interpreted as a clinical false-alarms-per-day estimate. The two-consecutive-window
alert rule is an app demonstration and has not been evaluated as an event detector.

See evaluation.json for the primary holdout, four leave-one-volunteer-out folds,
threshold baseline, counts and WISDM challenge. The poor result for one volunteer
must be considered alongside the favorable primary holdout. No clinical seizures
are used. The data is too small and heterogeneous to support medical monitoring.

The Kotlin feature extractor and tree traversal are tested against independent
Python/scikit-learn prediction vectors. The app performs inference locally from
the JSON forest; no Python, server, API key, TensorFlow or network is required.

Rebuild with tools/train_model.py using the pinned training environment documented
in tools/requirements.txt. Evaluation is deterministic for these versions and data.
