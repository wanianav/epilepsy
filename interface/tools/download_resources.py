"""Download public research data and optional isolated Android build tools."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / ".tools"


def get(url, target):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size:
        print(f"Already downloaded: {target.name}", flush=True)
        return target
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    temporary = target.with_suffix(target.suffix + f".{os.getpid()}.part")
    with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
    temporary.replace(target)
    print(f"Downloaded {target.name}: {target.stat().st_size:,} bytes", flush=True)
    return target


def ual():
    api = "https://data.mendeley.com/public-api/datasets/37f8w8f7tm/files?folder_id=root&version=1"
    with urllib.request.urlopen(urllib.request.Request(api, headers={"User-Agent": "Mozilla/5.0"}), timeout=60) as response:
        metadata = json.load(response)
    folder = ROOT / "dataset" / "ual"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "source_metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    entries = metadata if isinstance(metadata, list) else metadata.get("data", metadata.get("files", []))
    for entry in entries:
        if entry.get("filename", "").endswith(".csv"):
            content = entry.get("content_details", {})
            url = content.get("download_url") or entry.get("download_url")
            if not url:
                raise ValueError(f"No download URL: {entry}")
            get(url, folder / entry["filename"])


def wisdm():
    get("https://www.kaggle.com/api/v1/datasets/download/rabieelkharoua/human-activity-classification-dataset",
        ROOT / "dataset" / "wisdm" / "wisdm-original.zip")


def android():
    downloads = TOOLS / "downloads"
    jobs = [
        ("https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse", downloads / "jdk17.zip"),
        ("https://services.gradle.org/distributions/gradle-8.11.1-bin.zip", downloads / "gradle-8.11.1-bin.zip"),
        ("https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip", downloads / "android-cli.zip"),
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        paths = list(pool.map(lambda task: get(*task), jobs))
    for path, destination in zip(paths, [TOOLS / "java", TOOLS / "gradle", TOOLS / "android-sdk" / "cmdline-tools" / "latest"]):
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path) as archive:
            for member in archive.infolist():
                parts = Path(member.filename).parts
                if path.name == "android-cli.zip":
                    parts = parts[1:]
                if not parts:
                    continue
                target = destination.joinpath(*parts).resolve()
                if not target.is_relative_to(destination.resolve()):
                    raise ValueError("Unsafe archive path")
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(member) as src, target.open("wb") as dst:
                        import shutil
                        shutil.copyfileobj(src, dst)
        print(f"Extracted {path.name}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    selection = sys.argv[1:]
    tasks = [ual, wisdm] if not selection else [globals()[name] for name in selection]
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        for result in pool.map(lambda fn: fn(), tasks):
            pass
