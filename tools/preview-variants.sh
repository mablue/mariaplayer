#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

FONT="$HOME/.fonts/NotoMusic-Regular.ttf"
[ -s "$FONT" ] || { echo "FATAL: font missing"; exit 1; }
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

# renderer detection
if command -v rsvg-convert >/dev/null 2>&1; then RENDERER=rsvg
elif python3 -c "import cairosvg" 2>/dev/null; then RENDERER=cairosvg
elif command -v inkscape >/dev/null 2>&1; then RENDERER=inkscape
else
  echo "installing librsvg..."
  pkg install -y librsvg >/dev/null 2>&1 || pip install --quiet cairosvg 2>/dev/null || true
  if command -v rsvg-convert >/dev/null 2>&1; then RENDERER=rsvg
  elif python3 -c "import cairosvg" 2>/dev/null; then RENDERER=cairosvg
  else echo "FATAL: no SVG renderer"; exit 1; fi
fi
echo "renderer: $RENDERER"

# ── Core generator (Python) ───────────────────────────────
cat > ~/.render_variants.py << 'PYEOF'
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
RENDERER = sys.argv[1]
CX, CY = 54.0, 54.0

def triangle_path(s):
    r = s / (4 * math.sqrt(3))
    dt = r * math.sqrt(3)
    xl = CX - s * math.sqrt(3) / 4 + r / 2   # bbox-centered
    xa = xl + s * math.sqrt(3) / 2
    yt, yb = CY - s / 2, CY + s / 2
    sq = math.sqrt(3) / 2

    TL_s = (xl + dt * sq, yt + dt / 2)
    R_t  = (xa - dt * sq, CY - dt / 2)
    R_b  = (xa - dt * sq, CY + dt / 2)
    BL_s = (xl + dt * sq, yb - dt / 2)
    BL_v = (xl, yb - dt)
    TL_v = (xl, yt + dt)

    def ac(v):
        dx, dy = CX - v[0], CY - v[1]
        m = math.hypot(dx, dy)
        return (v[0] + 2*r*dx/m, v[1] + 2*r*dy/m)

    f = lambda p: f"{p[0]:.2f},{p[1]:.2f}"
    return (
        f"M{f(TL_s)} L{f(R_t)} "
        f"A{r:.2f},{r:.2f} 0 0 1 {f(R_b)} "
        f"L{f(BL_s)} "
        f"A{r:.2f},{r:.2f} 0 0 1 {f(BL_v)} "
        f"L{f(TL_v)} "
        f"A{r:.2f},{r:.2f} 0 0 1 {f(TL_s)} Z"
    )

_clef_cache = {}
def clef_path(target_dp):
    if target_dp in _clef_cache:
        return _clef_cache[target_dp]
    f = TTFont(FONT)
    gset = f.getGlyphSet()
    name = f.getBestCmap()[0x1D11E]
    glyph = gset[name]
    bp = BP(gset); glyph.draw(bp)
    bx0, by0, bx1, by1 = bp.bounds
    gw, gh = bx1 - bx0, by1 - by0
    sc = min(target_dp / gw, target_dp / gh)
    tx = CX - sc * (bx0 + bx1) / 2
    ty = CY + sc * (by0 + by1) / 2
    t = Transform(sc, 0, 0, -sc, tx, ty)
    pen = SVGPathPen(gset)
    glyph.draw(TransformPen(pen, t))
    d = pen.getCommands()
    _clef_cache[target_dp] = d
    return d

def build_svg(bg_spec, tri_color, clef_color, s, clef_size):
    tri = triangle_path(s)
    clef = clef_path(clef_size)
    parts = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">']

    kind = bg_spec[0]
    if kind == 'solid':
        parts.append(f'<rect width="108" height="108" fill="{bg_spec[1]}"/>')
    elif kind == 'linear':
        c1, c2 = bg_spec[1], bg_spec[2]
        parts.append(f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">'
                     f'<stop offset="0%" stop-color="{c1}"/><stop offset="100%" stop-color="{c2}"/>'
                     f'</linearGradient></defs>')
        parts.append('<rect width="108" height="108" fill="url(#g)"/>')
    elif kind == 'radial':
        c1, c2 = bg_spec[1], bg_spec[2]
        parts.append(f'<defs><radialGradient id="g" cx="50%" cy="50%" r="70%">'
                     f'<stop offset="0%" stop-color="{c1}"/><stop offset="100%" stop-color="{c2}"/>'
                     f'</radialGradient></defs>')
        parts.append('<rect width="108" height="108" fill="url(#g)"/>')
    elif kind == 'dots':
        base = bg_spec[1]
        parts.append(f'<defs><pattern id="p" width="6" height="6" patternUnits="userSpaceOnUse">'
                     f'<circle cx="3" cy="3" r="0.5" fill="#FFFFFF" opacity="0.07"/></pattern></defs>')
        parts.append(f'<rect width="108" height="108" fill="{base}"/>')
        parts.append('<rect width="108" height="108" fill="url(#p)"/>')
    elif kind == 'stripes':
        base = bg_spec[1]
        parts.append(f'<defs><pattern id="p" width="8" height="8" patternUnits="userSpaceOnUse" '
                     f'patternTransform="rotate(45)"><rect width="3" height="8" fill="#FFFFFF" opacity="0.05"/></pattern></defs>')
        parts.append(f'<rect width="108" height="108" fill="{base}"/>')
        parts.append('<rect width="108" height="108" fill="url(#p)"/>')

    parts.append(f'<path d="{tri}" fill="{tri_color}"/>')
    parts.append(f'<path d="{clef}" fill="{clef_color}"/>')
    parts.append('</svg>')
    return '\n'.join(parts)

