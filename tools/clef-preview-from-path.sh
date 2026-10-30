#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

FONT="$HOME/.fonts/NotoMusic-Regular.ttf"
[ -s "$FONT" ] || { echo "FATAL: font missing"; exit 1; }
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

# ── 1. Extract pathData, save to file ──────────────────────
python3 - << 'PY'
import os, sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
try:
    from fontTools.pens.boundsPen import BoundsPen as BP
except ImportError:
    from fontTools.pens.boundsPen import ControlBoundsPen as BP

FONT = os.path.expanduser('~/.fonts/NotoMusic-Regular.ttf')
f = TTFont(FONT)
gset = f.getGlyphSet()
name = f.getBestCmap()[0x1D11E]
glyph = gset[name]
bp = BP(gset); glyph.draw(bp)
bx0,by0,bx1,by1 = bp.bounds
gw,gh = bx1-bx0, by1-by0
TARGET=40.0
sc=min(TARGET/gw, TARGET/gh)
tx=54.0 - sc*(bx0+bx1)/2
ty=54.0 + sc*(by0+by1)/2
t = Transform(sc, 0, 0, -sc, tx, ty)
pen = SVGPathPen(gset)
glyph.draw(TransformPen(pen, t))
d = pen.getCommands()

out = os.path.expanduser('~/clef_path.txt')
with open(out, 'w') as fh:
    fh.write(d)
print(f"path saved: {out}  ({len(d)} chars)")
PY

# ── 2. Build SVG (uses the exact saved pathData) ───────────
python3 - << 'PY'
import os
path = open(os.path.expanduser('~/clef_path.txt')).read().strip()
tri = "M48.66,26.26 L80.69,44.76 A10.68,10.68 0 0 1 80.69,63.24 L48.66,81.74 A10.68,10.68 0 0 1 32.65,72.45 L32.65,35.55 A10.68,10.68 0 0 1 48.66,26.26 Z"
svg = (
  '<?xml version="1.0" encoding="UTF-8"?>\n'
  '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n'
  '  <rect width="108" height="108" fill="#567F66"/>\n'
  f'  <path d="{tri}" fill="#E0E6E8"/>\n'
  f'  <path d="{path}" fill="#C6AC40"/>\n'
  '</svg>'
)
out = os.path.expanduser('~/clef-preview.svg')
open(out, 'w').write(svg)
print(f"SVG: {out}")
PY

# ── 3. Rasterize SVG → PNG ─────────────────────────────────
if python3 -c "import cairosvg" 2>/dev/null; then
  python3 -c "import cairosvg; cairosvg.svg2png(url='$HOME/clef-preview.svg', write_to='$HOME/clef-preview-512.png', output_width=512)"
  echo "rendered via cairosvg (already installed)"
elif command -v rsvg-convert >/dev/null 2>&1; then
  rsvg-convert -w 512 -h 512 ~/clef-preview.svg > ~/clef-preview-512.png
  echo "rendered via rsvg-convert"
elif command -v inkscape >/dev/null 2>&1; then
  inkscape ~/clef-preview.svg -o ~/clef-preview-512.png -w 512 -h 512 >/dev/null 2>&1
  echo "rendered via inkscape"
else
  echo "installing cairosvg..."
  pkg install -y cairo pkg-config 2>/dev/null || true
  pip install --quiet cairosvg 2>&1 | tail -5 || true
  if python3 -c "import cairosvg" 2>/dev/null; then
    python3 -c "import cairosvg; cairosvg.svg2png(url='$HOME/clef-preview.svg', write_to='$HOME/clef-preview-512.png', output_width=512)"
    echo "rendered via cairosvg"
  else
    echo "FATAL: could not install any SVG rasterizer."
    echo "Try manually: pkg install librsvg  (then re-run)"
    exit 1
  fi
fi

# ── 4. Downscale previews ─────────────────────────────────
python3 - << 'PY'
import os
from PIL import Image
src = os.path.expanduser('~/clef-preview-512.png')
img = Image.open(src)
for sz, lbl in ((144,'144'), (48,'48')):
    img.resize((sz,sz), Image.LANCZOS).save(os.path.expanduser(f'~/clef-preview-{lbl}.png'))
print("previews: ~/clef-preview-{512,144,48}.png")
PY

echo
echo "=== nothing committed. Preview, then decide. ==="
echo "If good → reply: commit clef"
echo "If wrong size/position/color → reply: adjust <what>"
