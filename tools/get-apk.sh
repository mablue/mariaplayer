#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
OWNER=$(gh api user -q .login)
FULL="$OWNER/mariaplayer"

RUN_ID=$(gh run list --workflow=build.yml --repo "$FULL" --limit 1 --json databaseId -q '.[0].databaseId')
echo "run $RUN_ID"

gh run watch "$RUN_ID" --repo "$FULL" --exit-status >/dev/null 2>&1 \
  || { echo "FAILED step:"; gh run view "$RUN_ID" --repo "$FULL" --log-failed 2>&1 | tail -30; exit 1; }

APK_NAME=$(gh release view --repo "$FULL" --json assets \
  -q '.assets[] | select(.name|endswith(".apk")) | .name' -L 1 2>/dev/null || true)
[ -n "${APK_NAME:-}" ] || { echo "no release apk yet — check https://github.com/$FULL/releases"; exit 1; }

echo "downloading $APK_NAME"
gh release download --repo "$FULL" --pattern "$APK_NAME" \
  --dir "$HOME/storage/downloads" --clobber
termux-open "$HOME/storage/downloads/$APK_NAME"
echo "installing dialog opened"
