#!/data/data/com.termux/files/usr/bin/bash
# PR-L9: bulletproof sync — commit (optional), rebase on origin, push, build.
# Usage:
#   bash tools/sync.sh                     # push existing commits only
#   bash tools/sync.sh "commit message"    # stage all + commit + push
#   SKIP_BUILD=1 bash tools/sync.sh        # no build/install
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "✗ not a git repo"; exit 1; }
cd "$ROOT"

MSG="${1:-}"

# 0) commit if message given, else refuse if dirty
if [ -n "$MSG" ]; then
  git add -A
  if ! git diff --cached --quiet; then
    git commit -m "$MSG"
    echo "[0] committed: $MSG"
  else
    echo "[0] nothing new to commit"
  fi
elif [ -n "$(git status --porcelain)" ]; then
  echo "✗ uncommitted changes. Run:"
  echo "    bash tools/sync.sh \"your commit message\""
  git status --short
  exit 1
fi

# 1) fetch
echo "[1] fetch origin"
git fetch origin main

# 2) rebase (auto, thanks to pull.rebase=true)
echo "[2] rebase on origin/main"
if ! git pull --rebase origin main; then
  echo "✗ rebase conflict — resolve manually:"
  git diff --name-only --diff-filter=U
  echo "    fix → git add -A && git rebase --continue"
  echo "    abort → git rebase --abort"
  exit 1
fi

# 3) push
echo "[3] push origin main"
if ! git push origin main; then
  echo "✗ push failed even after rebase"
  exit 1
fi
echo "[3] ✓ pushed: $(git log --oneline -1)"

# 4) build + install
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "[4] build+install"
  exec bash tools/get-apk.sh
fi
