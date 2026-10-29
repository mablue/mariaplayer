#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
python3 -c "import PIL" 2>/dev/null || { echo "FATAL: pillow install failed"; exit 1; }

# Keep the original PNG out of the way
[ -f ~/mpi-original.png ] || cp ~/mpi.png ~/mpi-original.png

# Font with 𝄞 (U+1D11E MUSICAL SYMBOL G CLEF)
FONT=~/NotoSansSymbols2-Regular.ttf
if [ ! -f "$FONT" ]; then
  for url in \
    "https://raw.githubusercontent.com/notofonts/noto-fonts/main/hinted/ttf/NotoSansSymbols2/NotoSansSymbols2-Regular.ttf" \
    "https://github.com/google/fonts/raw/main/ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf"; do
    if curl -fL -o "$FONT" "$url" 2>/dev/null; then break; fi
  done
fi
[ -s "$FONT" ] || { echo "FATAL: font download failed"; exit 1; }
echo "font: $FONT ($(stat -c%s "$FONT") bytes)"

python3 - <<'PY'
import os
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, 'NotoSansSymbols2-Regular.ttf')
SIZE = 1024

BG   = (168, 104, 123)     # #A8687B  dusty rose (Maryam)
TRI  = (245, 239, 230)     # #F5EFE6  warm cream (petal)
CLEF = BG                  # cutout: bg shows through triangle

GLYPH = '\U0001D11E'       # 𝄞 treble clef

def triangle_points(size, scale=1.0):
    m = int(size * 0.06 * scale)
    s = int(size * scale)
    off = (size - s) // 2
    pts = [(s // 2, m), (m, s - m), (s - m, s - m)]
    return [(x + off, y + off) for x, y in pts]

def draw_clef(draw, canvas, scale=1.0, color=(255, 255, 255, 255), font_size_ratio=0.55, cy_ratio=0.60):
    font = ImageFont.truetype(FONT, int(canvas * font_size_ratio * scale))
    bb = draw.textbbox((0, 0), GLYPH, font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    cx = canvas // 2
    cy = int(canvas * cy_ratio)
    draw.text((cx - tw // 2 - bb[0], cy - th // 2 - bb[1]), GLYPH, font=font, fill=color)

def full_icon(bg_rgba, scale=1.0):
    img = Image.new('RGBA', (SIZE, SIZE), bg_rgba)
    d = ImageDraw.Draw(img)
    d.polygon(triangle_points(SIZE, scale), fill=TRI + (255,))
    draw_clef(d, SIZE, scale=scale, color=CLEF + (255,))
    return img

# 1) Full icon (legacy launcher)
full = full_icon(BG + (255,))
full.save(os.path.join(HOME, 'mpi.png'))
print('wrote ~/mpi.png (full)')

# 2) Adaptive foreground: transparent bg, content in 61% safe zone
fg = full_icon((0, 0, 0, 0), scale=0.61)
fg.save(os.path.join(HOME, 'mpi-fg.png'))
print('wrote ~/mpi-fg.png (foreground)')

# 3) Monochrome: triangle outline + clef filled, white on transparent
mono = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(mono)
pts = triangle_points(SIZE)
d.line(pts + [pts[0]], fill=(255, 255, 255, 255),
       width=int(SIZE * 0.045), joint='curve')
draw_clef(d, SIZE, color=(255, 255, 255, 255),
          font_size_ratio=0.50, cy_ratio=0.60)
mono.save(os.path.join(HOME, 'mpi-mono.png'))
print('wrote ~/mpi-mono.png (monochrome)')

# 4) Notification (24dp) — just the clef, clean at small size
N = 96
notif = Image.new('RGBA', (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(notif)
font = ImageFont.truetype(FONT, 88)
bb = d.textbbox((0, 0), GLYPH, font=font)
tw, th = bb[2] - bb[0], bb[3] - bb[1]
d.text(((N - tw) // 2 - bb[0], (N - th) // 2 - bb[1]),
       GLYPH, font=font, fill=(255, 255, 255, 255))
notif.save(os.path.join(HOME, 'mpi-notif.png'))
print('wrote ~/mpi-notif.png (notification)')
PY

# Write all Android resources from the generated sources
python3 - <<'PY'
import os
from PIL import Image

HOME = os.path.expanduser('~')
BASE = os.getcwd()
full  = Image.open(os.path.join(HOME, 'mpi.png')).convert('RGBA')
fg    = Image.open(os.path.join(HOME, 'mpi-fg.png')).convert('RGBA')
mono  = Image.open(os.path.join(HOME, 'mpi-mono.png')).convert('RGBA')
notif = Image.open(os.path.join(HOME, 'mpi-notif.png')).convert('RGBA')

legacy = [(48,'mdpi'), (72,'hdpi'), (96,'xhdpi'), (144,'xxhdpi'), (192,'xxxhdpi')]
for size, d in legacy:
    outdir = os.path.join(BASE, f'app/src/main/res/mipmap-{d}')
    os.makedirs(outdir, exist_ok=True)
    img = full.resize((size, size), Image.LANCZOS)
    img.save(os.path.join(outdir, 'ic_launcher.png'))
    img.save(os.path.join(outdir, 'ic_launcher_round.png'))

adaptive = [(108,'mdpi'), (162,'hdpi'), (216,'xhdpi'), (324,'xxhdpi'), (432,'xxxhdpi')]
for size, d in adaptive:
    outdir = os.path.join(BASE, f'app/src/main/res/mipmap-{d}')
    os.makedirs(outdir, exist_ok=True)
    fg.resize((size, size), Image.LANCZOS).save(
        os.path.join(outdir, 'ic_launcher_foreground.png'))

drawdir = os.path.join(BASE, 'app/src/main/res/drawable')
os.makedirs(drawdir, exist_ok=True)
mono.resize((432, 432), Image.LANCZOS).save(
    os.path.join(drawdir, 'ic_launcher_monochrome.png'))
notif.save(os.path.join(drawdir, 'ic_notification.png'))
print('android resources written')
PY

# Background color + adaptive icon XML
mkdir -p app/src/main/res/values
cat > app/src/main/res/values/colors.xml << 'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FFA8687B</color>
</resources>
XML

mkdir -p app/src/main/res/mipmap-anydpi-v26
cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml << 'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
XML
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
   app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

git add -A
git commit -qm "feat(icon): programmatic Maryam-rose icon (triangle + treble clef)"

for attempt in 1 2 3; do
  if git push origin main; then break; fi
  echo "push attempt $attempt failed — pull --rebase and retry"
  git pull --rebase origin main || true
  sleep 3
done
echo "pushed"
