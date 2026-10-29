#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw

HOME = os.path.expanduser('~')
BG   = (0, 188, 212)
FG   = (253, 245, 242)
SAFE_ZONE_R = 33  # dp

# ── geometry (single source of truth) ──────────────────────
def compute(s, r, cx=54.0, cy=54.0, mode='bbox'):
    half_s3   = s * math.sqrt(3) / 2
    tangent_d = r * math.sqrt(3)
    apex_off  = 1.5 * r
    arc_half  = r * math.sqrt(3) / 2

    if mode == 'bbox':
        x_left = (2*cx + r - half_s3) / 2
    elif mode == 'centroid':
        x_left = cx - s * math.sqrt(3) / 6
    else:
        raise ValueError(mode)

    x_apex = x_left + half_s3
    y_tl   = cy - s/2
    y_bl   = cy + s/2

    return {
        's': s, 'r': r, 'mode': mode,
        'x_left':    x_left,
        'x_apex':    x_apex,
        'rightmost': x_apex - r,
        'bbox_center': (x_left + (x_apex - r)) / 2,
        'centroid_x':  x_left + s * math.sqrt(3) / 6,
        # tangent points
        'TL_vert':  (x_left, y_tl + tangent_d),
        'TL_slant': (x_left + apex_off, y_tl + arc_half),
        'BL_vert':  (x_left, y_bl - tangent_d),
        'BL_slant': (x_left + apex_off, y_bl - arc_half),
        'R_top':    (x_apex - apex_off, cy - arc_half),
        'R_bot':    (x_apex - apex_off, cy + arc_half),
        # arc centers
        'TL_c': (x_left + r, y_tl + tangent_d),
        'BL_c': (x_left + r, y_bl - tangent_d),
        'R_c':  (x_apex - 2*r, cy),
    }

def pathdata(d):
    r = d['r']
    return (
        f"M{d['TL_slant'][0]:.2f},{d['TL_slant'][1]:.2f} "
        f"L{d['R_top'][0]:.2f},{d['R_top'][1]:.2f} "
        f"A{r},{r} 0 0 1 {d['R_bot'][0]:.2f},{d['R_bot'][1]:.2f} "
        f"L{d['BL_slant'][0]:.2f},{d['BL_slant'][1]:.2f} "
        f"A{r},{r} 0 0 1 {d['BL_vert'][0]:.2f},{d['BL_vert'][1]:.2f} "
        f"L{d['TL_vert'][0]:.2f},{d['TL_vert'][1]:.2f} "
        f"A{r},{r} 0 0 1 {d['TL_slant'][0]:.2f},{d['TL_slant'][1]:.2f} Z"
    )

# ── render: sample the SAME arcs the pathData will use ─────
def sample_arc(cx, cy, r, a0, a1, steps=48):
    while a1 <= a0: a1 += 2*math.pi
    pts = []
    for i in range(steps + 1):
        a = a0 + (a1 - a0) * i / steps
        pts.append((cx + r*math.cos(a), cy + r*math.sin(a)))
    return pts

def render(d, size):
    scale = size / 108.0
    def P(p): return (p[0]*scale, p[1]*scale)
    r = d['r']

    a0_tl = math.atan2(d['TL_vert'][1]  - d['TL_c'][1], d['TL_vert'][0]  - d['TL_c'][0])
    a1_tl = math.atan2(d['TL_slant'][1] - d['TL_c'][1], d['TL_slant'][0] - d['TL_c'][0])
    a0_r  = math.atan2(d['R_top'][1] - d['R_c'][1], d['R_top'][0] - d['R_c'][0])
    a1_r  = math.atan2(d['R_bot'][1] - d['R_c'][1], d['R_bot'][0] - d['R_c'][0])
    a0_bl = math.atan2(d['BL_slant'][1] - d['BL_c'][1], d['BL_slant'][0] - d['BL_c'][0])
    a1_bl = math.atan2(d['BL_vert'][1]  - d['BL_c'][1], d['BL_vert'][0]  - d['BL_c'][0])

    pts = []
    pts += [P(p) for p in sample_arc(*d['TL_c'], r, a0_tl, a1_tl)]
    pts.append(P(d['R_top']))
    pts += [P(p) for p in sample_arc(*d['R_c'], r, a0_r, a1_r)]
    pts.append(P(d['BL_slant']))
    pts += [P(p) for p in sample_arc(*d['BL_c'], r, a0_bl, a1_bl)]
    pts.append(P(d['TL_vert']))

    img = Image.new('RGBA', (size, size), BG + (255,))
    ImageDraw.Draw(img).polygon(pts, fill=FG + (255,))
    return img

# ── parameters ────────────────────────────────────────────
S_MAIN = 64
R_MAIN = 8

variants = {
    'bbox':     compute(S_MAIN, R_MAIN, mode='bbox'),
    'centroid': compute(S_MAIN, R_MAIN, mode='centroid'),
}

for name, d in variants.items():
    print(f"=== {name} (s={S_MAIN}, r={R_MAIN}) ===")
    print(f"  left edge:        x = {d['x_left']:.2f}")
    print(f"  rightmost (arc):  x = {d['rightmost']:.2f}")
    print(f"  bbox center:         {d['bbox_center']:.2f}")
    print(f"  centroid x:          {d['centroid_x']:.2f}")
    print(f"  distance LEFT of 54:  {54 - d['x_left']:.2f}")
    print(f"  distance RIGHT of 54: {d['rightmost'] - 54:.2f}")
    print(f"  pathData:")
    print(f"    {pathdata(d)}")
    print()

    img = render(d, 512)
    for sz, lbl in ((512, '512'), (144, '144'), (48, '48')):
        img.resize((sz, sz), Image.LANCZOS).save(
            os.path.join(HOME, f'icon-v4-{name}-{lbl}.png'))

    # safe-zone check overlay
    check = img.copy()
    draw = ImageDraw.Draw(check)
    scale = 512 / 108.0
    draw.ellipse(
        [(54 - SAFE_ZONE_R) * scale, (54 - SAFE_ZONE_R) * scale,
         (54 + SAFE_ZONE_R) * scale, (54 + SAFE_ZONE_R) * scale],
        outline=(255, 0, 0, 255), width=3)
    check.save(os.path.join(HOME, f'icon-v4-{name}-safecheck.png'))

print("previews:")
for name in variants:
    print(f"  ~/icon-v4-{name}-512.png")
    print(f"  ~/icon-v4-{name}-144.png")
    print(f"  ~/icon-v4-{name}-48.png")
    print(f"  ~/icon-v4-{name}-safecheck.png   (red circle = 33dp safe-zone edge)")
PY
