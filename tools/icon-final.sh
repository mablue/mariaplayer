#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

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
import os, math
from PIL import Image, ImageDraw, ImageFont, ImageChops

HOME = os.path.expanduser('~')
FONT_PATH = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')
S = 1024

# ── پالت ─────────────────────────────────────────────────
BG    = (253, 246, 245)   # #FDF6F5  سفید گل مریم
TURQ  = (32, 178, 170)    # #20B2AA  فیروزه‌ای
CLEF  = BG                # کلید سل = رنگ بک‌گراند (cut-out)
GLYPH = '\U0001D11E'

# ── هندسه‌ی مثلث (بزرگ، داخل safe zone) ──────────────────
A = (235, 210)
B = (235, 814)
C = (830, 512)
R = 42                    # گوشه‌گرد — فقط روی خود مثلث

def unit(vx, vy):
    m = math.hypot(vx, vy)
    return (vx/m, vy/m)

def offset_pt(v, u, r):
    return (v[0] + u[0]*r, v[1] + u[1]*r)

def rounded_tri_png(draw, A, B, C, r, fill):
    # سه رأس + arc در هر گوشه (بدون دایره‌ی اضافی)
    AB = unit(B[0]-A[0], B[1]-A[1]); AC = unit(C[0]-A[0], C[1]-A[1])
    BA = unit(A[0]-B[0], A[1]-B[1]); BC = unit(C[0]-B[0], C[1]-B[1])
    CA = unit(A[0]-C[0], A[1]-C[1]); CB = unit(B[0]-C[0], B[1]-C[1])

    a1, a2 = offset_pt(A, AB, r), offset_pt(A, AC, r)
    b1, b2 = offset_pt(B, BA, r), offset_pt(B, BC, r)
    c1, c2 = offset_pt(C, CA, r), offset_pt(C, CB, r)

    # میان‌بر: polygon + arc در هر گوشه با ImageDraw.arc
    draw.polygon([a1, a2, c1, c2, b2, b1], fill=fill)
    # arc گوشه‌ی A
    draw.pieslice([A[0]-r, A[1]-r, A[0]+r, A[1]+r], 0, 360, fill=fill)
    # arc گوشه‌ی B
    draw.pieslice([B[0]-r, B[1]-r, B[0]+r, B[1]+r], 0, 360, fill=fill)
    # arc گوشه‌ی C
    draw.pieslice([C[0]-r, C[1]-r, C[0]+r, C[1]+r], 0, 360, fill=fill)

def tri_path_d(A, B, C, r):
    def u(vx, vy): return unit(vx, vy)
    def p(v, dir, rr): return offset_pt(v, dir, rr)
    AB = u(B[0]-A[0], B[1]-A[1]); AC = u(C[0]-A[0], C[1]-A[1])
    BA = u(A[0]-B[0], A[1]-B[1]); BC = u(C[0]-B[0], C[1]-B[1])
    CA = u(A[0]-C[0], A[1]-C[1]); CB = u(B[0]-C[0], B[1]-C[1])
    a1, a2 = p(A, AB, r), p(A, AC, r)
    b1, b2 = p(B, BA, r), p(B, BC, r)
    c1, c2 = p(C, CA, r), p(C, CB, r)
    f = lambda pt: f"{pt[0]:.1f},{pt[1]:.1f}"
    return (f"M{f(a1)} A{r},{r} 0 0 1 {f(a2)} "
            f"L{f(c1)} A{r},{r} 0 0 1 {f(c2)} "
            f"L{f(b2)} A{r},{r} 0 0 1 {f(b1)} Z")

# ── پیش‌نمایش کامل ──────────────────────────────────────
img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)

rounded_tri_png(d, A, B, C, R, TURQ + (255,))

# کلید سل بولد داخل مثلث
cx = (A[0] + B[0] + C[0]) // 3
cy = (A[1] + B[1] + C[1]) // 3
font = ImageFont.truetype(FONT_PATH, 420)
bb = d.textbbox((0, 0), GLYPH, font=font)
tw, th = bb[2]-bb[0], bb[3]-bb[1]
d.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
       font=font, fill=CLEF + (255,), stroke_width=12, stroke_fill=CLEF + (255,))

