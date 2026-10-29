#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
if ! grep -q "material-icons-core has no Pause" LESSONS.md 2>/dev/null; then
cat >> LESSONS.md <<'LESSONS'

## 2026-10-29 compose — Icons.Filled.Pause not in material-icons-core
- tried: import Icons.Filled.Pause in NowPlayingBar
- why-failed: material-icons-core ships a small subset (PlayArrow, Menu,
  Search, etc). Pause / SkipNext / SkipPrevious / Shuffle are only in
  material-icons-extended (~50 MB). Unresolved reference: 'Pause'.
- learned: for P1 prefer TextButton("Play"/"Pause") instead of pulling in
  material-icons-extended. If a future UI genuinely needs the extended set,
  add it once and keep it — never mix.
- fixed-by: NowPlayingBar uses TextButton with "Play"/"Pause" labels
LESSONS
git add -A
git commit -qm "docs(LESSONS): record Pause icon gotcha"
if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi
echo "lesson appended"
else
  echo "lesson already present"
fi
