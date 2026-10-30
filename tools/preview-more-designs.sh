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

cat > ~/.render_more.py << 'PYEOF'
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

# ── palettes (all Maryam-flower inspired) ───────────────
P = {
  'P1': ('#3A5A40', '#F5EFE6'),  # sage / cream
  'P2': ('#2D6A4F', '#FDF6F5'),  # forest / ivory
  'P3': ('#B88A8A', '#F5EFE6'),  # blush / cream
  'P4': ('#C9A227', '#3A5A40'),  # gold / sage
  'P5': ('#5D2A3B', '#FDF6F5'),  # burgundy / ivory
  'P6': ('#6B7A4D', '#F0EBE3'),  # moss / warm white
}

def tri_path(s, cx=CX, cy=CY):
    r = s / (4 * math.sqrt(3))
    dt = r * math.sqrt(3)
    xl = cx - s * math.sqrt(3) / 4 + r / 2
    xa = xl + s * math.sqrt(3) / 2
    yt, yb = cy - s / 2, cy + s / 2
    sq = math.sqrt(3) / 2
    TL_s = (xl + dt*sq, yt + dt/2)
    R_t  = (xa - dt*sq, cy - dt/2)
    R_b  = (xa - dt*sq, cy + dt/2)
    BL_s = (xl + dt*sq, yb - dt/2)
    BL_v = (xl, yb - dt)
    TL_v = (xl, yt + dt)
    f = lambda p: f"{p[0]:.2f},{p[1]:.2f}"
    return (f"M{f(TL_s)} L{f(R_t)} A{r:.2f},{r:.2f} 0 0 1 {f(R_b)} "
            f"L{f(BL_s)} A{r:.2f},{r:.2f} 0 0 1 {f(BL_v)} "
            f"L{f(TL_v)} A{r:.2f},{r:.2f} 0 0 1 {f(TL_s)} Z")

def clef_path(h_target, cx=CX, cy=CY):
    f = TTFont(FONT)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[0x1D11E]
    g = gs[name]
    bp = BP(gs); g.draw(bp)
    bx0, by0, bx1, by1 = bp.bounds
    gw, gh = bx1 - bx0, by1 - by0
    sc = min(h_target / gw, h_target / gh)
    tx = cx - sc * (bx0 + bx1) / 2
    ty = cy + sc * (by0 + by1) / 2
    pen = SVGPathPen(gs)
    g.draw(TransformPen(pen, Transform(sc, 0, 0, -sc, tx, ty)))
    return pen.getCommands()

def polygon_path(points):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"

def circle_pts(cx, cy, r, n=64):
    return [(cx + r*math.cos(2*math.pi*i/n),
             cy + r*math.sin(2*math.pi*i/n)) for i in range(n)]

def hexagon_pts(cx, cy, R_):
    return [(cx + R_*math.cos(math.radians(a)),
             cy + R_*math.sin(math.radians(a)))
            for a in (0, 60, 120, 180, 240, 300)]

def diamond_pts(cx, cy, R_):
    return [(cx, cy-R_), (cx+R_, cy), (cx, cy+R_), (cx-R_, cy)]

def star_pts(cx, cy, R_out, R_in):
    pts = []
    for i in range(10):
        r = R_out if i % 2 == 0 else R_in
        a = math.radians(-90 + i * 36)
        pts.append((cx + r*math.cos(a), cy + r*math.sin(a)))
    return pts

def squircle_path(cx, cy, size, r):
    x, y = cx - size/2, cy - size/2
    return (f"M{x+r:.2f},{y:.2f} L{x+size-r:.2f},{y:.2f} "
            f"A{r:.2f},{r:.2f} 0 0 1 {x+size:.2f},{y+r:.2f} "
            f"L{x+size:.2f},{y+size-r:.2f} "
            f"A{r:.2f},{r:.2f} 0 0 1 {x+size-r:.2f},{y+size:.2f} "
            f"L{x+r:.2f},{y+size:.2f} "
            f"A{r:.2f},{r:.2f} 0 0 1 {x:.2f},{y+size-r:.2f} "
            f"L{x:.2f},{y+r:.2f} "
            f"A{r:.2f},{r:.2f} 0 0 1 {x+r:.2f},{y:.2f} Z")

def rasterize(svg, o512, o144, o48):
    if R == 'rsvg':
        with open(o512, 'wb') as fh:
            subprocess.run(['rsvg-convert', '-w', '512', '-h', '512', svg],
                           stdout=fh, check=True)
    else:
        import cairosvg
        cairosvg.svg2png(url=svg, write_to=o512, output_width=512)
    img = Image.open(o512)
    img.resize((144, 144), Image.LANCZOS).save(o144)
    img.resize((48, 48), Image.LANCZOS).save(o48)

def write_svg(name, bg, body_elems):
    svg_path = os.path.join(HOME, f'md-{name}.svg')
    with open(svg_path, 'w') as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n')
        f.write(f'  <rect width="108" height="108" fill="{bg}"/>\n')
        for e in body_elems:
            f.write(f'  {e}\n')
        f.write('</svg>\n')
    rasterize(svg_path,
              os.path.join(HOME, f'md-{name}-512.png'),
              os.path.join(HOME, f'md-{name}-144.png'),
              os.path.join(HOME, f'md-{name}-48.png'))
    print(f"  ok  {name}")

