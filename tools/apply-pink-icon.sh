#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

FONT="$HOME/.fonts/NotoMusic-Regular.ttf"
[ -s "$FONT" ] || { echo "FATAL: font missing"; exit 1; }
python3 -c "import fontTools" 2>/dev/null || pip install --quiet fonttools

# ── Clean up old icon artifacts (learned: png/xml stem collisions break aapt2) ──
echo "=== [1/6] clean old icon files ==="
find app/src/main/res -type f \( \
  -name 'ic_launcher*' -o \
  -name 'ic_notification*' -o \
  -name 'colors.xml' \) -delete 2>/dev/null || true
rm -rf app/src/main/res/mipmap-* 2>/dev/null || true

mkdir -p app/src/main/res/values \
         app/src/main/res/drawable \
         app/src/main/res/mipmap-anydpi-v26

# ── Extract exact pathData for both shapes ──
echo
echo "=== [2/6] extract pathData from Noto Music ==="
python3 - << 'PY'
import os, math
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform
try:
    from fontTools.pens.boundsPen import BoundsPen as BP
except ImportError:
    from fontTools.pens.boundsPen import ControlBoundsPen as BP

HOME = os.path.expanduser('~')
FONT = os.path.join(HOME, '.fonts', 'NotoMusic-Regular.ttf')
CX, CY = 54.0, 54.0
TRI_S = 68.0
CLEF_H = 40.0

# ── Triangle path (rounded equilateral, right-pointing) ──
def triangle_path(s):
    r = s / (4 * math.sqrt(3))
    dt = r * math.sqrt(3)
    xl = CX - s * math.sqrt(3) / 4 + r / 2
    xa = xl + s * math.sqrt(3) / 2
    yt, yb = CY - s/2, CY + s/2
    sq = math.sqrt(3) / 2
    TL_s = (xl + dt*sq, yt + dt/2)
    R_t  = (xa - dt*sq, CY - dt/2)
    R_b  = (xa - dt*sq, CY + dt/2)
    BL_s = (xl + dt*sq, yb - dt/2)
    BL_v = (xl, yb - dt)
    TL_v = (xl, yt + dt)
    f = lambda p: f"{p[0]:.2f},{p[1]:.2f}"
    return (f"M{f(TL_s)} L{f(R_t)} A{r:.2f},{r:.2f} 0 0 1 {f(R_b)} "
            f"L{f(BL_s)} A{r:.2f},{r:.2f} 0 0 1 {f(BL_v)} "
            f"L{f(TL_v)} A{r:.2f},{r:.2f} 0 0 1 {f(TL_s)} Z")

# ── Clef path ──
def clef_path(h_target):
    f = TTFont(FONT)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[0x1D11E]
    g = gs[name]
    bp = BP(gs); g.draw(bp)
    bx0, by0, bx1, by1 = bp.bounds
    gw, gh = bx1 - bx0, by1 - by0
    sc = h_target / gh
    tx = CX - sc * (bx0 + bx1) / 2
    ty = CY + sc * (by0 + by1) / 2
    pen = SVGPathPen(gs)
    g.draw(TransformPen(pen, Transform(sc, 0, 0, -sc, tx, ty)))
    return pen.getCommands(), gw*sc, gh*sc

tri = triangle_path(TRI_S)
clef, cw, ch = clef_path(CLEF_H)

print(f"triangle: s={TRI_S}")
print(f"clef:     {cw:.2f} x {ch:.2f} dp (centered at 54,54)")
print()
print(f"TRI length:  {len(tri)} chars")
print(f"CLEF length: {len(clef)} chars")

# ── Write Android XML files ──
BASE = os.getcwd()

# 1) Background color
with open(f'{BASE}/app/src/main/res/values/ic_launcher_background.xml', 'w') as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
    fh.write('<resources>\n')
    fh.write('    <color name="ic_launcher_background">#B88A8A</color>\n')
    fh.write('</resources>\n')

# 2) Foreground: triangle cream + clef rose (bg color = cut-out look)
with open(f'{BASE}/app/src/main/res/drawable/ic_launcher_foreground.xml', 'w') as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
    fh.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    fh.write('    android:width="108dp" android:height="108dp"\n')
    fh.write('    android:viewportWidth="108" android:viewportHeight="108">\n')
    fh.write(f'    <!-- Triangle (cream) -->\n')
    fh.write(f'    <path android:fillColor="#F5EFE6" android:pathData="{tri}"/>\n')
    fh.write(f'    <!-- Treble clef (rose = bg → cut-out effect) -->\n')
    fh.write(f'    <path android:fillColor="#B88A8A" android:pathData="{clef}"/>\n')
    fh.write('</vector>\n')

# 3) Monochrome: triangle only, white silhouette (system tints it on API 33+)
with open(f'{BASE}/app/src/main/res/drawable/ic_launcher_monochrome.xml', 'w') as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
    fh.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    fh.write('    android:width="108dp" android:height="108dp"\n')
    fh.write('    android:viewportWidth="108" android:viewportHeight="108">\n')
    fh.write(f'    <path android:fillColor="#FFFFFF" android:pathData="{tri}"/>\n')
    fh.write('</vector>\n')

