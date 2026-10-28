#!/usr/bin/env python3
"""Works out the pinned values for the lyrics recognition (see "Распознавание текста нейросетью" in BUILDING.md).

    python tools/asr/pin.py library v1.13.8      constants for AsrLibrary.java (the official sherpa-onnx AAR)
    python tools/asr/pin.py model small          constants for AsrModel.java (a Hugging Face repository, fixed commit)
    python tools/asr/pin.py sources v1.13.8      downloads the Java classes of sherpa-onnx of that tag, with a header,
                                                 into extensions/arsound/src/main/java/com/k2fsa/sherpa/onnx

Only the Python standard library is used. The AAR is about 39 MB and is downloaded to a temporary folder.
"""
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VENDORED = os.path.join(ROOT, "extensions", "arsound", "src", "main", "java", "com", "k2fsa", "sherpa", "onnx")
ENTRY = "jni/arm64-v8a/libsherpa-onnx-jni.so"
# What the Java side of the recogniser needs; found by following the class names used by these.
ROOT_CLASSES = ["OfflineRecognizer", "OfflineStream", "OfflineRecognizerResult"]
# Kept by hand in the repository (changed for Arsound), never overwritten by this script.
KEPT_BY_HAND = {"LibraryLoader"}
# Not needed: the desktop loader that LibraryLoader used to call.
SKIPPED = {"LibraryUtils"}


def get(url, binary=True):
    request = urllib.request.Request(url, headers={"User-Agent": "arsound-pin"})
    with urllib.request.urlopen(request) as response:
        data = response.read()
    return data if binary else data.decode("utf-8")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def library(tag):
    version = tag.lstrip("v")
    url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/sherpa-onnx-static-link-onnxruntime-{version}.aar"
    print("downloading", url, file=sys.stderr)
    archive = get(url)
    with tempfile.NamedTemporaryFile(suffix=".aar", delete=False) as handle:
        handle.write(archive)
        name = handle.name
    try:
        with zipfile.ZipFile(name) as zipped:
            so = zipped.read(ENTRY)
    finally:
        os.unlink(name)
    print(f'static final String VERSION = "{version}";')
    print(f"static final long ARCHIVE_SIZE = {len(archive):_}L;")
    print(f'static final String ARCHIVE_SHA256 = "{sha256(archive)}";')
    print(f"static final long FILE_SIZE = {len(so):_}L;")
    print(f'static final String FILE_SHA256 = "{sha256(so)}";')
    # The library must be linked for 16 KB pages (Android 15+ phones).
    phoff = int.from_bytes(so[0x20:0x28], "little")
    entsize = int.from_bytes(so[0x36:0x38], "little")
    count = int.from_bytes(so[0x38:0x3A], "little")
    aligns = {int.from_bytes(so[phoff + i * entsize + 48:phoff + i * entsize + 56], "little")
              for i in range(count) if int.from_bytes(so[phoff + i * entsize:phoff + i * entsize + 4], "little") == 1}
    print("// LOAD segment alignment:", ", ".join(hex(a) for a in sorted(aligns)), "(0x4000 or more is right for 16 KB pages)")


def model(name):
    repo = f"csukuangfj/sherpa-onnx-whisper-{name}"
    info = json.loads(get(f"https://huggingface.co/api/models/{repo}", binary=False))
    commit = info["sha"]
    tree = json.loads(get(f"https://huggingface.co/api/models/{repo}/tree/{commit}", binary=False))
    print(f'repository "{repo}", commit "{commit}"')
    for entry in tree:
        if entry["type"] != "file" or not re.search(rf"{name}-(encoder|decoder)\.int8\.onnx$|{name}-tokens\.txt$", entry["path"]):
            continue
        lfs = entry.get("lfs")
        digest = lfs["oid"] if lfs else sha256(get(f"https://huggingface.co/{repo}/resolve/{commit}/{entry['path']}"))
        print(f'{{"{entry["path"]}", "{entry["size"]}", "{digest}"}},')


def sources(tag):
    listing = json.loads(get(
        f"https://api.github.com/repos/k2-fsa/sherpa-onnx/contents/sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx?ref={tag}",
        binary=False))
    files = {entry["name"][:-5]: entry["download_url"] for entry in listing if entry["name"].endswith(".java")}
    texts = {}
    todo = list(ROOT_CLASSES)
    while todo:
        name = todo.pop()
        if name in texts or name in SKIPPED:
            continue
        texts[name] = get(files[name], binary=False)
        todo.extend(word for word in set(re.findall(r"\b([A-Z]\w+)\b", texts[name])) if word in files and word not in texts)
    os.makedirs(VENDORED, exist_ok=True)
    for name, text in sorted(texts.items()):
        if name in KEPT_BY_HAND:
            continue
        header = f"// Vendored unmodified from https://github.com/k2-fsa/sherpa-onnx, tag {tag} (Apache License 2.0, see NOTICE.txt).\n"
        with open(os.path.join(VENDORED, name + ".java"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(header + text)
    print("written", len(texts) - len(KEPT_BY_HAND & set(texts)), "classes to", VENDORED)
    print("Check by hand: LibraryLoader.java (kept), NOTICE.txt (tag), and that the native library still resolves every class:")
    print("  python -c \"import re,sys; d=open(sys.argv[1],'rb').read(); print(sorted(set(re.findall(rb'com/k2fsa/sherpa/onnx/\\w+', d))))\" libsherpa-onnx-jni.so")


if __name__ == "__main__":
    commands = {"library": library, "model": model, "sources": sources}
    if len(sys.argv) != 3 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]](sys.argv[2])
