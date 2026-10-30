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

cat > ~/.render_clef_small.py << 'PYEOF'
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

def clef_bounds(h_target):
    """Return (pathData, bb_w, bb_h) in 108-space."""
    f = TTFont(FONT)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[0x1D11E]
    g = gs[name]
    bp = BP(gs); g.draw(bp)
    bx0, by0, bx1, by1 = bp.bounds
    gw, gh = bx1 - bx0, by1 - by0
    sc = h_target / gh                         # fit to height
    tx = CX - sc * (bx0 + bx1) / 2
    ty = CY + sc * (by0 + by1) / 2
    pen = SVGPathPen(gs)
    g.draw(TransformPen(pen, Transform(sc, 0, 0, -sc, tx, ty)))
    return pen.getCommands(), gw * sc, gh * sc

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
    clef, cw, ch = clef_bounds(clef_h)
    svg = os.path.join(HOME, f'cs-{name}.svg')
    with open(svg, 'w') as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n')
        f.write(f'  <rect width="108" height="108" fill="{bg_clef}"/>\n')
        f.write(f'  <path d="{tri}" fill="{tri_fill}"/>\n')
        f.write(f'  <path d="{clef}" fill="{bg_clef}"/>\n')
        f.write('</svg>\n')
    rasterize(svg,
              os.path.join(HOME, f'cs-{name}-512.png'),
              os.path.join(HOME, f'cs-{name}-144.png'),
              os.path.join(HOME, f'cs-{name}-48.png'))
    # within-triangle heuristic: clef_h must be < tri_s * (something like 0.85)
    inside = "inside" if clef_h < tri_s * 0.88 else "spill"
    print(f"  ok  {name}  tri={tri_s}  clef={clef_h} ({inside})  clef bbox={cw:.1f}×{ch:.1f}")

# name | triangle side | clef height (both in 108-space)
# safe: clef bbox must be ≤ ~62 to fit in 33dp safe circle
variants = [
    ("01_xs",  68, 34, "#3A5A40", "#F5EFE6"),   # very small, clearly inside
    ("02_s",   68, 40, "#3A5A40", "#F5EFE6"),   # small, comfortable
    ("03_m",   68, 46, "#3A5A40", "#F5EFE6"),   # medium
    ("04_ml",  68, 52, "#3A5A40", "#F5EFE6"),   # medium-large, near triangle edge
    ("05_l",   68, 58, "#3A5A40", "#F5EFE6"),   # large, slight spill
    # palette alternates at "small" size
    ("06_forest_s", 68, 40, "#2D6A4F", "#FDF6F5"),
    ("07_gold_s",   68, 40, "#C9A227", "#3A5A40"),
    ("08_pink_s",   68, 40, "#3A5A40", "#F5EFE6"),  # will add pinkish clef separately below? no—keep two-color rule
]

for v in variants:
    make(*v)

# For "pink" variant, we want the clef color to be a rose tone but triangle cream.
# Since user wants exactly two colors, and clef = bg, we can't do pink clef without pink bg.
# So 08 uses a soft dusty pink as BG+clef, cream triangle.
# Redo 08 with pink instead.
make("08_pink_s", 68, 40, "#B88A8A", "#F5EFE6")

print()
print(f"wrote {len(variants)} variants into ~/cs-*-{{512,144,48}}.png")
PYEOF

python3 ~/.render_clef_small.py "$R"

if command -v montage >/dev/null 2>&1; then
  montage -background '#111' -fill white -pointsize 16 -label '%t' \
    -tile 4x2 -geometry 240x240+8+8 ~/cs-*-512.png ~/cs-contact-sheet.png 2>/dev/null \
    && echo "contact sheet: ~/cs-contact-sheet.png" || true
fi

echo
echo "=== nothing committed ==="
echo "  termux-open ~/cs-contact-sheet.png"
