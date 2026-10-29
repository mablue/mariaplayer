#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

echo "=== [A] Apply new palette to triangle XMLs ==="

cat > app/src/main/res/values/ic_launcher_background.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#567F66</color>
</resources>
XEOF

cat > app/src/main/res/drawable/ic_launcher_foreground.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#E0E6E8"
        android:pathData="M48.66,26.26 L80.69,44.76 A10.68,10.68 0 0 1 80.69,63.24 L48.66,81.74 A10.68,10.68 0 0 1 32.65,72.45 L32.65,35.55 A10.68,10.68 0 0 1 48.66,26.26 Z" />
</vector>
XEOF

cat > app/src/main/res/drawable/ic_launcher_monochrome.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M48.66,26.26 L80.69,44.76 A10.68,10.68 0 0 1 80.69,63.24 L48.66,81.74 A10.68,10.68 0 0 1 32.65,72.45 L32.65,35.55 A10.68,10.68 0 0 1 48.66,26.26 Z" />
</vector>
XEOF

cat > app/src/main/res/drawable/ic_notification.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M48.66,26.26 L80.69,44.76 A10.68,10.68 0 0 1 80.69,63.24 L48.66,81.74 A10.68,10.68 0 0 1 32.65,72.45 L32.65,35.55 A10.68,10.68 0 0 1 48.66,26.26 Z" />
</vector>
XEOF

echo "palette applied"

echo
echo "=== [B] Commit + push (triangle with new palette) ==="
git add -A
git commit -qm "feat(icon): palette #567F66 bg / #E0E6E8 triangle on rounded equilateral geo"
for attempt in 1 2 3 4 5; do
  if git push origin main 2>/dev/null; then echo "pushed (attempt $attempt)"; break; fi
  echo "push retry $attempt"; git pull --rebase origin main 2>/dev/null || true; sleep 3
done

echo
echo "=== [C] Generate clef previews (NOT committed) ==="
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
FONT=~/NotoSansSymbols2-Regular.ttf
[ -s "$FONT" ] || {
  for url in \
    "https://raw.githubusercontent.com/notofonts/noto-fonts/main/hinted/ttf/NotoSansSymbols2/NotoSansSymbols2-Regular.ttf" \
    "https://github.com/google/fonts/raw/main/ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf"; do
    curl -fL -o "$FONT" "$url" 2>/dev/null && break
  done
}
[ -s "$FONT" ] || { echo "FATAL: font missing"; exit 1; }

python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT_PATH = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')

BG   = (0x56, 0x7F, 0x66)
TRI  = (0xE0, 0xE6, 0xE8)
GOLD = (0xC6, 0xAC, 0x40)
SAGE = (0x9A, 0x9E, 0x80)
GRN  = BG

S = 1024
sc = S / 108
s = 74.0
r = s / (4 * math.sqrt(3))
cx, cy = 54.0, 54.0
xa = cx + s * math.sqrt(3) / 3
xl = cx - s * math.sqrt(3) / 6
yt, yb = cy - s / 2, cy + s / 2
dt = r * math.sqrt(3)
sqrt3_2 = math.sqrt(3) / 2

TL_s = (xl + dt * sqrt3_2, yt + dt * 0.5)
R_t  = (xa - dt * sqrt3_2, cy - dt * 0.5)
R_b  = (xa - dt * sqrt3_2, cy + dt * 0.5)
BL_s = (xl + dt * sqrt3_2, yb - dt * 0.5)

def unit(v):
    m = math.hypot(*v); return (v[0]/m, v[1]/m)
def ac(V):
    u = unit((cx-V[0], cy-V[1])); return (V[0]+2*r*u[0], V[1]+2*r*u[1])
c_TL = ac((xl, yt)); c_BL = ac((xl, yb)); c_R = ac((xa, cy))

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
target_h_px = (74 * 0.60) * sc  # 60% of triangle height

fsz = int(target_h_px * 1.3)
for _ in range(6):
    font = ImageFont.truetype(FONT_PATH, fsz)
    bb = ImageDraw.Draw(Image.new('RGB', (10,10))).textbbox((0,0), GLYPH, font=font)
    gh = bb[3] - bb[1]
    fsz = max(10, int(fsz * target_h_px / gh))
font = ImageFont.truetype(FONT_PATH, fsz)
bb = ImageDraw.Draw(Image.new('RGB', (10,10))).textbbox((0,0), GLYPH, font=font)
gw, gh = bb[2]-bb[0], bb[3]-bb[1]

CLEF_CX = 53.0 * sc   # slightly left of bbox center (56.67) — since apex extends right
CLEF_CY = 54.0 * sc
x = CLEF_CX - gw/2 - bb[0]
y = CLEF_CY - gh/2 - bb[1]
stroke_w = max(1, int(3 * sc))

def make(color, label):
    img = Image.new('RGBA', (S, S), BG + (255,))
    dr = ImageDraw.Draw(img)
    dr.polygon(poly, fill=TRI + (255,))
    dr.text((x, y), GLYPH, font=font, fill=color + (255,),
            stroke_width=stroke_w, stroke_fill=color + (255,))
    for sz, lbl in ((512, '512'), (144, '144'), (48, '48')):
        img.resize((sz, sz), Image.LANCZOS).save(
            os.path.join(HOME, f'icon-clef-{label}-{lbl}.png'))
    print(f'  icon-clef-{label}-{{512,144,48}}.png  (clef=#{color[0]:02X}{color[1]:02X}{color[2]:02X})')

print("previews:")
make(GOLD, 'gold')     # #C6AC40
make(GRN,  'cutout')   # #567F66 same as bg → cut-out look
make(SAGE, 'sage')     # #9A9E80
PY

echo
echo "=== [D] Done ==="
echo "triangle (new palette) committed + pushed → CI build starting"
echo "clef previews are ONLY previews (~/), nothing committed"
