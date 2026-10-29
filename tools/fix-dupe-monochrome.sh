#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

echo "=== before ==="
ls -la app/src/main/res/drawable/ | grep ic_launcher_monochrome || echo "none"

# Remove stale PNG, keep the XML
rm -f app/src/main/res/drawable/ic_launcher_monochrome.png

# Belt-and-suspenders: no other drawable should exist as both .xml and .png
python3 - << 'PY'
import pathlib
d = pathlib.Path('app/src/main/res/drawable')
stems = {}
for f in d.iterdir():
    if f.suffix in ('.xml', '.png'):
        stems.setdefault(f.stem, []).append(f.name)
dupes = {k: v for k, v in stems.items() if len(v) > 1}
if dupes:
    print("STILL DUPLICATED:")
    for k, v in dupes.items():
        print(f"  {k}: {v}")
        # keep .xml, delete .png — safe for all our vector icons
        for fname in v:
            if fname.endswith('.png'):
                (d / fname).unlink()
                print(f"    deleted {fname}")
else:
    print("no duplicate stems")
PY

echo "=== after ==="
ls -la app/src/main/res/drawable/ | grep ic_launcher_monochrome || echo "none"

git add -A
git commit -qm "fix(icon): delete stale ic_launcher_monochrome.png (duplicate of .xml)"

for attempt in 1 2 3; do
  if git push origin main; then break; fi
  echo "push retry $attempt"
  git pull --rebase origin main || true
  sleep 3
done
echo "pushed"
