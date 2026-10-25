"""Reproducible simulation-only baseline, portable forest export, and sample library.

Never mixes WISDM negatives into UAL training: that would confound source and label.
No timestamps, participant IDs or labels are supplied as model features.
"""
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".tools" / "ml-packages"))
import hashlib
import io
import json
import zipfile
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import confusion_matrix, balanced_accuracy_score, f1_score

ASSETS = ROOT / "interface/app/src/main/assets"
OUTPUT = ROOT / "interface/model"
FEATURES = ["magnitude_mean", "magnitude_std", "magnitude_range", "magnitude_rms",
            "difference_mean", "difference_std", "difference_max", "axis_std_mean",
            "axis_range_mean", "axis_rms_mean", "magnitude_center_crossings", "lag1_correlation"]
THRESHOLD = 0.70
WINDOW = 40


def features(p):
    p = np.asarray(p, dtype=np.float64)
    assert p.shape == (WINDOW, 3) and np.isfinite(p).all()
    m = np.linalg.norm(p, axis=1)
    diff = np.abs(np.diff(m))
    c = m - m.mean()
    denominator = np.dot(c, c)
    return [m.mean(), m.std(), np.ptp(m), np.sqrt(np.mean(m*m)),
            diff.mean(), diff.std(), diff.max(), p.std(axis=0).mean(),
            np.ptp(p, axis=0).mean(), np.sqrt(np.mean(p*p, axis=0)).mean(),
            np.mean((c[:-1] >= 0) != (c[1:] >= 0)),
            np.dot(c[:-1], c[1:]) / denominator if denominator >= 1e-12 else 0.0]


def forest():
    return RandomForestClassifier(n_estimators=64, max_depth=6, min_samples_leaf=3,
        class_weight="balanced", random_state=42, n_jobs=1)


