#!/usr/bin/env python3
"""Generate models.json from HuggingFace + manual seeds.

CRITICAL: expand[]=siblings only works on the DETAIL endpoint
(/api/models/{id}?expand[]=siblings), NOT on the list endpoint.
This fetches per-model file lists one by one (~233 detail calls).
"""
import json, re, sys, os, urllib.request, datetime
from pathlib import Path

HF_API = "https://huggingface.co/api/models"
AUTHORS = ["csukuangfj", "k2-fsa", "Reza2kn"]
MANUAL = Path("tools/manual_models.json")
UA = {"User-Agent": "mariaplayer-catalog/3.0", "Accept": "application/json"}

NON_ASR = [
    "tts", "vits", "kokoro", "matcha", "piper",
    "vad", "silero", "ten-vad",
    "speaker", "diarization",
    "language-id", "punctuation",
    "keyword-spotting", "kws", "hotword",
    "audio-tagging", "denoiser",
    "source-separation", "speech-translation",
    "tdqn", "yesno", "hey-snapdragon",
    "-demo", "-example-", "-test-",
    "vosk-model",
    "sherpa-onnx-apk", "cmake-deps", "hifigan",
    "streaming-", "-streaming", "online-",
]

LANG_CODES = {
    "en","zh","fa","ar","tr","ru","de","fr","es","it","ja","ko",
    "hi","vi","th","id","ms","pt","nl","pl","uk","cs","hu","ro",
    "bn","ta","te","ur","he","el","fi","sv","da","no","ca","tl",
    "my","km","lo","si","az","kz","uz","hy","ka",
}

def http(url, timeout=30):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())

def is_asr(name):
    low = name.lower()
    return not any(x in low for x in NON_ASR)

def list_all():
    ids = []
    for author in AUTHORS:
        try:
            data = http(f"{HF_API}?author={author}&limit=1000")
            n = 0
            for m in data:
                mid = m.get("modelId", "")
                if mid and is_asr(mid):
                    ids.append(mid)
                    n += 1
            print(f"  {author}: {n} ASR of {len(data)} total")
        except Exception as e:
            print(f"  {author}: ERROR {type(e).__name__}: {e}")
    return ids

def fetch_files(mid):
    try:
        info = http(f"{HF_API}/{mid}?expand[]=siblings")
        return [s.get("rfilename", "") for s in info.get("siblings", [])]
    except Exception as e:
        print(f"    fetch {mid}: {type(e).__name__}: {e}")
        return []

def classify_arch(files):
    low = [f.lower() for f in files]
    has_enc = any("encoder" in f for f in low)
    has_dec = any("decoder" in f for f in low)
    has_join = any("joiner" in f for f in low)
    has_model = any(f == "model.onnx" or f == "model.int8.onnx" for f in low)
    if has_enc and has_dec and has_join: return "rnnt"
    if has_enc and has_dec: return "whisper"
    if has_model: return "ctc"
    return None

def find_in(files, patterns):
    for pat in patterns:
        for f in files:
            if f.endswith(pat):
                return f
    return None

def pick_files(files, arch):
    picked = []
    if arch == "rnnt":
        for b in ("encoder", "decoder", "joiner"):
            f = find_in(files, [f"{b}.int8.onnx", f"{b}.onnx"])
            if f: picked.append(f)
    elif arch == "whisper":
        for b in ("encoder", "decoder"):
            f = find_in(files, [f"{b}.int8.onnx", f"{b}.onnx"])
            if f: picked.append(f)
    elif arch == "ctc":
        f = find_in(files, ["model.int8.onnx", "model.onnx"])
        if f: picked.append(f)
    tok = find_in(files, ["tokens.txt"])
    if tok: picked.append(tok)
    return picked

def parse_langs(name):
    low = name.lower()
    if "whisper" in low:
        return ["multi"]
    if "sense-voice" in low or "sensevoice" in low:
        return ["zh", "en", "ja", "ko", "yue"]
    if "paraformer-trilingual" in low:
        return ["zh", "yue", "en"]
    if any(x in low for x in ("bilingual", "trilingual", "multilingual")):
        langs = []
        for code in LANG_CODES:
            if re.search(rf"(?:^|[-_]){code}(?:[-_]|$)", low):
                langs.append(code)
        return langs if langs else ["multi"]
    found = []
    for m in re.finditer(r"(?:^|[-_])([a-z]{2})(?:[-_]|$)", low):
        c = m.group(1)
        if c in LANG_CODES and c not in found:
            found.append(c)
    return found if found else ["en"]

def build_entry(mid, files):
    if not files:
        return None
    arch = classify_arch(files)
    if not arch:
        return None
    picked = pick_files(files, arch)
    if len(picked) < 2:
        return None
    short = mid.split("/")[-1]
    if short.startswith("sherpa-onnx-"):
        short = short[len("sherpa-onnx-"):]
    base = f"https://huggingface.co/{mid}/resolve/main"
    size = 0
    try:
        req = urllib.request.Request(f"{base}/{picked[0]}", method="HEAD", headers=UA)
        with urllib.request.urlopen(req, timeout=10) as r:
            size = int(r.headers.get("Content-Length", "0"))
    except Exception:
        pass
    return {
        "id": f"hf-{short}".lower(),
        "name": short,
        "language": parse_langs(short),
        "sizeBytes": size,
        "url": f"{base}/{picked[0]}",
        "format": "onnx",
        "engine": "sherpa-onnx",
        "arch": arch,
        "license": "Apache-2.0",
        "musicRating": "Unknown",
        "files": [{"name": p.split("/")[-1], "url": f"{base}/{p}"} for p in picked],
    }

def main():
    if not (os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS")):
        print("Run on CI only. Trigger: gh workflow run update-models.yml")
        print("Force locally: CI=1 python3 tools/generate_models.py")
        sys.exit(0)

    manual = json.loads(MANUAL.read_text())["models"] if MANUAL.exists() else []
    print(f"manual seeds: {len(manual)}")

    print("listing models:")
    all_ids = list_all()
    print(f"total ASR candidates: {len(all_ids)}")

    entries = []
    for i, mid in enumerate(all_ids):
        if i > 0 and i % 25 == 0:
            print(f"  progress: {i}/{len(all_ids)}")
        files = fetch_files(mid)
        e = build_entry(mid, files)
        if e:
            entries.append(e)

    print(f"HF kept: {len(entries)}")

    by_id = {m["id"].lower(): m for m in manual}
    for e in entries:
        if e["id"] not in by_id:
            by_id[e["id"]] = e

    final = sorted(by_id.values(), key=lambda e: (e["id"], e["name"]))
    out = {
        "version": 4,
        "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "count": len(final),
        "models": final,
    }
    Path("models.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))

    cov = {}
    for e in final:
        for l in e["language"]:
            cov[l] = cov.get(l, 0) + 1
    print(f"WROTE {len(final)} models")
    print("coverage:")
    for l in sorted(cov):
        print(f"  {l:6} {cov[l]}")

if __name__ == "__main__":
    main()
