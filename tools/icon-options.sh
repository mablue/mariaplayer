#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

FONT=~/NotoSansSymbols2-Regular.ttf
[ -s "$FONT" ] || {
  for url in \
    "https://raw.githubusercontent.com/notofonts/noto-fonts/main/hinted/ttf/NotoSansSymbols2/NotoSansSymbols2-Regular.ttf" \
    "https://github.com/google/fonts/raw/main/ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf"; do
    curl -fL -o "$FONT" "$url" 2>/dev/null && break
  done
}
[ -s "$FONT" ] || { echo "FATAL: font"; exit 1; }

python3 - <<'PY'
import os
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')

# Real Unicode glyphs
CLEF  = '\U0001D11E'   # 𝄞 treble clef
DNOTE = '\u266B'       # ♫ beamed double note
SNOTE = '\u266A'       # ♪ eighth note

# Palette
BLUSH = (253, 245, 242)   # #FDF5F2  Maryam-white (faint blush)
TURQ  = (0, 188, 212)     # #00BCD4  turquoise
DARK  = (26, 26, 46)      # #1A1A2E  near-black (fallback)

def render(bg, fg, glyph, size_ratio=0.85, stroke_ratio=0.025, canvas=1024):
    img = Image.new('RGBA', (canvas, canvas), bg + (255,))
    d = ImageDraw.Draw(img)

    # Auto-fit glyph ink to size_ratio of canvas
    probe_size = 500
    probe = ImageFont.truetype(FONT, probe_size)
    bb = d.textbbox((0, 0), glyph, font=probe)
    tw = bb[2] - bb[0]
    th = bb[3] - bb[1]
    target = canvas * size_ratio
    scale = min(target / tw, target / th)
    final_size = int(probe_size * scale)
    font = ImageFont.truetype(FONT, final_size)

    # Re-measure at final size
    bb = d.textbbox((0, 0), glyph, font=font)
    tw = bb[2] - bb[0]
    th = bb[3] - bb[1]
    x = (canvas - tw) // 2 - bb[0]
    y = (canvas - th) // 2 - bb[1]

    stroke = max(1, int(final_size * stroke_ratio))
    d.text((x, y), glyph, font=font, fill=fg + (255,),
           stroke_width=stroke, stroke_fill=fg + (255,))
    return img

def save_all(img, name):
    """Save at 512 (preview) + 144 (home screen) + 48 (notification)."""
    img.resize((512, 512), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-{name}-512.png'))
    img.resize((144, 144), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-{name}-144.png'))
    img.resize((48, 48), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-{name}-48.png'))
    print(f'  icon-{name}-512.png')

# ── A: bold clef, turquoise on Maryam-white ────────────────
save_all(render(BLUSH, TURQ, CLEF, size_ratio=0.82, stroke_ratio=0.024), 'A')

# ── B: bold clef, Maryam-white on turquoise ────────────────
save_all(render(TURQ, BLUSH, CLEF, size_ratio=0.82, stroke_ratio=0.024), 'B')

# ── C: beamed note ♫, turquoise on Maryam-white ────────────
save_all(render(BLUSH, TURQ, DNOTE, size_ratio=0.72, stroke_ratio=0.026), 'C')

# ── D: single note ♪, turquoise on Maryam-white ────────────
save_all(render(BLUSH, TURQ, SNOTE, size_ratio=0.72, stroke_ratio=0.028), 'D')

print("\nwrote 12 files (~/icon-{A,B,C,D}-{512,144,48}.png)")
PY
