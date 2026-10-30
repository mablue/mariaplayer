#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

FONT_DIR="$HOME/.fonts"; mkdir -p "$FONT_DIR"
FONT=""

echo "=== [1/3] downloading Noto Music (contains U+1D11E) ==="
URLS=(
  "https://fonts.gstatic.com/s/notomusic/v21/pe0rMIiSN5pO63htf1sxIteQB9Zra1U.ttf"
  "https://github.com/google/fonts/raw/main/ofl/notomusic/NotoMusic-Regular.ttf"
  "https://github.com/notofonts/notofonts.github.io/raw/NotoMusic-v2.003/fonts/NotoMusic/NotoMusic-Regular.ttf"
)
for url in "${URLS[@]}"; do
  echo "  trying: $url"
  if curl -fL --connect-timeout 20 -m 180 -o "$FONT_DIR/NotoMusic-Regular.ttf" "$url" 2>/dev/null; then
    if [ -s "$FONT_DIR/NotoMusic-Regular.ttf" ]; then
      FONT="$FONT_DIR/NotoMusic-Regular.ttf"
      echo "  OK ($(stat -c%s "$FONT") bytes)"
      break
    fi
  fi
done

if [ -z "$FONT" ]; then
  echo "  Noto Music download failed — will fall back to hard-coded SVG path"
else
  echo "=== [2/3] verifying U+1D11E in font ==="
  python3 - << PY
from fontTools.ttLib import TTFont
import sys
f = TTFont("$FONT")
cmap = f.getBestCmap()
cp = 0x1D11E
print(f"  U+{cp:04X} in font: {cp in cmap}")
sys.exit(0 if cp in cmap else 1)
PY
  if [ $? -ne 0 ]; then
    echo "  U+1D11E missing from Noto Music — fall back to SVG path"
    FONT=""
  fi
fi

# ── Path A: extract real outline from Noto Music ──────────
if [ -n "$FONT" ]; then
  echo
  echo "=== [3/3] extracting real vector pathData ==="
  python3 - << 'PY'
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
import os

FONT = os.path.expanduser('~/.fonts/NotoMusic-Regular.ttf')
f = TTFont(FONT)
cmap = f.getBestCmap()
name = cmap[0x1D11E]
gs = f.getGlyphSet()
glyph = gs[name]
b = glyph.getBounds()
gw, gh = b[2]-b[0], b[3]-b[1]
print(f"glyph: {name}")
print(f"bounds: {b}  ({gw:.0f} x {gh:.0f} font-units)")

# scale to fit ~40dp window inside the 74-side triangle
target = 40.0
scale = min(target/gw, target/gh)
tx = 54.0 - (b[0] + gw/2) * scale
ty = 54.0 + (b[1] + gh/2) * scale

t = Transform(scale, 0, 0, -scale, tx, ty)  # flip Y for SVG/Android
pen = SVGPathPen(gs)
tp = TransformPen(pen, t)
glyph.draw(tp)
d = pen.getCommands()
print()
print("=== FINAL pathData (108x108 viewport) ===")
print(d)
print()
print(f"(scale={scale:.4f}, centered at 54,54)")
PY

# ── Path B: fall back to known-good SVG path ──────────────
else
  echo
  echo "=== [3/3] using hard-coded SVG path (viewBox 0 0 512 512) ==="
  echo
  echo "source: pub.dev g_clef constant"
  echo
  echo "This path is already in 512x512 space."
  echo "Scale factor to 108x108 viewport: 108/512 = 0.2109375"
  echo
  echo "=== FINAL pathData (108x108 viewport) ==="
  python3 - << 'PY'