def rasterize(svg_path, out512, out144, out48):
    if RENDERER == 'rsvg':
        with open(out512, 'wb') as fh:
            subprocess.run(['rsvg-convert', '-w', '512', '-h', '512', svg_path],
                           stdout=fh, check=True)
    elif RENDERER == 'cairosvg':
        import cairosvg
        cairosvg.svg2png(url=svg_path, write_to=out512, output_width=512)
    else:
        subprocess.run(['inkscape', svg_path, '-o', out512, '-w', '512', '-h', '512'],
                       check=True, capture_output=True)
    img = Image.open(out512)
    img.resize((144, 144), Image.LANCZOS).save(out144)
    img.resize((48, 48), Image.LANCZOS).save(out48)

# ── Variant matrix ────────────────────────────────────────
# name | s | clef | bg_spec | tri_color | clef_color
# bg_spec: 'kind|args'
variants = [
    ("01_sage_solid",    68, 40, ("solid",  "#3A5A40"),                    "#F5EFE6", "#C9A227"),
    ("02_forest_solid",  72, 44, ("solid",  "#2D6A4F"),                    "#FDF6F5", "#C6AC40"),
    ("03_midnight_rad",  72, 44, ("radial", "#3A5A40", "#1B3A2F"),         "#F0EBE3", "#C9A227"),
    ("04_sage_gradient", 72, 44, ("linear", "#3A5A40", "#2D6A4F"),         "#F5EFE6", "#C9A227"),
    ("05_user_palette",  72, 44, ("solid",  "#567F66"),                    "#E0E6E8", "#C8B860"),
    ("06_cutout_clef",   72, 44, ("solid",  "#3A5A40"),                    "#F5EFE6", "#3A5A40"),
    ("07_dots_texture",  72, 44, ("dots",   "#3A5A40"),                    "#F5EFE6", "#C9A227"),
    ("08_stripes_tex",   72, 44, ("stripes","#2D6A4F"),                    "#FDF6F5", "#C6AC40"),
    ("09_xl_sage",       76, 48, ("solid",  "#3A5A40"),                    "#F5EFE6", "#C9A227"),
    ("10_xl_bold",       76, 52, ("solid",  "#2D6A4F"),                    "#FDF6F5", "#C6AC40"),
    ("11_pale_pink",     72, 44, ("solid",  "#3A5A40"),                    "#F5EFE6", "#EAD5D5"),
    ("12_modern_mix",    72, 48, ("linear", "#1B3A2F", "#3A5A40"),         "#F0EBE3", "#E8D9A0"),
]

for name, s, clef, bg, tri, clef_c in variants:
    svg = os.path.join(HOME, f'prev-{name}.svg')
    with open(svg, 'w') as f:
        f.write(build_svg(bg, tri, clef_c, s, clef))
    try:
        rasterize(svg,
                  os.path.join(HOME, f'prev-{name}-512.png'),
                  os.path.join(HOME, f'prev-{name}-144.png'),
                  os.path.join(HOME, f'prev-{name}-48.png'))
        print(f"  ok  {name}  (s={s}, clef={clef}, bg={bg[0]})")
    except Exception as e:
        print(f"  FAIL {name}: {e}")

print()
print(f"wrote {len(variants)} variants to ~/prev-*-{{512,144,48}}.png")
PYEOF

python3 ~/.render_variants.py "$RENDERER"

# ── Contact sheet (all 12 in one image) ───────────────────
if command -v montage >/dev/null 2>&1; then
  montage -background '#111' -fill white -pointsize 14 -label '%t' \
    -tile 4x3 -geometry 240x240+6+6 \
    ~/prev-*-512.png ~/contact-sheet.png 2>/dev/null \
    && echo "contact sheet: ~/contact-sheet.png"
else
  echo "(install imagemagick for a contact sheet: pkg install imagemagick)"
fi

echo
echo "=== done. Nothing committed. ==="
echo
echo "Open the contact sheet first (all 12 at once):"
echo "  termux-open ~/contact-sheet.png"
echo
echo "Or a specific size:"
ls ~/prev-*-512.png | sed 's|.*/||;s|^|  ~/|'
