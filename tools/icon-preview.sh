#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

# Font with 𝄞 (U+1D11E) — reuse if already downloaded
FONT=~/NotoSansSymbols2-Regular.ttf
if [ ! -s "$FONT" ]; then
  for url in \
    "https://raw.githubusercontent.com/notofonts/noto-fonts/main/hinted/ttf/NotoSansSymbols2/NotoSansSymbols2-Regular.ttf" \
    "https://github.com/google/fonts/raw/main/ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf"; do
    if curl -fL -o "$FONT" "$url" 2>/dev/null; then break; fi
  done
fi
[ -s "$FONT" ] || { echo "FATAL: font download failed"; exit 1; }

python3 - <<'PY'
import os
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')
SIZE = 1024

# ── Palette ──────────────────────────────────────────────
BG   = (194, 139, 158)   # #C28B9E  "گل مریم" dusty rose
GREEN = (46, 125, 79)    # #2E7D4F  forest green
CLEF = BG                # clef = same as bg → cut-out look

GLYPH = '\U0001D11E'     # 𝄞 treble clef

# ── Right-pointing rounded triangle (▶) ─────────────────
A = (200, 200)   # top-left
B = (200, 824)   # bottom-left
C = (850, 512)   # right-middle
R = 60           # corner radius

def rounded_triangle_path(A, B, C, R):
    """Return pathData string for right-pointing rounded triangle."""
    def unit(vx, vy):
        m = (vx*vx + vy*vy) ** 0.5
        return (vx/m, vy/m)
    def pt(v, u, r):
        return (v[0] + u[0]*r, v[1] + u[1]*r)

    AB = (B[0]-A[0], B[1]-A[1]); AC = (C[0]-A[0], C[1]-A[1])
    BA = (A[0]-B[0], A[1]-B[1]); BC = (C[0]-B[0], C[1]-B[1])
    CA = (A[0]-C[0], A[1]-C[1]); CB = (B[0]-C[0], B[1]-C[1])

    uAB = unit(*AB); uAC = unit(*AC)
    uBA = unit(*BA); uBC = unit(*BC)
    uCA = unit(*CA); uCB = unit(*CB)

    a1 = pt(A, uAB, R); a2 = pt(A, uAC, R)
    b1 = pt(B, uBA, R); b2 = pt(B, uBC, R)
    c1 = pt(C, uCA, R); c2 = pt(C, uCB, R)

    def f(p): return f"{p[0]:.1f},{p[1]:.1f}"
    # A -> arc -> line to C arc start -> arc -> line to B arc start -> arc -> line back to A arc start -> close
    return (
        f"M{f(a1)} "
        f"A{R},{R} 0 0 1 {f(a2)} "
        f"L{f(c1)} "
        f"A{R},{R} 0 0 1 {f(c2)} "
        f"L{f(b2)} "
        f"A{R},{R} 0 0 1 {f(b1)} "
        f"Z"
    )

triangle_d = rounded_triangle_path(A, B, C, R)

# ── Render preview ───────────────────────────────────────
img = Image.new('RGBA', (SIZE, SIZE), BG + (255,))
d = ImageDraw.Draw(img)

# solid triangle fill
d.polygon([A, B, C], fill=GREEN + (255,))
# round the three corners
for v in (A, B, C):
    d.ellipse([v[0]-R, v[1]-R, v[0]+R, v[1]+R], fill=GREEN + (255,))

# treble clef, background-coloured, centered in triangle
cx = (A[0] + B[0] + C[0]) // 3
cy = (A[1] + B[1] + C[1]) // 3
font = ImageFont.truetype(FONT, 360)
bb = d.textbbox((0, 0), GLYPH, font=font)
tw, th = bb[2]-bb[0], bb[3]-bb[1]
d.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
       font=font, fill=CLEF + (255,))

img.save(os.path.join(HOME, 'icon-preview-full.png'))

# ── Adaptive icon previews (108dp canvas, 66dp safe circle) ────
# Foreground on transparent, content scaled to 66dp safe zone
FG = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
fg_draw = ImageDraw.Draw(FG)
scale = 0.61  # 66/108
offset = int(SIZE * (1-scale) / 2)
A2 = (int(A[0]*scale)+offset, int(A[1]*scale)+offset)
B2 = (int(B[0]*scale)+offset, int(B[1]*scale)+offset)
C2 = (int(C[0]*scale)+offset, int(C[1]*scale)+offset)
R2 = int(R*scale)
fg_draw.polygon([A2, B2, C2], fill=GREEN + (255,))
for v in (A2, B2, C2):
    fg_draw.ellipse([v[0]-R2, v[1]-R2, v[0]+R2, v[1]+R2], fill=GREEN + (255,))
cx2 = (A2[0] + B2[0] + C2[0]) // 3
cy2 = (A2[1] + B2[1] + C2[1]) // 3
font2 = ImageFont.truetype(FONT, int(360*scale))
bb2 = fg_draw.textbbox((0, 0), GLYPH, font=font2)
tw2, th2 = bb2[2]-bb2[0], bb2[3]-bb2[1]
fg_draw.text((cx2 - tw2//2 - bb2[0], cy2 - th2//2 - bb2[1]), GLYPH,
             font=font2, fill=CLEF + (255,))
FG.save(os.path.join(HOME, 'icon-preview-foreground.png'))

# ── Adaptive composite with circular mask ─────────────────
comp = Image.new('RGBA', (SIZE, SIZE), BG + (255,))
comp.alpha_composite(FG)
mask = Image.new('L', (SIZE, SIZE), 0)
mask_draw = ImageDraw.Draw(mask)
mask_draw.ellipse([0, 0, SIZE, SIZE], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-preview-adaptive.png'))

# ── Monochrome for Android 13+ (white outline + clef) ───
MONO = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
m_draw = ImageDraw.Draw(MONO)
m_draw.polygon([A, B, C], outline=(255,255,255,255), width=22)
# round corners
for v in (A, B, C):
    m_draw.ellipse([v[0]-R, v[1]-R, v[0]+R, v[1]+R], outline=(255,255,255,255), width=22)
m_draw.text((cx - tw//2 - bb[0], cy - th//2 - bb[1]), GLYPH,
            font=font, fill=(255,255,255,255))
MONO.save(os.path.join(HOME, 'icon-preview-monochrome.png'))

print("previews written:")
print("  ~/icon-preview-full.png")
print("  ~/icon-preview-foreground.png")
print("  ~/icon-preview-adaptive.png")
print("  ~/icon-preview-monochrome.png")
print()
print("triangle pathData (for vector drawable):")
print(triangle_d)
PY
