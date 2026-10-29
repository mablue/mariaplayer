#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

python3 - <<'PY'
import os, math
from PIL import Image, ImageDraw

HOME = os.path.expanduser('~')
CANVAS = 1024
BG = (0, 188, 212)        # #00BCD4 turquoise
FG = (253, 245, 242)      # #FDF5F2 Maryam-white

# ── مثلث متساوی الاضلاع رو به راست با گوشه‌های گرد ─────────
def rounded_triangle_points(cx, cy, R, r, n=24):
    # رئوس مثلث متساوی الاضلاع رو به راست
    A = (cx - R/2, cy - R*math.sqrt(3)/2)   # چپ بالا
    B = (cx - R/2, cy + R*math.sqrt(3)/2)   # چپ پایین
    C = (cx + R,   cy)                       # راست
    verts = [A, B, C]

    def unit(vx, vy):
        m = math.hypot(vx, vy) or 1
        return (vx/m, vy/m)

    pts = []
    for i in range(3):
        V = verts[i]
        P = verts[(i-1) % 3]
        N = verts[(i+1) % 3]

        uP = unit(P[0]-V[0], P[1]-V[1])
        uN = unit(N[0]-V[0], N[1]-V[1])

        # فاصله مماس برای زاویه ۶۰ درجه: d = r * sqrt(3)
        d = r * math.sqrt(3)
        start = (V[0] + uP[0]*d, V[1] + uP[1]*d)
        end   = (V[0] + uN[0]*d, V[1] + uN[1]*d)

        # مرکز کمان: روی نیمساز، فاصله 2r از راس (چون sin(30)=0.5)
        bis = unit(uP[0]+uN[0], uP[1]+uN[1])
        arc_c = (V[0] + bis[0]*2*r, V[1] + bis[1]*2*r)

        a0 = math.atan2(start[1]-arc_c[1], start[0]-arc_c[0])
        a1 = math.atan2(end[1]-arc_c[1], end[0]-arc_c[0])
        diff = a1 - a0
        while diff >  math.pi: diff -= 2*math.pi
        while diff < -math.pi: diff += 2*math.pi

        for j in range(n+1):
            t = j/n
            a = a0 + diff*t
            pts.append((arc_c[0] + r*math.cos(a),
                        arc_c[1] + r*math.sin(a)))
    return pts

# ── پارامترها ────────────────────────────────────────────
CX, CY = CANVAS//2, CANVAS//2
R  = 300          # شعاع دایره محیطی (متساوی الاضلاع)
r  = 60           # شعاع گردی گوشه

def preview(name, filled: bool, stroke_w=0):
    img = Image.new('RGBA', (CANVAS, CANVAS), BG + (255,))
    d = ImageDraw.Draw(img)
    pts = rounded_triangle_points(CX, CY, R, r)

    if filled:
        d.polygon(pts, fill=FG + (255,))
    else:
        d.line(pts + [pts[0]], fill=FG + (255,),
               width=stroke_w, joint='curve')

    # ذخیره در سه اندازه
    for sz, label in ((512, '512'), (144, '144'), (48, '48')):
        img.resize((sz, sz), Image.LANCZOS).save(
            os.path.join(HOME, f'icon-{name}-{label}.png'))
    print(f'  icon-{name}-{{512,144,48}}.png')

print("generating previews:")
preview('P1', filled=True)           # مثلث توپر سفید
preview('P2', filled=False, stroke_w=46)  # مثلث خالی سفید

print()
print("هر دو متساوی الاضلاع، گوشه‌های کاملاً گرد، بدون دایره.")
print("R=300، r=60 (زاویه ۶۰°، فاصله مماس = r·√3)")
PY
