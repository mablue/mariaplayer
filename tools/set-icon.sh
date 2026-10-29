#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

[ -f ~/mpi.png ] || { echo "FATAL: ~/mpi.png not found"; exit 1; }

command -v magick >/dev/null || command -v convert >/dev/null || pkg install -y imagemagick
command -v python3 >/dev/null || pkg install -y python
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

CONVERT=$(command -v magick || command -v convert)
echo "=== source ==="
$CONVERT identify ~/mpi.png

mkdir -p .bak/icon
cp app/src/main/AndroidManifest.xml .bak/icon/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt .bak/icon/ 2>/dev/null || true

# 1) Legacy mipmaps
for pair in "48:mdpi" "72:hdpi" "96:xhdpi" "144:xxhdpi" "192:xxxhdpi"; do
  SIZE=${pair%%:*}; D=${pair##*:}
  mkdir -p "app/src/main/res/mipmap-$D"
  $CONVERT ~/mpi.png -resize ${SIZE}x${SIZE} -background none -gravity center \
    -extent ${SIZE}x${SIZE} "app/src/main/res/mipmap-$D/ic_launcher.png"
  cp "app/src/main/res/mipmap-$D/ic_launcher.png" "app/src/main/res/mipmap-$D/ic_launcher_round.png"
done

# 2) Adaptive foreground (108dp canvas, 66dp content)
for pair in "108:mdpi" "162:hdpi" "216:xhdpi" "324:xxhdpi" "432:xxxhdpi"; do
  FULL=${pair%%:*}; D=${pair##*:}
  INNER=$((FULL * 61 / 100))
  mkdir -p "app/src/main/res/mipmap-$D"
  $CONVERT ~/mpi.png -resize ${INNER}x${INNER} -background none -gravity center \
    -extent ${FULL}x${FULL} "app/src/main/res/mipmap-$D/ic_launcher_foreground.png"
done

# 3) Sample background color + write colors.xml
BG_HEX=$(python3 - <<'PY'
from PIL import Image
src = Image.open('/data/data/com.termux/files/home/mpi.png').convert('RGBA')
bg = Image.new('RGBA', src.size, (255, 255, 255, 255))
c = Image.alpha_composite(bg, src).convert('RGB').resize((1,1), Image.LANCZOS)
r, g, b = c.getpixel((0, 0))
print(f'{r:02X}{g:02X}{b:02X}')
PY
)
mkdir -p app/src/main/res/values
cat > app/src/main/res/values/colors.xml <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FF${BG_HEX}</color>
</resources>
XML
echo "background color: #FF${BG_HEX}"

# 4) Adaptive icon XML
mkdir -p app/src/main/res/mipmap-anydpi-v26
cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
XML
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
   app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

# 5) Notification + monochrome icons (white silhouette via PIL)
mkdir -p app/src/main/res/drawable
python3 - <<'PY'
from PIL import Image
src_path = '/data/data/com.termux/files/home/mpi.png'
out_notif = '/data/data/com.termux/files/home/mariaplayer/app/src/main/res/drawable/ic_notification.png'
out_mono  = '/data/data/com.termux/files/home/mariaplayer/app/src/main/res/drawable/ic_launcher_monochrome.png'

img = Image.open(src_path).convert('RGBA')
white = Image.new('RGBA', img.size, (255, 255, 255, 255))
img = Image.alpha_composite(white, img)
gray = img.convert('L')
mask = gray.point(lambda p: 255 - p)

# Notification: 96x96 (24dp at xxxhdpi-ish); Android will scale
mask_n = mask.resize((96, 96), Image.LANCZOS)
out = Image.new('RGBA', (96, 96), (255, 255, 255, 0))
out.putalpha(mask_n)
out.save(out_notif)

# Monochrome for themed icons: original size
out2 = Image.new('RGBA', img.size, (255, 255, 255, 0))
out2.putalpha(mask)
out2.save(out_mono)
print("ic_notification.png + ic_launcher_monochrome.png written")
PY

# 6) Manifest: add icon + roundIcon
python3 - <<'PY'
import pathlib
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
    print("manifest icon set")
else:
    print("manifest already has icon")
PY

# 7) PlaybackService: custom notification small icon
python3 - <<'PY'
import pathlib
p = pathlib.Path('app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt')
s = p.read_text()
if 'setMediaNotificationProvider' not in s:
    if 'androidx.media3.session.DefaultMediaNotificationProvider' not in s:
        s = s.replace(
            'import androidx.media3.session.MediaSessionService',
            'import androidx.media3.session.DefaultMediaNotificationProvider\n'
            'import androidx.media3.session.MediaSessionService',
            1
        )
    s = s.replace(
        '        super.onCreate()\n',
        '        super.onCreate()\n'
        '        setMediaNotificationProvider(\n'
        '            DefaultMediaNotificationProvider(this).apply {\n'
        '                setSmallIcon(ir.mariaplayer.R.drawable.ic_notification)\n'
        '            }\n'
        '        )\n',
        1
    )
    p.write_text(s)
    print("PlaybackService updated for custom notification icon")
else:
    print("PlaybackService already configured")
PY

# 8) Commit + push
git add -A
git commit -qm "feat(icon): use mpi.png for launcher / round / adaptive / monochrome / notification"
if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi
echo "pushed"
