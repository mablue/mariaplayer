#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

FONT="$HOME/.fonts/NotoMusic-Regular.ttf"
[ -s "$FONT" ] || { echo "FATAL: $FONT missing (run the download step first)"; exit 1; }

python3 - << 'PY'
import os, sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform

FONT = os.path.expanduser('~/.fonts/NotoMusic-Regular.ttf')

try:
    from fontTools.pens.boundsPen import BoundsPen as BoundsPenCls
    bp_name = 'BoundsPen'
except ImportError:
    from fontTools.pens.boundsPen import ControlBoundsPen as BoundsPenCls
    bp_name = 'ControlBoundsPen'

f = TTFont(FONT)
cmap = f.getBestCmap()
cp = 0x1D11E
if cp not in cmap:
    print(f"FATAL: U+{cp:04X} not in font"); sys.exit(1)
name = cmap[cp]
gset = f.getGlyphSet()
glyph = gset[name]

bp = BoundsPenCls(gset)
glyph.draw(bp)
if bp.bounds is None:
    print(f"FATAL: glyph '{name}' empty"); sys.exit(1)
bx0, by0, bx1, by1 = bp.bounds
gw, gh = bx1 - bx0, by1 - by0
print(f"glyph: {name}")
print(f"bounds ({bp_name}): x[{bx0:.1f},{bx1:.1f}] y[{by0:.1f},{by1:.1f}]  size {gw:.1f}x{gh:.1f}")

TARGET = 40.0
scale = min(TARGET/gw, TARGET/gh)
tx = 54.0 - scale * (bx0 + bx1) / 2
ty = 54.0 + scale * (by0 + by1) / 2
print(f"scale={scale:.5f}  tx={tx:.3f}  ty={ty:.3f}")

t = Transform(scale, 0, 0, -scale, tx, ty)
pen = SVGPathPen(gset)
glyph.draw(TransformPen(pen, t))
d = pen.getCommands()

if not d or not d.strip():
    print("FATAL: empty pathData"); sys.exit(1)

print()
print("=== FINAL pathData (108x108 viewport) ===")
print(d)
print()
print(f"(commands: M={d.count('M')} L={d.count('L')} Q={d.count('Q')} C={d.count('C')} Z={d.count('Z')}, chars={len(d)})")
PY
