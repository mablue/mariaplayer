#!/usr/bin/env python3
"""Generate models.json catalog from HuggingFace + GitHub releases.

Queries:
  1. HuggingFace API: all csukuangfj/sherpa-onnx-* ASR models
  2. GitHub releases: k2-fsa/sherpa-onnx tag asr-models assets

Output: models.json at repo root.

No model files are downloaded — only metadata.
"""
import json, re, sys, urllib.request, urllib.error
from pathlib import Path

HF_API = "https://huggingface.co/api/models"
GH_API = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models"

# Language code → human name
LANG_NAMES = {
    "en": "English", "zh": "Chinese", "fa": "Persian", "ar": "Arabic",
    "tr": "Turkish", "ru": "Russian", "de": "German", "fr": "French",
    "es": "Spanish", "it": "Italian", "ja": "Japanese", "ko": "Korean",
    "hi": "Hindi", "vi": "Vietnamese", "th": "Thai", "id": "Indonesian",
    "ms": "Malay", "pt": "Portuguese", "nl": "Dutch", "pl": "Polish",
    "uk": "Ukrainian", "cs": "Czech", "hu": "Hungarian", "ro": "Romanian",
    "bn": "Bengali", "ta": "Tamil", "te": "Telugu", "ur": "Urdu",
    "he": "Hebrew", "el": "Greek", "fi": "Finnish", "sv": "Swedish",
    "da": "Danish", "no": "Norwegian", "ca": "Catalan", "tl": "Tagalog",
    "my": "Burmese", "km": "Khmer", "lo": "Lao", "si": "Sinhala",
    "multi": "Multilingual",
}

def http_get(url, timeout=30):
    req = urllib.request.Request(url, headers={
        "User-Agent": "mariaplayer-catalog/1.0",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())

def parse_langs(name):
    """Extract language codes from sherpa-onnx model repo name."""
    codes = []
    lower = name.lower()
    # bilingual / multilingual patterns
    if "bilingual" in lower or "trilingual" in lower:
        codes.append("multi")
    if "multilingual" in lower:
        codes.append("multi")
    # explicit 2-letter codes after sherpa-onnx-
    for m in re.finditer(r"(?:^|-)([a-z]{2})(?:-|$)", lower):
        code = m.group(1)
        if code in LANG_NAMES and code not in codes:
            codes.append(code)
    if not codes:
        codes.append("en")  # default assumption
    return codes

def classify_arch(files):
    """Determine ASR architecture from file names."""
    has_encoder = any("encoder" in f for f in files)
    has_decoder = any("decoder" in f for f in files)
    has_joiner = any("joiner" in f for f in files)
    has_model = any(f == "model.onnx" or f == "model.int8.onnx" for f in files)
    if has_encoder and has_decoder and has_joiner:
        return "rnnt"
    if has_encoder and has_decoder:
        return "whisper"
    if has_model:
        return "ctc"
    return "unknown"

def hf_candidates():
    """Fetch all sherpa-onnx ASR models from HuggingFace."""
    out = []
    try:
        data = http_get(f"{HF_API}?author=csukuangfj&search=sherpa-onnx&limit=1000")
    except Exception as e:
        print(f"HF API error: {e}", file=sys.stderr)
        return out

    for m in data:
        mid = m.get("modelId", "")
        if not mid.startswith("csukuangfj/sherpa-onnx-"):
            continue
        # skip non-ASR tasks
        tags = m.get("tags", [])
        if any(t in tags for t in ["text-to-speech", "tts", "vad"]):
            continue
        out.append(mid)
    print(f"HF: {len(out)} ASR candidates")
    return out

def hf_model_files(repo):
    """List files in an HF repo."""
    try:
        info = http_get(f"{HF_API}/{repo}")
        return [s["rfilename"] for s in info.get("siblings", [])]
    except Exception:
        return []

def build_hf_entry(repo, files):
    """Build a ModelEntry dict from HF repo + files."""
    short = repo.replace("csukuangfj/sherpa-onnx-", "")
    arch = classify_arch(files)
    if arch == "unknown":
        return None
    langs = parse_langs(repo)
    base = f"https://huggingface.co/{repo}/resolve/main"

    # pick the relevant files
    picked = []
    if arch == "rnnt":
        for base_name in ("encoder", "decoder", "joiner"):
            f = next((x for x in files if x.endswith(f"{base_name}.int8.onnx")), None) \
                or next((x for x in files if x.endswith(f"{base_name}.onnx")), None)
            if f: picked.append(f)
    elif arch == "whisper":
        for base_name in ("encoder", "decoder"):
            f = next((x for x in files if x.endswith(f"{base_name}.int8.onnx")), None) \
                or next((x for x in files if x.endswith(f"{base_name}.onnx")), None)
            if f: picked.append(f)
    elif arch == "ctc":
        f = next((x for x in files if x == "model.int8.onnx"), None) \
            or next((x for x in files if x == "model.onnx"), None)
        if f: picked.append(f)

    tok = next((x for x in files if x.endswith("tokens.txt")), None)
    if tok: picked.append(tok)
    if len(picked) < 2:
        return None

    return {
        "id": f"hf-{short}".replace("/", "-"),
        "name": short,
        "language": langs,
        "sizeBytes": 0,
        "url": f"{base}/{picked[0]}",
        "format": "onnx",
        "engine": "sherpa-onnx",
        "arch": arch,
        "license": "Apache-2.0",
        "musicRating": "Unknown",
        "files": [{"name": f, "url": f"{base}/{f}"} for f in picked],
    }

def gh_assets():
    """Fetch ASR model archives from GitHub release."""
    out = []
    try:
        data = http_get(GH_API)
    except Exception as e:
        print(f"GH API error: {e}", file=sys.stderr)
        return out
    for a in data.get("assets", []):
        name = a.get("name", "")
        if not name.endswith(".tar.bz2"):
            continue
        url = a.get("browser_download_url")
        size = a.get("size", 0)
        short = name.replace("sherpa-onnx-", "").replace(".tar.bz2", "")
        langs = parse_langs(short)
        out.append({
            "id": f"gh-{short}",
            "name": short,
            "language": langs,
            "sizeBytes": size,
            "url": url,
            "format": "tar.bz2",
            "engine": "sherpa-onnx",
            "arch": "unknown",
            "license": "Apache-2.0",
            "musicRating": "Unknown",
            "files": [],
        })
    print(f"GH: {len(out)} ASR archives")
    return out

def main():
    entries = []

    for repo in hf_candidates():
        files = hf_model_files(repo)
        e = build_hf_entry(repo, files)
        if e:
            entries.append(e)

    entries.extend(gh_assets())

    # dedupe by id
    seen = set()
    uniq = []
    for e in entries:
        if e["id"] in seen: continue
        seen.add(e["id"])
        uniq.append(e)

    # sort: HF first by name, then GH
    uniq.sort(key=lambda e: (e["id"].startswith("gh-"), e["name"].lower()))

    out = {
        "version": 2,
        "generated": __import__("datetime").datetime.utcnow().isoformat() + "Z",
        "count": len(uniq),
        "models": uniq,
    }
    Path("models.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"wrote models.json with {len(uniq)} entries")

if __name__ == "__main__":
    main()
