#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw

HOME = os.path.expanduser('~')
S = 512
scale = S / 108
BG = (0, 188, 212)
FG = (253, 245, 242)

# ── هندسه (با اعداد دقیق) ──────────────────────────────────
s = 55
r = 6
half_s3 = s * math.sqrt(3) / 2   # 47.6314
tangent_d = r / math.tan(math.radians(30))   # 10.3923

# نقاط پس از شیفت (در دستگاه 108dp)
A_on_AB = (33.18, 36.89)
B_on_AB = (33.18, 71.11)
B_on_BC = (42.18, 76.30)
C_on_BC = (71.82, 59.20)
C_on_CA = (71.82, 48.80)
A_on_AC = (42.18, 31.70)

# مراکز کمان‌ها
arcB_c = (39.46, 71.27)
arcC_c = (68.82, 54.00)
arcA_c = (39.46, 36.73)

def P(p): return (p[0]*scale, p[1]*scale)

def sample_arc(cx, cy, radius, a0_deg, a1_deg, steps=32):
    """Draw arc sampling in SVG coords (y-down); decreasing angle = sweep 0"""
    a0 = math.radians(a0_deg)
    a1 = math.radians(a1_deg)
    diff = a1 - a0
    while diff >  math.pi: diff -= 2*math.pi
    while diff < -math.pi: diff += 2*math.pi
    pts = []
    for i in range(steps + 1):
        a = a0 + diff * i / steps
        pts.append((cx + radius*math.cos(a), cy + radius*math.sin(a)))
    return pts

# نقاط مسیر را دقیقاً مطابق pathData تولید می‌کنیم
# زاویه‌ها را از روی مختصات بازسازی می‌کنیم
def ang(cx, cy, p):
    return math.degrees(math.atan2(p[1]-cy, p[0]-cx))

pts = []
pts.append(P(A_on_AB))
pts.append(P(B_on_AB))
a_b = ang(*arcB_c, B_on_AB); b_b = ang(*arcB_c, B_on_BC)
pts += [P(p) for p in sample_arc(*arcB_c, r, a_b, b_b)]
pts.append(P(C_on_BC))
a_c = ang(*arcC_c, C_on_BC); b_c = ang(*arcC_c, C_on_CA)
pts += [P(p) for p in sample_arc(*arcC_c, r, a_c, b_c)]
pts.append(P(A_on_AC))
a_a = ang(*arcA_c, A_on_AC); b_a = ang(*arcA_c, A_on_AB)
pts += [P(p) for p in sample_arc(*arcA_c, r, a_a, b_a)]

img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)
d.polygon(pts, fill=FG + (255,))

# گزارش‌های تأیید
print("=== verification (in 108dp coords) ===")
print(f"left edge   x = {A_on_AB[0]:.2f}")
print(f"right vertex x = {arcC_c[0] + r:.2f}")
print(f"left distance  to center: {54 - A_on_AB[0]:.2f}")
print(f"right distance to center: {(arcC_c[0]+r) - 54:.2f}")
print(f"bbox center x = {(A_on_AB[0] + arcC_c[0] + r)/2:.2f}  (target 54)")
print(f"side length = {s}  circumradius = {s/math.sqrt(3):.2f}")
print(f"triangle scale vs P1: {s/52*100:.1f}%")
print()

for sz, lbl in ((512, '512'), (144, '144'), (48, '48')):
    img.resize((sz, sz), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-final-{lbl}.png'))

# adaptive composite (circular mask + bg + fg)
comp = img.copy()
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-final-adaptive.png'))

# also a side-by-side vs P1
def load_p1_bbox():
    pass
print("previews:")
print("  ~/icon-final-512.png")
print("  ~/icon-final-144.png")
print("  ~/icon-final-48.png")
print("  ~/icon-final-adaptive.png  (what Android shows)")
PY