# Known-good g_clef path, viewBox 0 0 512 512
D_512 = (
    "m238.75 138.492 18.147-18.829c.898-21.472 9.107-59.37 26.166-60.771 "
    "6.994.097 14.566 5.833 15.742 16.021 3.25 28.145-11.833 40.563-29.072 "
    "57.372l-31.408 32.586-.037-.038c-28.467 31.447-65.355 70.37-69.772 "
    "106.973-5.588 53.77 31.264 108.42 87.149 105.693l-3.178-18.066c-37.522-4.905-"
    "69.771-43.77-56.711-81.312 23.275-60.413 75.664-92.822 116.333-148.022 "
    "11.474-15.574 14.575-46.945 11.815-63.128-2.76-16.184-14.345-43.066-"
    "32.059-45.017-34.714-4.59-60.724 62.476-53.114 116.538zm25.667 75.896-"
    "15.508 15.248c.542 2.775 1.166 6.185 2.088 12.14-34.017 21.505-36.576 "
    "66.707-12.186 90.96 5.177 5.31 15.165-2.767 10.104-8.674-10.971-19.786-"
    "12.097-44.028 5.742-57.137 8.78 50.539 24.627 117.16 30.164 170.895.364 "
    "3.535-6.593 10.614-9.973 11.396 0-22.091-17.908-40-40-40-22.09 0-40 17.909-"
    "40 40 .017 20.193 15.266 36.007 35.123 39.668 42.008 7.745 78.659-17.935 "
    "72.76-53.16-8.793-59.062-21.986-121.007-30.857-175.32 12.066-1.253 28.875 "
    "5.169 36.428 12.847 8.575 9.297 12.95 22.35 11.982 32.787-.924 9.96-7.425 "
    "20.992-17.06 30.381l3.662 20.776c17.346-12.583 35.117-30.185 36.94-49.844 "
    "1.518-16.352-5.01-40.324-18.08-52.273-16.885-15.437-38.313-18.553-57.349-"
    "16.612-2.004-13.065-3.018-18.039-3.98-24.078z"
)
SCALE = 108/512
print("(use a <group android:scaleX=\"0.2109375\" android:scaleY=\"0.2109375\"> wrapper)")
print()
print(D_512)
PY
fi

echo
echo "=== rendering previews ==="
python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, '.fonts', 'NotoMusic-Regular.ttf')
S = 1024; sc = S/108
BG   = (0x56,0x7F,0x66); TRI=(0xE0,0xE6,0xE8)
GOLD = (0xC6,0xAC,0x40); SAGE=(0x9A,0x9E,0x80); CUT=BG

s=74.0; r=s/(4*math.sqrt(3)); cx,cy=54.0,54.0
xa=cx+s*math.sqrt(3)/3; xl=cx-s*math.sqrt(3)/6
yt,yb=cy-s/2,cy+s/2; dt=r*math.sqrt(3); sq=math.sqrt(3)/2
TL_s=(xl+dt*sq, yt+dt*0.5); R_t=(xa-dt*sq, cy-dt*0.5)
R_b=(xa-dt*sq, cy+dt*0.5); BL_s=(xl+dt*sq, yb-dt*0.5)

def u(v):
    m=math.hypot(*v); return (v[0]/m,v[1]/m)
def ac(V):
    d=u((cx-V[0],cy-V[1])); return (V[0]+2*r*d[0], V[1]+2*r*d[1])
c_TL,c_BL,c_R = ac((xl,yt)),ac((xl,yb)),ac((xa,cy))
def arc(c,rad,a0,a1,n=40):
    a0,a1=math.radians(a0),math.radians(a1)
    while a1<=a0: a1+=2*math.pi
    return [(c[0]+rad*math.cos(a0+(a1-a0)*i/n), c[1]+rad*math.sin(a0+(a1-a0)*i/n)) for i in range(n+1)]
P=lambda p:(p[0]*sc,p[1]*sc)
poly =[P(p) for p in arc(c_TL,r,180,-60)]+[P(R_t)]+[P(p) for p in arc(c_R,r,-60,60)]+[P(BL_s)]+[P(p) for p in arc(c_BL,r,60,180)]

if not os.path.exists(FONT):
    print("  no font → skipping previews"); raise SystemExit(0)

GLYPH='\U0001D11E'
target_h=(74*0.55)*sc
fsz=int(target_h*1.3); dummy=ImageDraw.Draw(Image.new('RGB',(10,10)))
for _ in range(8):
    font=ImageFont.truetype(FONT,fsz)
    bb=dummy.textbbox((0,0),GLYPH,font=font)
    gh=bb[3]-bb[1]
    if gh==0: break
    fsz=max(10,int(fsz*target_h/gh))
font=ImageFont.truetype(FONT,fsz)
bb=dummy.textbbox((0,0),GLYPH,font=font)
gw,gh=bb[2]-bb[0],bb[3]-bb[1]
x=53.0*sc-gw/2-bb[0]; y=54.0*sc-gh/2-bb[1]
stroke=max(1,int(4*sc))

for color,label in [(GOLD,'gold'),(CUT,'cutout'),(SAGE,'sage')]:
    img=Image.new('RGBA',(S,S),BG+(255,))
    dr=ImageDraw.Draw(img)
    dr.polygon(poly,fill=TRI+(255,))
    dr.text((x,y),GLYPH,font=font,fill=color+(255,),stroke_width=stroke,stroke_fill=color+(255,))
    for sz,lbl in ((512,'512'),(144,'144'),(48,'48')):
        img.resize((sz,sz),Image.LANCZOS).save(os.path.join(HOME,f'icon-clef-{label}-{lbl}.png'))
    print(f'  icon-clef-{label}-{{512,144,48}}.png')
PY

echo
echo "done. Preview first, then decide."