# ────────────────────────────────────────────────────────
# 01. Circle + cut-out clef
bg, fg = P['P1']
write_svg('01_circle', bg, [
    f'<path d="{polygon_path(circle_pts(54, 54, 44))}" fill="{fg}"/>',
    f'<path d="{clef_path(50)}" fill="{bg}"/>',
])

# 02. Squircle + cut-out clef
bg, fg = P['P2']
write_svg('02_squircle', bg, [
    f'<path d="{squircle_path(54, 54, 78, 24)}" fill="{fg}"/>',
    f'<path d="{clef_path(50)}" fill="{bg}"/>',
])

# 03. Diamond + cut-out clef
bg, fg = P['P1']
write_svg('03_diamond', bg, [
    f'<path d="{polygon_path(diamond_pts(54, 54, 44))}" fill="{fg}"/>',
    f'<path d="{clef_path(46)}" fill="{bg}"/>',
])

# 04. Hexagon + cut-out clef
bg, fg = P['P2']
write_svg('04_hexagon', bg, [
    f'<path d="{polygon_path(hexagon_pts(54, 54, 42))}" fill="{fg}"/>',
    f'<path d="{clef_path(46)}" fill="{bg}"/>',
])

# 05. Star + cut-out clef
bg, fg = P['P4']
write_svg('05_star', bg, [
    f'<path d="{polygon_path(star_pts(54, 54, 44, 18))}" fill="{fg}"/>',
    f'<path d="{clef_path(42)}" fill="{bg}"/>',
])

# 06. Clef alone (huge)
bg, fg = P['P1']
write_svg('06_clef_only', bg, [
    f'<path d="{clef_path(64)}" fill="{fg}"/>',
])

# 07. Two overlapping triangles + clef in front
bg, fg = P['P3']
# two triangles: shifted copies (fast-forward)
t1 = tri_path(46, cx=48, cy=54)
t2 = tri_path(46, cx=58, cy=54)
write_svg('07_two_tri', bg, [
    f'<path d="{t1}" fill="{fg}" opacity="0.55"/>',
    f'<path d="{t2}" fill="{fg}"/>',
    f'<path d="{clef_path(40, cx=56)}" fill="{bg}"/>',
])

# 08. Play-button: circle + triangle + tiny cut-out clef
bg, fg = P['P4']
tri_small = tri_path(30)
write_svg('08_play_btn', bg, [
    f'<path d="{polygon_path(circle_pts(54, 54, 44))}" fill="{fg}"/>',
    f'<path d="{tri_small}" fill="{bg}"/>',
    f'<path d="{clef_path(20, cx=55)}" fill="{fg}"/>',
])

# 09. Rounded rect + 5 staff lines + clef cut-out
bg, fg = P['P2']
staff_elems = [f'<path d="{squircle_path(54, 54, 84, 22)}" fill="{fg}"/>']
for y in (34, 44, 54, 64, 74):
    staff_elems.append(f'<rect x="20" y="{y-0.8}" width="68" height="1.6" fill="{bg}"/>')
staff_elems.append(f'<path d="{clef_path(44)}" fill="{bg}"/>')
write_svg('09_staff', bg, staff_elems)

# 10. Tilted triangle (12°) + cut-out clef
bg, fg = P['P1']
tri_tilted = tri_path(64)
write_svg('10_tilt', bg, [
    f'<g transform="rotate(12 54 54)"><path d="{tri_tilted}" fill="{fg}"/></g>',
    f'<path d="{clef_path(44)}" fill="{bg}"/>',
])

# 11. Rounded triangle (baseline) + cut-out clef, burgundy
bg, fg = P['P5']
write_svg('11_burgundy', bg, [
    f'<path d="{tri_path(68)}" fill="{fg}"/>',
    f'<path d="{clef_path(42)}" fill="{bg}"/>',
])

# 12. Rounded triangle + cut-out clef, moss
bg, fg = P['P6']
write_svg('12_moss', bg, [
    f'<path d="{tri_path(68)}" fill="{fg}"/>',
    f'<path d="{clef_path(42)}" fill="{bg}"/>',
])

print()
print("wrote 12 designs into ~/md-*-{512,144,48}.png")
PYEOF

python3 ~/.render_more.py "$R"

# Contact sheet
if ! command -v montage >/dev/null 2>&1; then pkg install -y imagemagick >/dev/null 2>&1 || true; fi
if command -v montage >/dev/null 2>&1; then
  montage -background '#111' -fill white -pointsize 15 -label '%t' \
    -tile 4x3 -geometry 240x240+6+6 ~/md-*-512.png ~/md-contact-sheet.png 2>/dev/null \
    && echo "contact sheet: ~/md-contact-sheet.png"
fi

echo
echo "=== nothing committed ==="
echo "  termux-open ~/md-contact-sheet.png"
