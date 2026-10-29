#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

# ── Foreground: bbox-centered rounded triangle ──
cat > app/src/main/res/drawable/ic_launcher_foreground.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Equilateral triangle (side 40, right vertex 71.32),
         corner radius 6, tangent dist = r*sqrt(3) = 10.39
         Horizontal bbox centered at x=54: left edge 36.68, right vertex 71.32 -->
    <path
        android:fillColor="#FDF5F2"
        android:pathData="M45.7,39.2
            L62.3,48.8
            A6,6 0 0 1 62.3,59.2
            L45.7,68.8
            A6,6 0 0 1 36.7,63.6
            L36.7,44.4
            A6,6 0 0 1 45.7,39.2 Z" />
</vector>
XEOF

# ── Monochrome: same shape, solid white ──
cat > app/src/main/res/drawable/ic_launcher_monochrome.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M45.7,39.2
            L62.3,48.8
            A6,6 0 0 1 62.3,59.2
            L45.7,68.8
            A6,6 0 0 1 36.7,63.6
            L36.7,44.4
            A6,6 0 0 1 45.7,39.2 Z" />
</vector>
XEOF

# ── Notification: same shape at 24dp (scale 24/108 = 0.2222) ──
cat > app/src/main/res/drawable/ic_notification.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M10.15,8.71
            L13.85,10.85
            A1.33,1.33 0 0 1 13.85,13.15
            L10.15,15.29
            A1.33,1.33 0 0 1 8.15,14.14
            L8.15,9.86
            A1.33,1.33 0 0 1 10.15,8.71 Z" />
</vector>
XEOF

# ── Preview: exact trace of new pathData ──
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

python3 - << 'PY'
import os, math
from PIL import Image, ImageDraw

HOME = os.path.expanduser('~')
S = 512
scale = S / 108
BG = (0, 188, 212)
FG = (253, 245, 242)

def P(x, y): return (x * scale, y * scale)

def arc(cx, cy, r, a0, a1, steps=32):
    pts = []
    diff = a1 - a0
    while diff >  math.pi: diff -= 2*math.pi
    while diff < -math.pi: diff += 2*math.pi
    for i in range(steps + 1):
        a = a0 + diff * (i/steps)
        pts.append((cx + r*math.cos(a), cy + r*math.sin(a)))
    return pts

# Match pathData exactly.
# Arc centers (y-down): A(42.68, 44.392), B(42.68, 63.608), C(59.32, 54). r=6.
pts = []
pts.append(P(45.7, 39.2))                                    # start
pts.append(P(62.3, 48.8))                                    # line to C start
pts += [P(*p) for p in arc(59.32, 54, 6, -math.pi/3, math.pi/3)]   # C arc -60°..+60°
pts.append(P(62.3, 59.2))                                    # (arc already ends here)
pts.append(P(45.7, 68.8))                                    # line to B start
pts += [P(*p) for p in arc(42.68, 63.608, 6, math.pi/3, math.pi)]  # B arc +60°..180°
pts.append(P(36.7, 63.6))
pts.append(P(36.7, 44.4))                                    # line to A start
pts += [P(*p) for p in arc(42.68, 44.392, 6, math.pi, math.pi + 2*math.pi/3)]  # A arc 180°..300°
pts.append(P(45.7, 39.2))

# dedup consecutive duplicates
clean = []
for p in pts:
    if not clean or abs(clean[-1][0]-p[0])>0.1 or abs(clean[-1][1]-p[1])>0.1:
        clean.append(p)
pts = clean

img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)
d.polygon(pts, fill=FG + (255,))

# bbox center guide (thin) — bbox center should be at (54,54)
bc_x = (36.7 + 71.32) / 2
bc_y = (34 + 74) / 2
print(f"bbox center: ({bc_x:.2f}, {bc_y:.2f})  expected: (54.00, 54.00)")
print(f"left edge x: 36.70  right vertex x: 71.32  -> center: {(36.7+71.32)/2:.2f}")
print(f"distance to left:  {54 - 36.7:.2f}")
print(f"distance to right: {71.32 - 54:.2f}")

for sz, lbl in ((512, '512'), (144, '144'), (48, '48')):
    img.resize((sz, sz), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-tri-centered-{lbl}.png'))

# extra: composited adaptive preview (circular mask + turquoise)
comp = img.resize((S, S))
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'icon-tri-centered-adaptive.png'))

print("previews written:")
print("  ~/icon-tri-centered-512.png")
print("  ~/icon-tri-centered-144.png")
print("  ~/icon-tri-centered-48.png")
print("  ~/icon-tri-centered-adaptive.png")
PY

git add -A
git commit -qm "fix(icon): bbox-center triangle horizontally (left edge and right vertex equidistant)"

for attempt in 1 2 3 4 5; do
  if git push origin main 2>/dev/null; then
    echo "pushed on attempt $attempt"
    exit 0
  fi
  echo "push retry $attempt"
  git pull --rebase origin main 2>/dev/null || true
  sleep 3
done
echo "push failed after 5 attempts"
exit 1
