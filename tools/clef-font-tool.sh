#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

FONT_DIR="$HOME/.fonts"
mkdir -p "$FONT_DIR"
FONT=""

# 尝试多个可靠来源下载 Noto Sans Symbols 2
echo "=== downloading Noto Sans Symbols 2 ==="
URLS=(
  "https://raw.githubusercontent.com/notofonts/notofonts.github.io/noto-monthly-release-2025.06.01/fonts/NotoSansSymbols2/full/ttf/NotoSansSymbols2-Regular.ttf"
  "https://github.com/notofonts/symbols/releases/download/NotoSansSymbols2-v2.008/NotoSansSymbols2-Regular.ttf"
  "https://raw.githubusercontent.com/fontsource/font-files/main/fonts/google/noto-sans-symbols-2/files/noto-sans-symbols-2-latin-400-normal.ttf"
)
for url in "${URLS[@]}"; do
  echo "  trying: $url"
  if curl -fL --connect-timeout 20 -m 120 -o "$FONT_DIR/NotoSansSymbols2-Regular.ttf" "$url" 2>/dev/null; then
    FONT="$FONT_DIR/NotoSansSymbols2-Regular.ttf"
    echo "  OK"
    break
  fi
done

# 如果 Symbols 2 失败，尝试 Noto Music
if [ -z "$FONT" ] || [ ! -s "$FONT" ]; then
  echo "  trying Noto Music as fallback..."
  curl -fL --connect-timeout 20 -m 120 \
    -o "$FONT_DIR/NotoMusic-Regular.ttf" \
    "https://github.com/notofonts/music/raw/main/fonts/NotoMusic/hinted/ttf/NotoMusic-Regular.ttf" 2>/dev/null \
    && FONT="$FONT_DIR/NotoMusic-Regular.ttf" || true
fi

[ -s "$FONT" ] || { echo "FATAL: no font downloaded"; exit 1; }
echo "font ready: $FONT ($(stat -c%s "$FONT") bytes)"

# 验证字体是否真的包含 U+1D11E
python3 - << PY
from fontTools.ttLib import TTFont
f = TTFont("$FONT")
cmap = f.getBestCmap()
cp = 0x1D11E
print(f"  U+{cp:04X} in font: {cp in cmap}")
if cp not in cmap:
    print("  FATAL: font does not contain G CLEF")
    import sys; sys.exit(1)
PY

echo
echo "=== rendering previews (3 colors) ==="
python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT_PATH = os.path.join(HOME, '.fonts', 'NotoSansSymbols2-Regular.ttf')
if not os.path.exists(FONT_PATH):
    FONT_PATH = os.path.join(HOME, '.fonts', 'NotoMusic-Regular.ttf')
S = 1024
sc = S / 108
BG   = (0x56, 0x7F, 0x66)
TRI  = (0xE0, 0xE6, 0xE8)
GOLD = (0xC6, 0xAC, 0x40)
SAGE = (0x9A, 0x9E, 0x80)
CUT  = BG

# triangle geometry (same as approved icon)
s = 74.0
r = s / (4 * math.sqrt(3))
cx, cy = 54.0, 54.0
xa = cx + s * math.sqrt(3) / 3
xl = cx - s * math.sqrt(3) / 6
yt, yb = cy - s / 2, cy + s / 2
dt = r * math.sqrt(3)
sqrt3_2 = math.sqrt(3) / 2

TL_s = (xl + dt*sqrt3_2, yt + dt*0.5)
R_t  = (xa - dt*sqrt3_2, cy - dt*0.5)
R_b  = (xa - dt*sqrt3_2, cy + dt*0.5)
BL_s = (xl + dt*sqrt3_2, yb - dt*0.5)

def unit(v):
    m = math.hypot(*v); return (v[0]/m, v[1]/m)
def ac(V):
    u = unit((cx-V[0], cy-V[1])); return (V[0]+2*r*u[0], V[1]+2*r*u[1])
c_TL, c_BL, c_R = ac((xl,yt)), ac((xl,yb)), ac((xa,cy))

def arc(c, radius, a0, a1, n=40):
    a0, a1 = math.radians(a0), math.radians(a1)
    while a1 <= a0: a1 += 2*math.pi
    return [(c[0]+radius*math.cos(a0+(a1-a0)*i/n),
             c[1]+radius*math.sin(a0+(a1-a0)*i/n)) for i in range(n+1)]

