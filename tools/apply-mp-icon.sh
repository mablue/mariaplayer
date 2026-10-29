#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

[ -f ~/mp.png ] || { echo "FATAL: ~/mp.png not found"; exit 1; }

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

BK=".bak/mp-icon-$(date +%s)"
mkdir -p "$BK"
cp -r app/src/main/res "$BK/" 2>/dev/null || true

echo "=== source ==="
python3 -c "
from PIL import Image
img = Image.open('$HOME/mp.png')
print(f'  size:   {img.size}')
print(f'  mode:   {img.mode}')
print(f'  format: {img.format}')
"

# ── 1. Clean all previous icon files ──────────────────────────
rm -rf app/src/main/res/mipmap-mdpi \
       app/src/main/res/mipmap-hdpi \
       app/src/main/res/mipmap-xhdpi \
       app/src/main/res/mipmap-xxhdpi \
       app/src/main/res/mipmap-xxxhdpi
rm -f  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
       app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml \
       app/src/main/res/drawable/ic_launcher_foreground.xml \
       app/src/main/res/drawable/ic_launcher_monochrome.xml \
       app/src/main/res/drawable/ic_launcher_monochrome.png \
       app/src/main/res/drawable/ic_notification.png \
       app/src/main/res/values/ic_launcher_background.xml \
       app/src/main/res/values/colors.xml

mkdir -p app/src/main/res/mipmap-anydpi-v26 \
         app/src/main/res/drawable \
         app/src/main/res/values

# ── 2. Generate mipmap layers from mp.png ─────────────────────
python3 - <<'PY'
import os
from PIL import Image

HOME = os.path.expanduser('~')
BASE = os.getcwd()
src = Image.open(os.path.join(HOME, 'mp.png')).convert('RGBA')

def squared(img, size):
    return img.resize((size, size), Image.LANCZOS)

def centered_fg(img, canvas_size, content_ratio):
    """Foreground for adaptive icon: content fits inside safe zone."""
    inner = int(canvas_size * content_ratio)
    scaled = img.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new('RGBA', (canvas_size, canvas_size), (0, 0, 0, 0))
    off = (canvas_size - inner) // 2
    canvas.alpha_composite(scaled, (off, off))
    return canvas

# Legacy mipmaps (minSdk=26 → optional, but harmless + safe for launchers)
legacy = [(48, 'mdpi'), (72, 'hdpi'), (96, 'xhdpi'),
          (144, 'xxhdpi'), (192, 'xxxhdpi')]
for size, d in legacy:
    outdir = os.path.join(BASE, f'app/src/main/res/mipmap-{d}')
    os.makedirs(outdir, exist_ok=True)
    img = squared(src, size)
    img.save(os.path.join(outdir, 'ic_launcher.png'))
    img.save(os.path.join(outdir, 'ic_launcher_round.png'))

# Adaptive foreground: content at 66/108 = 61% of canvas
adaptive = [(108, 'mdpi'), (162, 'hdpi'), (216, 'xhdpi'),
            (324, 'xxhdpi'), (432, 'xxxhdpi')]
for size, d in adaptive:
    outdir = os.path.join(BASE, f'app/src/main/res/mipmap-{d}')
    os.makedirs(outdir, exist_ok=True)
    centered_fg(src, size, 0.61).save(
        os.path.join(outdir, 'ic_launcher_foreground.png'))

# ── 3. Monochrome (white silhouette via luminance) ────────────
def silhouette(img):
    white = Image.new('RGBA', img.size, (255, 255, 255, 255))
    on_white = Image.alpha_composite(white, img).convert('L')
    out = Image.new('RGBA', img.size, (255, 255, 255, 0))
    out.putalpha(on_white)
    return out

mono = silhouette(src).resize((432, 432), Image.LANCZOS)
mono.save(os.path.join(BASE, 'app/src/main/res/drawable/ic_launcher_monochrome.png'))

# ── 4. Notification icon (24dp → 96px) ────────────────────────
notif = silhouette(src).resize((96, 96), Image.LANCZOS)
notif.save(os.path.join(BASE, 'app/src/main/res/drawable/ic_notification.png'))

print("PNG resources written")
PY

# ── 5. Sample bg color (for adaptive icon background layer) ───
BG_HEX=$(python3 - <<'PY'
from PIL import Image
src = Image.open('/data/data/com.termux/files/home/mp.png').convert('RGBA')
white = Image.new('RGBA', src.size, (255, 255, 255, 255))
c = Image.alpha_composite(white, src).convert('RGB').resize((1, 1), Image.LANCZOS)
r, g, b = c.getpixel((0, 0))
print(f'{r:02X}{g:02X}{b:02X}')
PY
)
echo "sampled bg color: #FF${BG_HEX}"

cat > app/src/main/res/values/ic_launcher_background.xml <<XEOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FF${BG_HEX}</color>
</resources>
XEOF

# ── 6. Adaptive icon XML ──────────────────────────────────────
cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
XEOF
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
   app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

# ── 7. Manifest refs ──────────────────────────────────────────
python3 - << 'PY'
import pathlib, re
p = pathlib.Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
if 'android:icon=' not in s:
    s = s.replace(
        'android:label="MariaPlayer"',
        'android:label="MariaPlayer"\n'
        '        android:icon="@mipmap/ic_launcher"\n'
        '        android:roundIcon="@mipmap/ic_launcher_round"',
        1
    )
    p.write_text(s)
    print("manifest refs set")
else:
    print("manifest refs already present")
PY

# ── 8. PlaybackService notification icon (make sure ref survives) ──
python3 - << 'PY'
import pathlib
p = pathlib.Path('app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt')
s = p.read_text()
if 'setSmallIcon(ir.mariaplayer.R.drawable.ic_notification)' not in s:
    print("WARN: PlaybackService doesn't set custom notif icon — you'll get Media3 default")
else:
    print("PlaybackService references ic_notification ✓")
PY

# ── 9. Preview of what Android will show ──────────────────────
python3 - <<'PY'
import os
from PIL import Image, ImageDraw
HOME = os.path.expanduser('~')
BASE = os.getcwd()
fg = Image.open(os.path.join(BASE, 'app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png'))
import re
xml = open(os.path.join(BASE, 'app/src/main/res/values/ic_launcher_background.xml')).read()
hexcol = re.search(r'#FF([0-9A-Fa-f]{6})', xml).group(1)
bg = tuple(int(hexcol[i:i+2], 16) for i in (0, 2, 4)) + (255,)

comp = Image.new('RGBA', fg.size, bg)
comp.alpha_composite(fg)
mask = Image.new('L', fg.size, 0)
ImageDraw.Draw(mask).ellipse([0, 0, fg.size[0], fg.size[1]], fill=255)
comp.putalpha(mask)
comp.save(os.path.join(HOME, 'mp-icon-preview.png'))
print("preview: ~/mp-icon-preview.png")
PY

# ── 10. Commit + push ────────────────────────────────────────
git add -A
git commit -qm "feat(icon): use ~/mp.png for launcher + adaptive + mono + notification"

for attempt in 1 2 3; do
  if git push origin main; then break; fi
  echo "push retry $attempt"
  git pull --rebase origin main || true
  sleep 3
done
echo "pushed"
