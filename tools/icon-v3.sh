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
import os, math
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT_PATH = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')
S = 1024

# ── Colors (change here to retry) ──────────────────────
BG    = (253, 245, 242)   # #FDF5F2 Maryam-white, subtle blush
TURQ  = (0, 188, 212)     # #00BCD4 Material Cyan 500
CLEF  = BG                # clef = background → cut-out effect

GLYPH = '\U0001D11E'      # 𝄞

# ── Triangle (right-pointing play ▶) ───────────────────
A = (250, 220)            # top-left
B = (250, 804)            # bottom-left
C = (820, 512)            # mid-right
R = 55                    # corner radius (change: 30 sharper, 100 rounder)

def rounded_triangle_points(A, B, C, radius, seg=50):
    """Generate perimeter points of a triangle with TRUE rounded corners
       (arcs replace each vertex). No extra circles drawn."""
    vertices = [A, B, C]
    n = 3
    pts = []
    for i in range(n):
        V = vertices[i]
        P = vertices[(i-1) % n]
        N = vertices[(i+1) % n]

        d_prev = (P[0]-V[0], P[1]-V[1])
        l_prev = math.hypot(*d_prev) or 1
        u_prev = (d_prev[0]/l_prev, d_prev[1]/l_prev)

        d_next = (N[0]-V[0], N[1]-V[1])
        l_next = math.hypot(*d_next) or 1
        u_next = (d_next[0]/l_next, d_next[1]/l_next)

        a_prev = math.atan2(u_prev[1], u_prev[0])
        a_next = math.atan2(u_next[1], u_next[0])

        diff = a_next - a_prev
        while diff > math.pi: diff -= 2*math.pi
        while diff < -math.pi: diff += 2*math.pi

        for j in range(seg + 1):
            t = j / seg
            a = a_prev + diff * t
            pts.append((V[0] + radius*math.cos(a),
                        V[1] + radius*math.sin(a)))
    return pts

def draw_clef(d, cx, cy, size, color, stroke):
    font = ImageFont.truetype(FONT_PATH, size)
    bb = d.textbbox((0, 0), GLYPH, font=font)
    x = cx - (bb[0] + bb[2]) / 2
    y = cy - (bb[1] + bb[3]) / 2
    d.text((x, y), GLYPH, font=font, fill=color,
           stroke_width=stroke, stroke_fill=color)

# ── 1) Full-icon preview ───────────────────────────────
img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)
d.polygon(rounded_triangle_points(A, B, C, R), fill=TURQ + (255,))
cx = (A[0] + B[0] + C[0]) // 3
cy = (A[1] + B[1] + C[1]) // 3
draw_clef(d, cx, cy, 380, CLEF + (255,), 14)
img.save(os.path.join(HOME, 'icon-v3-full.png'))

# ── 2) Adaptive preview (what Android actually renders) ─
sc = 0.61
off = int(S * (1 - sc) / 2)
A2 = (int(A[0]*sc)+off, int(A[1]*sc)+off)
B2 = (int(B[0]*sc)+off, int(B[1]*sc)+off)
C2 = (int(C[0]*sc)+off, int(C[1]*sc)+off)
R2 = int(R * sc)

FG = Image.new('RGBA', (S, S), (0, 0, 0, 0))
fd = ImageDraw.Draw(FG)
fd.polygon(rounded_triangle_points(A2, B2, C2, R2), fill=TURQ + (255,))
cx2 = (A2[0] + B2[0] + C2[0]) // 3
cy2 = (A2[1] + B2[1] + C2[1]) // 3
draw_clef(fd, cx2, cy2, int(380*sc), CLEF + (255,), int(14*sc))

comp = Image.new('RGBA', (S, S), BG + (255,))
comp.alpha_composite(FG)
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-v3-adaptive.png'))

# ── 3) Real-size previews ──────────────────────────────
Image.open(os.path.join(HOME, 'icon-v3-full.png')).resize((144, 144), Image.LANCZOS)\
    .save(os.path.join(HOME, 'icon-v3-144.png'))
Image.open(os.path.join(HOME, 'icon-v3-full.png')).resize((48, 48), Image.LANCZOS)\
    .save(os.path.join(HOME, 'icon-v3-48.png'))

print("previews:")
print("  ~/icon-v3-full.png      (1024px, full detail)")
print("  ~/icon-v3-adaptive.png  (what Android shows, circular mask)")
print("  ~/icon-v3-144.png       (home-screen size)")
print("  ~/icon-v3-48.png        (notification size)")
print()
print(f"colors:  bg=#{BG[0]:02X}{BG[1]:02X}{BG[2]:02X}  "
      f"triangle=#{TURQ[0]:02X}{TURQ[1]:02X}{TURQ[2]:02X}  "
      f"clef=bg  R={R}px")
PY