P = lambda p: (p[0]*sc, p[1]*sc)
poly  = [P(p) for p in arc(c_TL, r, 180, -60)]
poly += [P(R_t)]
poly += [P(p) for p in arc(c_R,  r, -60,  60)]
poly += [P(BL_s)]
poly += [P(p) for p in arc(c_BL, r,  60, 180)]

GLYPH = '\U0001D11E'
target_h = (74 * 0.55) * sc
fsz = int(target_h * 1.3)
dummy = ImageDraw.Draw(Image.new('RGB', (10,10)))
for _ in range(8):
    font = ImageFont.truetype(FONT_PATH, fsz)
    bb = dummy.textbbox((0,0), GLYPH, font=font)
    gh = bb[3]-bb[1]
    if gh == 0: break
    fsz = max(10, int(fsz * target_h / gh))
font = ImageFont.truetype(FONT_PATH, fsz)
bb = dummy.textbbox((0,0), GLYPH, font=font)
gw, gh = bb[2]-bb[0], bb[3]-bb[1]

# center clef slightly left of bbox-center because apex pushes right
CLEF_CX = 53.0 * sc
CLEF_CY = 54.0 * sc
x = CLEF_CX - gw/2 - bb[0]
y = CLEF_CY - gh/2 - bb[1]
stroke = max(1, int(4 * sc))

def make(color, label):
    img = Image.new('RGBA', (S, S), BG + (255,))
    dr = ImageDraw.Draw(img)
    dr.polygon(poly, fill=TRI + (255,))
    dr.text((x, y), GLYPH, font=font, fill=color + (255,),
            stroke_width=stroke, stroke_fill=color + (255,))
    for sz, lbl in ((512,'512'), (144,'144'), (48,'48')):
        img.resize((sz, sz), Image.LANCZOS).save(
            os.path.join(HOME, f'icon-clef-{label}-{lbl}.png'))
    print(f'  icon-clef-{label}-{{512,144,48}}.png')

make(GOLD, 'gold')
make(CUT,  'cutout')
make(SAGE, 'sage')
print("previews: ~/icon-clef-{gold,cutout,sage}-{512,144,48}.png")
PY

echo
echo "=== extracting real vector pathData for U+1D11E ==="
python3 - << 'PY'
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
import os

FONT_PATH = os.path.expanduser('~/.fonts/NotoSansSymbols2-Regular.ttf')
if not os.path.exists(FONT_PATH):
    FONT_PATH = os.path.expanduser('~/.fonts/NotoMusic-Regular.ttf')

f = TTFont(FONT_PATH)
cmap = f.getBestCmap()
glyph_name = cmap.get(0x1D11E)
if not glyph_name:
    print("FATAL: no glyph for U+1D11E")
    raise SystemExit(1)

gs = f.getGlyphSet()
glyph = gs[glyph_name]
bounds = glyph.getBounds()
print(f"glyph: {glyph_name}")
print(f"bounds (font units): {bounds}")

# Extract raw SVG path
pen = SVGPathPen(gs)
glyph.draw(pen)
raw_d = pen.getCommands()
print(f"\nraw pathData (font units):\n{raw_d[:200]}...")

# Compute scale to fit inside triangle safe-area (~40x40 dp window)
bx0, by0, bx1, by1 = bounds
gw, gh = bx1-bx0, by1-by0
target = 38.0  # dp, fits inside 74-side triangle comfortably
scale = min(target/gw, target/gh)
# Flip Y because font Y is up, SVG Y is down
# Translate glyph to origin then center at (54,54)
tx = 54.0 - (bx0 + gw/2) * scale
ty = 54.0 + (by0 + gh/2) * scale

t = Transform(scale, 0, 0, -scale, tx, ty)
pen2 = SVGPathPen(gs)
tp = TransformPen(pen2, t)
glyph.draw(tp)
d = pen2.getCommands()

print(f"\n=== FINAL pathData for ic_launcher_foreground.xml (108x108 viewport) ===")
print(d)
print(f"\n(scale={scale:.4f}, fits in {target}x{target} dp, centered at 54,54)")
PY

echo
echo "=== done. Nothing committed. Review ~/icon-clef-*.png first. ==="
