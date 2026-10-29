#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

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
import os
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')
S = 1024

# ── رنگ‌ها ──────────────────────────────────────────────
BG    = (253, 246, 245)   # #FDF6F5  سفید گل مریم با ته‌رنگ صورتی
TURQ  = (32, 178, 170)    # #20B2AA  فیروزه‌ای ملایم
CLEF  = BG                # کلید سل = رنگ بک‌گراند
GLYPH = '\U0001D11E'      # 𝄞

# ── مثلث رو به راست، گوشه‌گرد (بدون دایره) ─────────────
A = (260, 230)            # بالا-چپ
B = (260, 794)            # پایین-چپ
C = (830, 512)            # راست-وسط
R = 55                    # شعاع گردی گوشه (کوچک، فقط برای نرمی)

def rounded_tri_path(A, B, C, R):
    def u(vx, vy):
        m = (vx*vx + vy*vy) ** 0.5
        return (vx/m, vy/m)
    def p(v, dir, r):
        return (v[0] + dir[0]*r, v[1] + dir[1]*r)

    AB = u(B[0]-A[0], B[1]-A[1]); AC = u(C[0]-A[0], C[1]-A[1])
    BA = u(A[0]-B[0], A[1]-B[1]); BC = u(C[0]-B[0], C[1]-B[1])
    CA = u(A[0]-C[0], A[1]-C[1]); CB = u(B[0]-C[0], B[1]-C[1])

    a1, a2 = p(A, AB, R), p(A, AC, R)
    b1, b2 = p(B, BA, R), p(B, BC, R)
    c1, c2 = p(C, CA, R), p(C, CB, R)

    def f(pt): return f"{pt[0]:.1f},{pt[1]:.1f}"
    return (
        f"M{f(a1)} A{R},{R} 0 0 1 {f(a2)} "
        f"L{f(c1)} A{R},{R} 0 0 1 {f(c2)} "
        f"L{f(b2)} A{R},{R} 0 0 1 {f(b1)} Z"
    )

path_d = rounded_tri_path(A, B, C, R)

# ── پیش‌نمایش کامل ─────────────────────────────────────
img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)

# مثلث با fill + گوشه‌گرد (با polygon ساده + arc در گوشه‌ها شبیه‌سازی می‌کنیم)
d.polygon([A, B, C], fill=TURQ + (255,))
# گوشه‌گرد: روی هر رأس یک دایره‌ی هم‌رنگ می‌کشیم اما کوچک (R=55) — این‌بار خیلی محسوس نیست
for v in (A, B, C):
    d.ellipse([v[0]-R, v[1]-R, v[0]+R, v[1]+R], fill=TURQ + (255,))

# کلید سل بولد (stroke_width)
cx = (A[0] + B[0] + C[0]) // 3
cy = (A[1] + B[1] + C[1]) // 3
font = ImageFont.truetype(FONT, 380)
bb = d.textbbox((0, 0), GLYPH, font=font)
tw, th = bb[2]-bb[0], bb[3]-bb[1]
d.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
       font=font, fill=CLEF + (255,), stroke_width=10, stroke_fill=CLEF + (255,))

img.save(os.path.join(HOME, 'icon-v2-full.png'))

# ── پیش‌نمایش adaptive (ماسک دایره‌ای) ──────────────────
FG = Image.new('RGBA', (S, S), (0, 0, 0, 0))
fd = ImageDraw.Draw(FG)
sc = 0.61
off = int(S * (1 - sc) / 2)
A2 = (int(A[0]*sc)+off, int(A[1]*sc)+off)
B2 = (int(B[0]*sc)+off, int(B[1]*sc)+off)
C2 = (int(C[0]*sc)+off, int(C[1]*sc)+off)
R2 = int(R*sc)
fd.polygon([A2, B2, C2], fill=TURQ + (255,))
for v in (A2, B2, C2):
    fd.ellipse([v[0]-R2, v[1]-R2, v[0]+R2, v[1]+R2], fill=TURQ + (255,))
cx2 = (A2[0] + B2[0] + C2[0]) // 3
cy2 = (A2[1] + B2[1] + C2[1]) // 3
f2 = ImageFont.truetype(FONT, int(380*sc))
bb2 = fd.textbbox((0, 0), GLYPH, font=f2)
tw2, th2 = bb2[2]-bb2[0], bb2[3]-bb2[1]
fd.text((cx2 - tw2//2 - bb2[0], cy2 - th2//2 - bb2[1]), GLYPH,
        font=f2, fill=CLEF + (255,), stroke_width=6, stroke_fill=CLEF + (255,))

comp = Image.new('RGBA', (S, S), BG + (255,))
comp.alpha_composite(FG)
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-v2-adaptive.png'))

# ── مونوکروم ────────────────────────────────────────────
MONO = Image.new('RGBA', (S, S), (0, 0, 0, 0))
md = ImageDraw.Draw(MONO)
md.polygon([A, B, C], outline=(255,255,255,255), width=26)
for v in (A, B, C):
    md.ellipse([v[0]-R, v[1]-R, v[0]+R, v[1]+R], outline=(255,255,255,255), width=26)
md.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
        font=font, fill=(255,255,255,255), stroke_width=10, stroke_fill=(255,255,255,255))
MONO.save(os.path.join(HOME, 'icon-v2-monochrome.png'))

print("previews:")
print("  ~/icon-v2-full.png")
print("  ~/icon-v2-adaptive.png")
print("  ~/icon-v2-monochrome.png")
print()
print("pathData for vector drawable:")
print(path_d)
PY
