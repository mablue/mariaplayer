#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

FONT="$HOME/.fonts/NotoMusic-Regular.ttf"
[ -s "$FONT" ] || { echo "FATAL: font missing"; exit 1; }
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

if command -v rsvg-convert >/dev/null 2>&1; then R=rsvg
elif python3 -c "import cairosvg" 2>/dev/null; then R=cairosvg
else pkg install -y librsvg >/dev/null 2>&1 || true
     command -v rsvg-convert >/dev/null 2>&1 && R=rsvg || R=none
fi
[ "$R" != none ] || { echo "FATAL: no SVG renderer"; exit 1; }
echo "renderer: $R"

cat > ~/.render_two_color.py << 'PYEOF'
import os, sys, math, subprocess
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
try:
    from fontTools.pens.boundsPen import BoundsPen as BP
except ImportError:
    from fontTools.pens.boundsPen import ControlBoundsPen as BP
from PIL import Image

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, '.fonts', 'NotoMusic-Regular.ttf')
R = sys.argv[1]
CX, CY = 54.0, 54.0

def triangle_path(s):
    r = s / (4 * math.sqrt(3))
    dt = r * math.sqrt(3)
    xl = CX - s * math.sqrt(3) / 4 + r / 2
    xa = xl + s * math.sqrt(3) / 2
    yt, yb = CY - s/2, CY + s/2
    sq = math.sqrt(3) / 2
    TL_s = (xl + dt*sq, yt + dt/2)
    R_t  = (xa - dt*sq, CY - dt/2)
    R_b  = (xa - dt*sq, CY + dt/2)
    BL_s = (xl + dt*sq, yb - dt/2)
    BL_v = (xl, yb - dt)
    TL_v = (xl, yt + dt)
    f = lambda p: f"{p[0]:.2f},{p[1]:.2f}"
    return (f"M{f(TL_s)} L{f(R_t)} A{r:.2f},{r:.2f} 0 0 1 {f(R_b)} "
            f"L{f(BL_s)} A{r:.2f},{r:.2f} 0 0 1 {f(BL_v)} "
            f"L{f(TL_v)} A{r:.2f},{r:.2f} 0 0 1 {f(TL_s)} Z")

def clef_path(h_target):
    f = TTFont(FONT)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[0x1D11E]
    g = gs[name]
    bp = BP(gs); g.draw(bp)
    bx0, by0, bx1, by1 = bp.bounds
    gw, gh = bx1 - bx0, by1 - by0
    sc = min(h_target / gw, h_target / gh)
    tx = CX - sc * (bx0 + bx1) / 2
    ty = CY + sc * (by0 + by1) / 2
    pen = SVGPathPen(gs)
    g.draw(TransformPen(pen, Transform(sc, 0, 0, -sc, tx, ty)))
    return pen.getCommands()

def rasterize(svg, out512, out144, out48):
    if R == 'rsvg':
        with open(out512, 'wb') as fh:
            subprocess.run(['rsvg-convert', '-w', '512', '-h', '512', svg],
                           stdout=fh, check=True)
    else:
        import cairosvg
        cairosvg.svg2png(url=svg, write_to=out512, output_width=512)
    img = Image.open(out512)
    img.resize((144, 144), Image.LANCZOS).save(out144)
    img.resize((48, 48), Image.LANCZOS).save(out48)

def make(name, tri_s, clef_h, bg_clef, tri_fill):
    tri = triangle_path(tri_s)
    clef = clef_path(clef_h)
    svg = os.path.join(HOME, f'tc-{name}.svg')
    with open(svg, 'w') as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n')
        f.write(f'  <rect width="108" height="108" fill="{bg_clef}"/>\n')
        f.write(f'  <path d="{tri}" fill="{tri_fill}"/>\n')
        f.write(f'  <path d="{clef}" fill="{bg_clef}"/>\n')   # clef = bg color
        f.write('</svg>\n')
    rasterize(svg,
              os.path.join(HOME, f'tc-{name}-512.png'),
              os.path.join(HOME, f'tc-{name}-144.png'),
              os.path.join(HOME, f'tc-{name}-48.png'))
    print(f"  ok  {name}  tri={tri_s}  clef={clef_h}  bg/clef={bg_clef}  tri={tri_fill}")

# name | tri_size | clef_size | BG & CLEF color | TRIANGLE color
variants = [
    # same palette, increasing clef spill
    ("01_subtle",      64, 68, "#3A5A40", "#F5EFE6"),   # dark sage / cream — clef slightly bigger
    ("02_spill",       60, 74, "#3A5A40", "#F5EFE6"),   # smaller triangle, clearly bigger clef
    ("03_dramatic",    56, 80, "#3A5A40", "#F5EFE6"),   # small triangle, big clef spill

    # other palettes at "medium spill" size
    ("04_forest",      60, 74, "#2D6A4F", "#FDF6F5"),   # forest green / ivory
    ("05_midnight",    60, 74, "#1B3A2F", "#C9A227"),   # midnight green / gold triangle
    ("06_inverted",    60, 74, "#C9A227", "#3A5A40"),   # gold bg/clef, sage triangle
]

for v in variants:
    make(*v)

print()
print(f"wrote {len(variants)} × 3 sizes into ~/tc-*-{{512,144,48}}.png")
PYEOF

python3 ~/.render_two_color.py "$R"

if command -v montage >/dev/null 2>&1; then
  montage -background '#111' -fill white -pointsize 16 -label '%t' \
    -tile 3x2 -geometry 260x260+8+8 ~/tc-*-512.png ~/tc-contact-sheet.png 2>/dev/null \
    && echo "contact sheet: ~/tc-contact-sheet.png"
else
  pkg install -y imagemagick >/dev/null 2>&1 || true
  command -v montage >/dev/null 2>&1 && \
    montage -background '#111' -fill white -pointsize 16 -label '%t' \
      -tile 3x2 -geometry 260x260+8+8 ~/tc-*-512.png ~/tc-contact-sheet.png \
    && echo "contact sheet: ~/tc-contact-sheet.png" || \
    echo "(montage unavailable — view individual files instead)"
fi

echo
echo "=== done, nothing committed ==="
echo "Open the contact sheet:"
echo "  termux-open ~/tc-contact-sheet.png"