img.save(os.path.join(HOME, 'icon-final-full.png'))

# ── پیش‌نمایش adaptive (ماسک دایره‌ای) ──────────────────
sc = 0.61
off = int(S * (1-sc) / 2)
A2 = (int(A[0]*sc)+off, int(A[1]*sc)+off)
B2 = (int(B[0]*sc)+off, int(B[1]*sc)+off)
C2 = (int(C[0]*sc)+off, int(C[1]*sc)+off)
R2 = int(R*sc)
FG = Image.new('RGBA', (S, S), (0,0,0,0))
fd = ImageDraw.Draw(FG)
rounded_tri_png(fd, A2, B2, C2, R2, TURQ + (255,))
cx2 = (A2[0]+B2[0]+C2[0])//3; cy2 = (A2[1]+B2[1]+C2[1])//3
f2 = ImageFont.truetype(FONT_PATH, int(420*sc))
bb2 = fd.textbbox((0,0), GLYPH, font=f2)
fd.text((cx2 - (bb2[2]-bb2[0])//2 - bb2[0], cy2 - (bb2[3]-bb2[1])//2 - bb2[1]),
        GLYPH, font=f2, fill=CLEF + (255,), stroke_width=7, stroke_fill=CLEF + (255,))

comp = Image.new('RGBA', (S,S), BG + (255,))
comp.alpha_composite(FG)
mask = Image.new('L', (S,S), 0)
ImageDraw.Draw(mask).ellipse([0,0,S,S], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-final-adaptive.png'))

# ── مونوکروم (Themed Icons) ─────────────────────────────
MONO = Image.new('RGBA', (S,S), (0,0,0,0))
md = ImageDraw.Draw(MONO)
rounded_tri_png(md, A, B, C, R, (255,255,255,255))
md.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
        font=font, fill=(255,255,255,255), stroke_width=12, stroke_fill=(255,255,255,255))
MONO.save(os.path.join(HOME, 'icon-final-monochrome.png'))

# ── pathData برای vector drawable ───────────────────────
path_d = tri_path_d(A, B, C, R)
print("PREVIEWS WRITTEN:")
print("  ~/icon-final-full.png")
print("  ~/icon-final-adaptive.png")
print("  ~/icon-final-monochrome.png")
print()
print("TRIANGLE pathData (viewport 108x108 = 1024px / 9.48):")
# convert 1024px coords to 108 viewport
def to108(v):
    return (v[0]*108/S, v[1]*108/S)
sc108 = 108/S
f = lambda pt: f"{pt[0]:.2f},{pt[1]:.2f}"
A108 = to108(A); B108 = to108(B); C108 = to108(C); R108 = R*sc108
# regenerate for 108
def u108(vx,vy): return unit(vx,vy)
AB = u108(B108[0]-A108[0], B108[1]-A108[1]); AC = u108(C108[0]-A108[0], C108[1]-A108[1])
BA = u108(A108[0]-B108[0], A108[1]-B108[1]); BC = u108(C108[0]-B108[0], C108[1]-B108[1])
CA = u108(A108[0]-C108[0], A108[1]-C108[1]); CB = u108(B108[0]-C108[0], B108[1]-C108[1])
a1,a2 = offset_pt(A108,AB,R108), offset_pt(A108,AC,R108)
b1,b2 = offset_pt(B108,BA,R108), offset_pt(B108,BC,R108)
c1,c2 = offset_pt(C108,CA,R108), offset_pt(C108,CB,R108)
print(f"M{f(a1)} A{R108:.2f},{R108:.2f} 0 0 1 {f(a2)} "
      f"L{f(c1)} A{R108:.2f},{R108:.2f} 0 0 1 {f(c2)} "
      f"L{f(b2)} A{R108:.2f},{R108:.2f} 0 0 1 {f(b1)} Z")
PY