def metrics(truth, prediction):
    tn, fp, fn, tp = confusion_matrix(truth, prediction, labels=[0, 1]).ravel()
    return {"sensitivity": float(tp / (tp + fn)) if tp + fn else 0.0,
            "specificity": float(tn / (tn + fp)) if tn + fp else 0.0,
            "f1": float(f1_score(truth, prediction, zero_division=0)),
            "balanced_accuracy": float(balanced_accuracy_score(truth, prediction)),
            "confusion_matrix": [[int(tn), int(fp)], [int(fn), int(tp)]],
            "test_windows": int(len(truth))}


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path = ROOT / "dataset/ual/epilepsy_dataset.csv"
    expected = "e5c74fe66e0dceb0615f4914e2eb95e6b2f7dbe2340ca97ca22228ec19e4b492"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected
    data = pd.read_csv(path)
    assert list(data.columns) == ["timestamp", "x", "y", "z", "User", "Epilepsy"]
    assert not data.isna().any().any() and set(data.Epilepsy.unique()) == {0, 1}
    # Split source runs at label/participant boundaries, clock reversals and >2 s gaps.
    boundary = data.User.ne(data.User.shift()) | data.Epilepsy.ne(data.Epilepsy.shift())
    delta = data.timestamp.diff()
    boundary |= (delta <= 0) | (delta > 2000)
    records, runs = [], []
    for run, group in data.groupby(boundary.cumsum(), sort=False):
        raw = group[["x", "y", "z"]].to_numpy()
        user, label = int(group.User.iloc[0]), int(group.Epilepsy.iloc[0])
        # The publisher reports ~100 Hz but file timestamps are bursty. Use ordered
        # groups of 5 as a documented nominal-rate assumption, not verified timing.
        n = len(raw) // 5
        reduced = raw[:n*5].reshape(n, 5, 3).mean(axis=1)
        runs.append({"user": user, "label": label, "run": int(run), "points": reduced})
        for start in range(0, len(reduced) - WINDOW + 1, WINDOW):
            p = reduced[start:start+WINDOW]
            records.append({"user": user, "label": label, "run": int(run), "points": p})
    x = np.asarray([features(r["points"]) for r in records])
    y = np.asarray([r["label"] for r in records])
    users = np.asarray([r["user"] for r in records])
    train, test = users != 3, users == 3
    assert set(users[train]).isdisjoint(set(users[test]))
    assert set(y[train]) == set(y[test]) == {0, 1}
    model = forest().fit(x[train], y[train])
    probabilities = model.predict_proba(x[test])[:, 1]
    report = metrics(y[test], probabilities >= THRESHOLD)
    report.update({"training_users": [0, 1, 2], "held_out_user": 3,
                   "training_windows": int(train.sum()), "source_rows": len(data),
                   "training_label_counts": np.bincount(y[train]).tolist(),
                   "test_label_counts": np.bincount(y[test]).tolist()})
    candidates = np.linspace(0.01, 1.5, 150)
    baseline_threshold = max(candidates, key=lambda threshold: balanced_accuracy_score(y[train], x[train, 1] >= threshold))
    report["threshold_baseline"] = {"magnitude_std_threshold": float(baseline_threshold),
                                    **metrics(y[test], x[test, 1] >= baseline_threshold)}
    folds = []
    for user in sorted(set(users)):
        held = users == user
        f = forest().fit(x[~held], y[~held])
        folds.append({"held_out_user": int(user), **metrics(y[held], f.predict_proba(x[held])[:, 1] >= THRESHOLD)})
    report["leave_one_volunteer_out"] = folds
    trees = []
    for estimator in model.estimators_:
        t = estimator.tree_
        values = t.value[:, 0, :]
        positive = values[:, 1] / values.sum(axis=1)
        trees.append({"feature": t.feature.tolist(), "threshold": t.threshold.tolist(),
                      "left": t.children_left.tolist(), "right": t.children_right.tolist(),
                      "positive_fraction": positive.tolist()})

    clips = []
    for label, title in [(0, "Other movement"), (1, "Simulated shaking")]:
        run = max((r for r in runs if r["user"] == 3 and r["label"] == label), key=lambda r: len(r["points"]))
        p = run["points"][:240]
        p = p[:len(p)//WINDOW*WINDOW]
        clips.append({"id": f"ual-{label}", "title": title,
                      "description": "Volunteer-recorded movement from the held-out participant. The positive label is simulated seizure-like movement.",
                      "source": "UAL Smart Home", "participant": "3 (held out)",
                      "label": "simulated seizure-like movement" if label else "other movement",
                      "hz": 20, "points": p.tolist()})

    wisdm_path = ROOT / "dataset/wisdm/wisdm-original.zip"
    with zipfile.ZipFile(wisdm_path) as archive:
        assert archive.testzip() is None, "Corrupt WISDM archive"
        members = [n for n in archive.namelist() if "/raw/watch/accel/" in n and n.endswith(".txt")]
        assert len(members) == 51, f"Expected 51 watch recordings; got {len(members)}"
        negatives, activity_totals, skipped = [], {}, 0
        extracted = ROOT / "dataset/wisdm/watch_accel"
        extracted.mkdir(exist_ok=True)
        for member in sorted(members):
            content = archive.read(member)
            (extracted / Path(member).name).write_bytes(content)
            # The raw release sometimes joins records on one physical line.
            # Semicolons, not line breaks, terminate records in this dataset.
            normalised = content.decode("utf-8").replace(";", "\n")
            lines = [line.strip() for line in normalised.splitlines() if line.strip()]
            invalid = [line for line in lines if len(line.split(",")) != 6]
            skipped += len(invalid)
            normalised = "\n".join(line for line in lines if len(line.split(",")) == 6)
            frame = pd.read_csv(io.StringIO(normalised), header=None,
                                names=["user", "activity", "timestamp", "x", "y", "z"], dtype={"z": str})
            frame["z"] = pd.to_numeric(frame.z.str.rstrip(";"), errors="coerce")
            for axis in ["x", "y", "z"]: frame[axis] = pd.to_numeric(frame[axis], errors="coerce")
            skipped += int(frame[["x", "y", "z"]].isna().any(axis=1).sum())
            frame = frame.dropna(subset=["x", "y", "z"])
            for activity, group in frame.groupby(frame.activity.ne(frame.activity.shift()).cumsum(), sort=False):
                code = str(group.activity.iloc[0])
                # WISDM uses SI acceleration; conversion to g is documented.
                points = group[["x", "y", "z"]].to_numpy() / 9.80665
                windows = [points[i:i+WINDOW] for i in range(0, len(points)-WINDOW+1, WINDOW)]
                for p in windows:
                    if np.isfinite(p).all():
                        negatives.append(features(p)); activity_totals[code] = activity_totals.get(code, 0) + 1
                if int(group.user.iloc[0]) == 1600 and code in ["A", "G", "R"]:
                    title = {"A": "Walking", "G": "Brushing teeth", "R": "Clapping"}[code]
                    clips.append({"id": f"wisdm-{code}", "title": title,
                                  "description": "Real smartwatch movement. A separate everyday-activity challenge, outside the model's training dataset.",
                                  "source": "WISDM watch", "participant": "1600", "label": title.lower(),
                                  "hz": 20, "points": points[:240].tolist()})
        scores = model.predict_proba(np.asarray(negatives))[:, 1]
        report["wisdm_challenge"] = {"windows": len(negatives), "above_threshold": int((scores >= THRESHOLD).sum()),
            "positive_window_rate": float(np.mean(scores >= THRESHOLD)), "activity_windows": activity_totals,
            "dropped_invalid_rows": skipped,
            "interpretation": "External negative-only challenge. Window-positive rate is not a clinical false-alarms-per-day estimate."}
        for member in archive.namelist():
            if Path(member).name in ["README.txt", "activity_key.txt"]:
                (ROOT / "dataset/wisdm" / Path(member).name).write_bytes(archive.read(member))
    assert len(clips) == 5
    payload = {"schema_version": 1, "features": FEATURES, "window_readings": WINDOW,
               "threshold": THRESHOLD, "report": report, "trees": trees}
    for directory in [ASSETS, OUTPUT]:
        (directory / "motion_forest.json").write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    (ASSETS / "sample_clips.json").write_text(json.dumps({"clips": clips}, separators=(",", ":")), encoding="utf-8")
    vectors = []
    for r in [records[i] for i in np.where(test)[0][::max(1, test.sum()//12)]]:
        v = features(r["points"])
        vectors.append({"points": r["points"].tolist(), "features": v,
                        "expected_score": float(model.predict_proba([v])[0, 1])})
    (ASSETS / "parity_vectors.json").write_text(json.dumps({"vectors": vectors}), encoding="utf-8")
    report["feature_importance"] = dict(zip(FEATURES, model.feature_importances_.tolist()))
    (OUTPUT / "evaluation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    manifest = {
        "ual": {"source": "https://data.mendeley.com/datasets/37f8w8f7tm/1", "doi": "10.17632/37f8w8f7tm.1",
                "license": "CC BY 4.0", "author": "Marcos Lupión Lorente", "sha256": expected, "rows": len(data),
                "caveats": ["Four volunteers simulating seizures; no clinical seizures", "Sensor placement not confirmed",
                            "Units assumed g from scale; not documented in dataset description", "Bursty timestamps; nominal sample-order reduction, no verified timing"]},
        "wisdm": {"original": "https://doi.org/10.24432/C5HK59", "author": "Gary M. Weiss / WISDM Lab, Fordham University",
                  "download": "https://www.kaggle.com/datasets/rabieelkharoua/human-activity-classification-dataset",
                  "license": "CC BY 4.0", "archive_sha256": hashlib.sha256(wisdm_path.read_bytes()).hexdigest(),
                  "watch_files": len(members), "use": "Separate everyday-activity challenge and replay; excluded from training"},
        "original_eeg": {"file": "EEG_Signal.csv", "use": "Preserved unchanged, excluded from movement model"}}
    (ROOT / "dataset/SOURCES.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