# 4) Notification: triangle only, white, 24dp viewport (system tints the whole thing)
with open(f'{BASE}/app/src/main/res/drawable/ic_notification.xml', 'w') as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
    fh.write('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    fh.write('    android:width="24dp" android:height="24dp"\n')
    fh.write('    android:viewportWidth="108" android:viewportHeight="108">\n')
    fh.write(f'    <path android:fillColor="#FFFFFF" android:pathData="{tri}"/>\n')
    fh.write('</vector>\n')

# 5) Adaptive icon XMLs
for name in ('ic_launcher', 'ic_launcher_round'):
    with open(f'{BASE}/app/src/main/res/mipmap-anydpi-v26/{name}.xml', 'w') as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
        fh.write('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n')
        fh.write('    <background android:drawable="@color/ic_launcher_background" />\n')
        fh.write('    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n')
        fh.write('    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n')
        fh.write('</adaptive-icon>\n')

print()
print("wrote 6 XML files")
PY

# ── Verify files ──
echo
echo "=== [3/6] verify written files ==="
ls -la app/src/main/res/drawable/ic_launcher*.xml app/src/main/res/drawable/ic_notification.xml \
       app/src/main/res/values/ic_launcher_background.xml \
       app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml

# ── Update PlaybackService to reference ic_notification ──
echo
echo "=== [4/6] PlaybackService notification icon ==="
python3 - << 'PY'
import pathlib
p = pathlib.Path('app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt')
if p.exists():
    s = p.read_text()
    if 'setSmallIcon' in s and 'R.drawable.ic_notification' in s:
        print("  already wired")
    elif 'setMediaNotificationProvider' in s:
        print("  provider set — ic_notification used by small icon")
    else:
        # add provider
        if 'DefaultMediaNotificationProvider' not in s:
            s = s.replace(
                'import androidx.media3.session.MediaSessionService',
                'import androidx.media3.session.DefaultMediaNotificationProvider\n'
                'import androidx.media3.session.MediaSessionService', 1)
        s = s.replace(
            '        super.onCreate()\n',
            '        super.onCreate()\n'
            '        setMediaNotificationProvider(\n'
            '            DefaultMediaNotificationProvider(this).apply {\n'
            '                setSmallIcon(ir.mariaplayer.R.drawable.ic_notification)\n'
            '            }\n'
            '        )\n', 1)
        p.write_text(s)
        print("  provider + small icon wired")
else:
    print("  WARN: PlaybackService.kt missing")
PY

# ── Local preview (exact same pathData, rendered as PNG for visual check) ──
echo
echo "=== [5/6] local preview ==="
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow
if command -v rsvg-convert >/dev/null 2>&1; then R=rsvg
elif python3 -c "import cairosvg" 2>/dev/null; then R=cairosvg
else R=none
fi

if [ "$R" != none ]; then
python3 - << 'PY'
import os, subprocess
from PIL import Image

HOME = os.path.expanduser('~')
BASE = os.getcwd()

# Read back what we wrote
def read_paths(xml_file):
    s = open(xml_file).read()
    import re
    paths = re.findall(r'android:pathData="([^"]+)"', s)
    fills = re.findall(r'android:fillColor="([^"]+)"', s)
    return list(zip(paths, fills))

fg = read_paths(f'{BASE}/app/src/main/res/drawable/ic_launcher_foreground.xml')
bg_xml = open(f'{BASE}/app/src/main/res/values/ic_launcher_background.xml').read()
import re
bg = re.search(r'#([0-9A-Fa-f]{6})', bg_xml).group(1)

svg = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">',
       f'  <rect width="108" height="108" fill="#{bg}"/>']
for p, c in fg:
    svg.append(f'  <path d="{p}" fill="{c}"/>')
svg.append('</svg>')

open(os.path.join(HOME, 'final-icon-preview.svg'), 'w').write('\n'.join(svg))

if os.path.exists('/data/data/com.termux/files/usr/bin/rsvg-convert'):
    with open(os.path.join(HOME, 'final-icon-512.png'), 'wb') as fh:
        subprocess.run(['rsvg-convert', '-w', '512', '-h', '512',
                        os.path.join(HOME, 'final-icon-preview.svg')],
                       stdout=fh, check=True)
else:
    import cairosvg
    cairosvg.svg2png(url=os.path.join(HOME, 'final-icon-preview.svg'),
                     write_to=os.path.join(HOME, 'final-icon-512.png'),
                     output_width=512)

img = Image.open(os.path.join(HOME, 'final-icon-512.png'))
for sz, lbl in ((144, '144'), (48, '48')):
    img.resize((sz, sz), Image.LANCZOS).save(
        os.path.join(HOME, f'final-icon-{lbl}.png'))
print("preview: ~/final-icon-{512,144,48}.png")
PY
else
  echo "  (no SVG renderer — preview skipped)"
fi

# ── Commit + push ──
echo
echo "=== [6/6] commit + push ==="
git add -A
git commit -qm "feat(icon): final pink design — #B88A8A rose bg + #F5EFE6 cream triangle + cut-out treble clef"

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
