#!/usr/bin/env python3
"""Generate models.json = manual seeds + HuggingFace (multi-author) + GitHub releases."""
import json, re, sys, urllib.request, datetime
from pathlib import Path

HF_AUTHORS = ["csukuangfj", "Reza2kn", "k2-fsa", "UsefulSensors"]
GH_API = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models"
MANUAL = Path("tools/manual_models.json")

LANG_NAMES = {
    "en":"English","zh":"Chinese","fa":"Persian","ar":"Arabic","tr":"Turkish",
    "ru":"Russian","de":"German","fr":"French","es":"Spanish","it":"Italian",
    "ja":"Japanese","ko":"Korean","hi":"Hindi","vi":"Vietnamese","th":"Thai",
    "id":"Indonesian","ms":"Malay","pt":"Portuguese","nl":"Dutch","pl":"Polish",
    "uk":"Ukrainian","cs":"Czech","hu":"Hungarian","ro":"Romanian","bn":"Bengali",
    "ta":"Tamil","te":"Telugu","ur":"Urdu","he":"Hebrew","el":"Greek",
    "fi":"Finnish","sv":"Swedish","da":"Danish","no":"Norwegian","ca":"Catalan",
    "tl":"Tagalog","my":"Burmese","km":"Khmer","lo":"Lao","si":"Sinhala",
}

NON_ASR_MARKERS = [
    # non-ASR modality
    "tts", "vits", "kokoro", "matcha", "piper",
    "vad", "silero", "ten-vad",
    "speaker", "diarization",
    "language-id", "punctuation",
    "keyword-spotting", "kws", "hotword",
    "audio-tagging", "denoiser",
    "source-separation", "speech-translation",
    "streaming-", "-streaming", "online-",
    # toy / demo models that crash sherpa-onnx
    "tdqn", "yesno", "yes-no",
    "hey-snapdragon", "hey-siri",
    "-demo", "-example-", "-test-",
    "playground", "hello-world",
    # unrelated engines - cannot run in sherpa-onnx
    "vosk-model", "vosk_",
    # moonshine in its original single-file form (not sherpa-onnx package)
    # we keep sherpa-onnx-moonshine-* (has tokens.txt + proper onnx)
]

def is_asr(name):
    lower = name.lower()
    if any(m in lower for m in NON_ASR_MARKERS):
        return False
    # require sherpa-onnx marker or explicit model/tokens pattern
    return True

def http_get(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": "mariaplayer-catalog/2.0",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())

def parse_langs(name):
    lower = name.lower()
    codes = []
    if any(k in lower for k in ("multi-lang", "multilingual", "multi-language")):
        codes.append("multi")
    if "bilingual" in lower or "trilingual" in lower:
        codes.append("multi")
    for m in re.finditer(r"(?:^|[-_])([a-z]{2})(?:[-_]|$)", lower):
        code = m.group(1)
        if code in LANG_NAMES and code not in codes:
            codes.append(code)
    if not codes:
        codes.append("en")
    return codes

def classify_arch(files):
    has_encoder = any("encoder" in f for f in files)
    has_decoder = any("decoder" in f for f in files)
    has_joiner = any("joiner" in f for f in files)
    has_model = any(f in ("model.onnx", "model.int8.onnx") for f in files)
    if has_encoder and has_decoder and has_joiner: return "rnnt"
    if has_encoder and has_decoder: return "whisper"
    if has_model: return "ctc"
    return "unknown"

def pick_files(files, arch):
    picked = []
    if arch == "rnnt":
        for base in ("encoder","decoder","joiner"):
            f = next((x for x in files if x.endswith(f"{base}.int8.onnx")), None) \
                or next((x for x in files if x.endswith(f"{base}.onnx")), None)
            if f: picked.append(f)
    elif arch == "whisper":
        for base in ("encoder","decoder"):
            f = next((x for x in files if x.endswith(f"{base}.int8.onnx")), None) \
                or next((x for x in files if x.endswith(f"{base}.onnx")), None)
            if f: picked.append(f)
    elif arch == "ctc":
        f = next((x for x in files if x == "model.int8.onnx"), None) \
            or next((x for x in files if x == "model.onnx"), None)
        if f: picked.append(f)
    tok = next((x for x in files if x.endswith("tokens.txt")), None)
    if tok: picked.append(tok)
    return picked

def hf_models():
    out = []
    for author in HF_AUTHORS:
        try:
            data = http_get(f"https://huggingface.co/api/models?author={author}&limit=1000&expand[]=siblings&expand[]=tags")
        except Exception as e:
            print(f"HF {author} error: {e}", file=sys.stderr)
            continue
        for m in data:
            mid = m.get("modelId", "")
            if not mid: continue
            tags = m.get("tags", [])
            if any(t in tags for t in ["text-to-speech","tts","vad","voice-activity-detection"]):
                continue
            short_for_filter = mid.split("/")[-1]
            if not is_asr(short_for_filter):
                continue
            files = [s["rfilename"] for s in m.get("siblings", [])]
            if not files: continue
            arch = classify_arch(files)
            if arch == "unknown": continue
            picked = pick_files(files, arch)
            if len(picked) < 2: continue
            base = f"https://huggingface.co/{mid}/resolve/main"
            short = mid.split("/")[-1]
            out.append({
                "id": f"hf-{short}".lower(),
                "name": short,
                "language": parse_langs(short),
                "sizeBytes": 0,
                "url": f"{base}/{picked[0]}",
                "format": "onnx",
                "engine": "sherpa-onnx",
                "arch": arch,
                "license": "Apache-2.0",
                "musicRating": "Unknown",
                "files": [{"name": f, "url": f"{base}/{f}"} for f in picked],
            })
    print(f"HF total: {len(out)}")
    return out

def gh_archives():
    out = []
    try:
        data = http_get(GH_API)
    except Exception as e:
        print(f"GH error: {e}", file=sys.stderr)
        return out
    for a in data.get("assets", []):
        name = a.get("name","")
        if not name.endswith(".tar.bz2"): continue
        short = name.replace("sherpa-onnx-","").replace(".tar.bz2","")
        if not is_asr(short): continue
        out.append({
            "id": f"gh-{short}".lower(),
            "name": short,
            "language": parse_langs(short),
            "sizeBytes": a.get("size",0),
            "url": a.get("browser_download_url"),
            "format": "tar.bz2",
            "engine": "sherpa-onnx",
            "arch": "unknown",
            "license": "Apache-2.0",
            "musicRating": "Unknown",
            "files": [],
        })
    print(f"GH: {len(out)}")
    return out

def main():
    manual = json.loads(MANUAL.read_text())["models"] if MANUAL.exists() else []
    print(f"manual: {len(manual)}")

    # GH archives are tar.bz2 with unknown internal layout — Vosk, Moonshine,
    # raw single-file models, etc. sherpa-onnx only runs clean HF-style
    # onnx models with tokens.txt. Re-enable only if extractor can be verified
    # against every archive shape.
    auto = hf_models()

    by_id = {}
    for e in manual:
        by_id[e["id"].lower()] = e
    for e in auto:
        if e["id"] not in by_id:
            by_id[e["id"]] = e

    entries = list(by_id.values())
    entries.sort(key=lambda e: (e["id"].startswith("gh-"), e["name"].lower()))

    out = {
        "version": 3,
        "generated": datetime.datetime.utcnow().isoformat() + "Z",
        "count": len(entries),
        "models": entries,
    }
    Path("models.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"WROTE models.json with {len(entries)} entries")

if __name__ == "__main__":
    main()
