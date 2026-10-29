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
SAFE = 33.0
S_MAIN = 74.0        # ← حداکثر اندازه‌ی امن

def compute(s, cx=54.0, cy=54.0):
    r  = s / (4 * math.sqrt(3))       # fillet radius = incircle / 2
    dt = r * math.sqrt(3)             # tangent distance from vertex
    xl = cx - s * math.sqrt(3) / 6    # left edge
    xa = cx + s * math.sqrt(3) / 3    # apex
    yt, yb = cy - s/2, cy + s/2

    V = {'TL': (xl, yt), 'BL': (xl, yb), 'R': (xa, cy)}
    def unit(v):
        m = math.hypot(*v)
        return (v[0]/m, v[1]/m)
    def center(vertex):
        u = unit((cx - vertex[0], cy - vertex[1]))
        return (vertex[0] + 2*r*u[0], vertex[1] + 2*r*u[1])

    sqrt3_2 = math.sqrt(3)/2
    return {
        's': s, 'r': r,
        # tangent points (order = clockwise path)
        'TL_s': (xl + dt*sqrt3_2, yt + dt*0.5),
        'R_t':  (xa - dt*sqrt3_2, cy - dt*0.5),
        'R_b':  (xa - dt*sqrt3_2, cy + dt*0.5),
        'BL_s': (xl + dt*sqrt3_2, yb - dt*0.5),
        'BL_v': (xl,             yb - dt),
        'TL_v': (xl,             yt + dt),
        # arc centers
        'c_TL': center(V['TL']),
        'c_R':  center(V['R']),
        'c_BL': center(V['BL']),
    }

def pathdata(d):
    r = d['r']; f = lambda p: f"{p[0]:.2f},{p[1]:.2f}"
    return (f"M{f(d['TL_s'])} "
            f"L{f(d['R_t'])} "
            f"A{r:.2f},{r:.2f} 0 0 1 {f(d['R_b'])} "
            f"L{f(d['BL_s'])} "
            f"A{r:.2f},{r:.2f} 0 0 1 {f(d['BL_v'])} "
            f"L{f(d['TL_v'])} "
            f"A{r:.2f},{r:.2f} 0 0 1 {f(d['TL_s'])} Z")

def arc(c, r, a0_deg, a1_deg, n=40):
    a0, a1 = math.radians(a0_deg), math.radians(a1_deg)
    while a1 <= a0: a1 += 2*math.pi
    return [(c[0]+r*math.cos(a0+(a1-a0)*i/n),
             c[1]+r*math.sin(a0+(a1-a0)*i/n)) for i in range(n+1)]

def render(d, size, safe_overlay=False):
    sc = size / 108.0
    P = lambda p: (p[0]*sc, p[1]*sc)
    r = d['r']
    pts  = [P(p) for p in arc(d['c_TL'], r, 180, -60)]        # TL arc
    pts += [P(d['R_t'])]
    pts += [P(p) for p in arc(d['c_R'],  r, -60,  60)]        # apex arc
    pts += [P(d['BL_s'])]
    pts += [P(p) for p in arc(d['c_BL'], r,  60, 180)]        # BL arc
    img = Image.new('RGBA', (size, size), BG + (255,))
    dr  = ImageDraw.Draw(img)
    dr.polygon(pts, fill=FG + (255,))
    if safe_overlay:
        dr.ellipse([(54-SAFE)*sc, (54-SAFE)*sc,
                    (54+SAFE)*sc, (54+SAFE)*sc],
                   outline=(255, 0, 0, 255), width=3)
    return img

d = compute(S_MAIN)
reach = d['s']/math.sqrt(3) - d['r']

print("=== computed values (108dp space) ===")
print(f"side s          = {d['s']:.2f}")
print(f"fillet r        = {d['r']:.3f}   (= s/(4√3) — روش شما)")
print(f"incircle radius = {d['s']/(2*math.sqrt(3)):.2f}   (= 2r)")
print(f"circumradius    = {d['s']/math.sqrt(3):.2f}")
print(f"rounded reach   = {reach:.2f}    (safe = 33, margin = {SAFE-reach:.2f})")
print(f"safely inside?  = {reach <= SAFE}")
print(f"largest safe s  = {SAFE*4*math.sqrt(3)/3:.2f}")
print()
print("pathData:")
print(f"  {pathdata(d)}")
print()

for sz, lbl in ((512, '512'), (144, '144'), (48, '48')):
    render(d, sz).save(os.path.join(HOME, f'icon-geo-{lbl}.png'))
render(d, 512, safe_overlay=True).save(os.path.join(HOME, 'icon-geo-safecheck.png'))

# adaptive composite (round mask)
img = render(d, 512)
mask = Image.new('L', (512, 512), 0)
ImageDraw.Draw(mask).ellipse([0, 0, 512, 512], fill=255)
img.putalpha(mask)
img.save(os.path.join(HOME, 'icon-geo-adaptive.png'))

print("previews:")
print("  ~/icon-geo-512.png")
print("  ~/icon-geo-144.png")
print("  ~/icon-geo-48.png")
print("  ~/icon-geo-adaptive.png")
print("  ~/icon-geo-safecheck.png  (red circle = 33dp safe-zone)")
PY
