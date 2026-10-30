#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

# ── Preconditions ─────────────────────────────────────────
[ -s "$HOME/clef_path.txt" ] || { echo "FATAL: ~/clef_path.txt missing — run extract step first"; exit 1; }
python3 -c "import PIL" 2>/dev/null || pip install --quiet pillow

TRI_PATH='M48.66,26.26 L80.69,44.76 A10.68,10.68 0 0 1 80.69,63.24 L48.66,81.74 A10.68,10.68 0 0 1 32.65,72.45 L32.65,35.55 A10.68,10.68 0 0 1 48.66,26.26 Z'

# ── Check SVG rasterizer availability ─────────────────────
if python3 -c "import cairosvg" 2>/dev/null; then
  RENDERER=cairosvg
elif command -v rsvg-convert >/dev/null 2>&1; then
  RENDERER=rsvg
elif command -v inkscape >/dev/null 2>&1; then
  RENDERER=inkscape
else
  echo "No SVG rasterizer found — attempting install..."
  pkg install -y librsvg 2>/dev/null || pip install --quiet cairosvg 2>/dev/null || true
  if command -v rsvg-convert >/dev/null 2>&1; then RENDERER=rsvg
  elif python3 -c "import cairosvg" 2>/dev/null; then RENDERER=cairosvg
  else echo "FATAL: install librsvg (pkg install librsvg) or cairosvg"; exit 1
  fi
fi
echo "renderer: $RENDERER"

# ── Build SVG for a given variant ─────────────────────────
build_svg() {
  local outfile=$1 bg=$2 tri=$3 clef=$4 ring=$5
  local clef_path=$(cat "$HOME/clef_path.txt")
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n'
    printf '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">\n'
    printf '  <rect width="108" height="108" fill="%s"/>\n' "$bg"
    printf '  <path d="%s" fill="%s"/>\n' "$TRI_PATH" "$tri"
    printf '  <path d="%s" fill="%s"/>\n' "$clef_path" "$clef"
    [ -n "$ring" ] && printf '  <circle cx="54" cy="54" r="33" fill="none" stroke="%s" stroke-width="0.3" stroke-dasharray="1,1"/>\n' "$ring"
    printf '</svg>\n'
  } > "$outfile"
}

# ── Rasterize SVG → PNG ───────────────────────────────────
rasterize() {
  local svg=$1 png512=$2 png144=$3 png48=$4
  case "$RENDERER" in
    cairosvg)
      python3 -c "import cairosvg; cairosvg.svg2png(url='$svg', write_to='$png512', output_width=512)"
      ;;
    rsvg)
      rsvg-convert -w 512 -h 512 "$svg" > "$png512"
      ;;
    inkscape)
      inkscape "$svg" -o "$png512" -w 512 -h 512 >/dev/null 2>&1
      ;;
  esac
  python3 - "$png512" "$png144" "$png48" << 'PY'
import sys
from PIL import Image
src = Image.open(sys.argv[1])
for sz, path in ((144, sys.argv[2]), (48, sys.argv[3])):
    src.resize((sz, sz), Image.LANCZOS).save(path)
PY
}

# ── Three variants ────────────────────────────────────────
echo
echo "=== Variant A: deep sage + cream + antique gold ==="
build_svg ~/clef-A.svg  "#3A5A40" "#F5EFE6" "#C9A227" ""
rasterize ~/clef-A.svg ~/clef-A-512.png ~/clef-A-144.png ~/clef-A-48.png
echo "  bg=#3A5A40 tri=#F5EFE6 clef=#C9A227"

echo
echo "=== Variant B: forest + ivory + bright gold ==="
build_svg ~/clef-B.svg  "#2D6A4F" "#FDF6F5" "#C6AC40" ""
rasterize ~/clef-B.svg ~/clef-B-512.png ~/clef-B-144.png ~/clef-B-48.png
echo "  bg=#2D6A4F tri=#FDF6F5 clef=#C6AC40"

echo
echo "=== Variant C: sage + cream + pale-pink blush clef ==="
build_svg ~/clef-C.svg  "#3A5A40" "#F5EFE6" "#EAD5D5" ""
rasterize ~/clef-C.svg ~/clef-C-512.png ~/clef-C-144.png ~/clef-C-48.png
echo "  bg=#3A5A40 tri=#F5EFE6 clef=#EAD5D5 (soft pink)"

# safe-zone check overlay for A
build_svg ~/clef-A-check.svg "#3A5A40" "#F5EFE6" "#C9A227" "#FF0000"
rasterize ~/clef-A-check.svg ~/clef-A-safecheck.png ~/clef-A-safecheck-144.png ~/clef-A-safecheck-48.png

echo
echo "=== all previews written ==="
ls -1 ~/clef-{A,B,C}-{512,144,48}.png ~/clef-A-safecheck.png
echo
echo "nothing committed."
echo
echo "Reply: A / B / C to approve"
echo "       or adjust <what> (e.g., 'A with brighter gold')"
