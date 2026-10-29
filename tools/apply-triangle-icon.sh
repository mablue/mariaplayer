#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

echo "=== cleanup all previous icon files ==="
find app/src/main/res -type f \( \
  -name 'ic_launcher*' -o \
  -name 'ic_notification*' -o \
  -name 'colors.xml' \) -delete 2>/dev/null || true
rm -rf app/src/main/res/mipmap-* 2>/dev/null || true

mkdir -p app/src/main/res/values \
         app/src/main/res/drawable \
         app/src/main/res/mipmap-anydpi-v26

# ──────────────────────────────────────────────────────────
# Background color (turquoise)
# ──────────────────────────────────────────────────────────
cat > app/src/main/res/values/ic_launcher_background.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#00BCD4</color>
</resources>
XEOF

# ──────────────────────────────────────────────────────────
# Foreground: white solid rounded equilateral triangle
# pathData uses A (arc) commands — no circles, only real rounded corners
# Geometry: center(54,54), circumradius R=30, corner radius r=6
#   A top-left  (39,28.02)
#   B bot-left  (39,79.98)
#   C right     (84,54.00)
# ──────────────────────────────────────────────────────────
cat > app/src/main/res/drawable/ic_launcher_foreground.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FDF5F2"
        android:pathData="M39,38.41 L39,69.59 A6,6 0 0 0 48,74.78 L75,59.20 A6,6 0 0 0 75,48.80 L48,33.22 A6,6 0 0 0 39,38.41 Z" />
</vector>
XEOF

# ──────────────────────────────────────────────────────────
# Monochrome: same triangle, pure white (for Themed Icons API 33+)
# ──────────────────────────────────────────────────────────
cat > app/src/main/res/drawable/ic_launcher_monochrome.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M39,38.41 L39,69.59 A6,6 0 0 0 48,74.78 L75,59.20 A6,6 0 0 0 75,48.80 L48,33.22 A6,6 0 0 0 39,38.41 Z" />
</vector>
XEOF

# ──────────────────────────────────────────────────────────
# Notification icon (24dp, same triangle scaled by 24/108)
# ──────────────────────────────────────────────────────────
cat > app/src/main/res/drawable/ic_notification.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M8.67,8.53 L8.67,15.46 A1.33,1.33 0 0 0 10.67,16.62 L16.67,13.16 A1.33,1.33 0 0 0 16.67,10.84 L10.67,7.38 A1.33,1.33 0 0 0 8.67,8.53 Z" />
</vector>
XEOF

# ──────────────────────────────────────────────────────────
# Adaptive icon (ic_launcher + ic_launcher_round)
# ──────────────────────────────────────────────────────────
for name in ic_launcher ic_launcher_round; do
cat > "app/src/main/res/mipmap-anydpi-v26/${name}.xml" << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
XEOF
done

# ──────────────────────────────────────────────────────────
# Preview PNG by tracing the SAME pathData (verification)
# ──────────────────────────────────────────────────────────
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 - <<'PY'
import os, math
from PIL import Image, ImageDraw

HOME = os.path.expanduser('~')
S = 512
BG = (0, 188, 212)
FG = (253, 245, 242)
IMG_FG_108 = "#FDF5F2"
IMG_BG_108 = "#00BCD4"
scale = S / 108

# Trace pathData exactly:
#   M39,38.41 L39,69.59 A6,6 0 0 0 48,74.78 L75,59.20 A6,6 0 0 0 75,48.80 L48,33.22 A6,6 0 0 0 39,38.41 Z

def P(x, y): return (x * scale, y * scale)

def sample_arc(cx, cy, r, a0, a1, steps=24):
    pts = []
    diff = a1 - a0
    while diff >  math.pi: diff -= 2*math.pi
    while diff < -math.pi: diff += 2*math.pi
    for i in range(steps + 1):
        a = a0 + diff * (i / steps)
        pts.append((cx + r*math.cos(a), cy + r*math.sin(a)))
    return pts

# corner centers in 108-space
# corner B: tangent (39,69.59) and (48,74.78), r=6
#   center = (45, 69.59), start angle 180°, end angle 60°
# corner C: tangent (75,59.20) and (75,48.80), r=6
#   center = (72, 54), start angle 60°, end angle 300°
# corner A: tangent (48,33.22) and (39,38.41), r=6
#   center = (45, 38.41), start angle 300°, end angle 180°

pts = []
pts.append(P(39, 38.41))                       # start
pts.append(P(39, 69.59))                       # line to B tangent
pts += [P(*p) for p in sample_arc(45, 69.59, 6, math.pi, math.radians(60))]     # arc B
pts.append(P(75, 59.20))                       # line to C tangent
pts += [P(*p) for p in sample_arc(72, 54,    6, math.radians(60), math.radians(-60))]  # arc C
pts.append(P(48, 33.22))                       # line to A tangent
pts += [P(*p) for p in sample_arc(45, 38.41, 6, math.radians(-60), math.pi)]    # arc A

img = Image.new('RGBA', (S, S), BG + (255,))
d = ImageDraw.Draw(img)
d.polygon(pts, fill=FG + (255,))

# also draw a faint check circle at r=33 (safe zone edge) on a copy
check = img.copy()
d2 = ImageDraw.Draw(check)
d2.ellipse([(54-33)*scale, (54-33)*scale, (54+33)*scale, (54+33)*scale],
           outline=(255, 0, 0, 255), width=4)

img.save(os.path.join(HOME, 'icon-tri-preview-512.png'))
check.save(os.path.join(HOME, 'icon-tri-preview-safecheck.png'))

for sz, lbl in ((144, '144'), (48, '48')):
    img.resize((sz, sz), Image.LANCZOS).save(
        os.path.join(HOME, f'icon-tri-preview-{lbl}.png'))

print("previews:")
print("  ~/icon-tri-preview-512.png")
print("  ~/icon-tri-preview-144.png")
print("  ~/icon-tri-preview-48.png")
print("  ~/icon-tri-preview-safecheck.png  (red circle = 66dp safe zone)")
PY

# ──────────────────────────────────────────────────────────
# Commit + push (with retry for TLS issues)
# ──────────────────────────────────────────────────────────
git add -A
git commit -qm "feat(icon): rounded equilateral triangle (vector, A-arcs, no circles) + turquoise/white palette"

for attempt in 1 2 3 4 5; do
  if git push origin main 2>/dev/null; then
    echo "pushed on attempt $attempt"
    exit 0
  fi
  echo "push attempt $attempt failed — pull --rebase and retry"
  git pull --rebase origin main 2>/dev/null || true
  sleep 3
done

echo "FATAL: 5 push attempts failed"
exit 1
