# Project datasets

## UAL Smart Home simulated movements

- Folder: ual/epilepsy_dataset.csv
- Author: Marcos Lupión Lorente (2022), Universidad de Almeria
- Source: https://data.mendeley.com/datasets/37f8w8f7tm/1
- DOI: 10.17632/37f8w8f7tm.1
- License: Creative Commons Attribution 4.0 (CC BY 4.0)
- 79,181 rows; timestamp, x, y, z, User, Epilepsy
- Four volunteers; Epilepsy=1 denotes simulated seizure-like movement, not a
  clinically confirmed seizure. Epilepsy=0 is the other source-labelled movement.
- Original CSV preserved byte-for-byte. SHA-256 is verified against publisher metadata.
- Timestamp intervals are highly irregular. Units and sensor placement need confirmation.

## WISDM smartphone and smartwatch activities

- Author: Gary M. Weiss / WISDM Lab, Fordham University (2019)
- Original: https://doi.org/10.24432/C5HK59
- Download mirror: https://www.kaggle.com/datasets/rabieelkharoua/human-activity-classification-dataset
- License: Creative Commons Attribution 4.0 (CC BY 4.0)
- Folder: wisdm/wisdm-original.zip (complete mirror archive)
- Extracted subset: wisdm/watch_accel/ (51 source files)
- 51 people performing 18 ordinary activities; no seizure class.
- Only watch accelerometer data is used. Phone and gyroscope data remain in the archive.
- The archive passes ZIP CRC verification. See SOURCES.json for its SHA-256.
- The model excludes this dataset from training and uses it for a separate
  everyday-movement challenge. Raw files are preserved; semicolon-separated
  records are parsed and malformed records are counted during processing.

## Existing EEG file

EEG_Signal.csv is retained unchanged and excluded from the movement model.
Its recording IDs and label structure match the Bonn EEG corpus. It measures
brain electrical activity, not wrist movement.

SOURCES.json records downloaded sources, attribution, checksums, use and limitations.
The prototype does not include SHAR synthetic data or restricted My Seizure Gauge data.

The WISDM archive and extracted watch recordings are intentionally excluded from Git.
They remain on the development machine. On a fresh clone, run
`python interface/tools/download_resources.py wisdm` from the repository root to
download the archive; the training script extracts its watch subset when needed.
