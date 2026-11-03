#!/data/data/com.termux/files/usr/bin/bash
# Listen for CI of the CURRENT HEAD sha, wait for it, then open that
# release page in the browser. No text files, no stale links.
set -uo pipefail
cd ~/mariaplayer || exit 1

SHA=$(git rev-parse --short HEAD)
FULL=$(git rev-parse HEAD)
echo "repo:    mablue/mariaplayer"
echo "head:    $SHA"
echo "full:    $FULL"
echo

echo "[1/3] waiting for CI run for $SHA ..."
RUN_ID=""
for i in $(seq 1 240); do
  ROW=$(gh run list --workflow=build.yml --limit 10 --json databaseId,headSha,status,conclusion 2>/dev/null \
        | python3 -c "
import sys, json
target = '$FULL'
for r in json.load(sys.stdin):
    if r.get('headSha') == target:
        print(f\"{r['databaseId']}|{r['status']}|{r.get('conclusion') or ''}\")
        break
" 2>/dev/null)
  if [ -n "$ROW" ]; then
    RUN_ID=$(echo "$ROW" | cut -d'|' -f1)
    STATUS=$(echo "$ROW" | cut -d'|' -f2)
    CONCL=$(echo "$ROW" | cut -d'|' -f3)
    case "$STATUS" in
      completed)
        case "$CONCL" in
          success) echo "      run $RUN_ID: success"; break ;;
          *)       echo "      run $RUN_ID: $CONCL"
                   echo "✘ CI FAILED"
                   gh run view "$RUN_ID" --log-failed 2>&1 | grep -E '^e:|error:|FAILURE|Caused by' | head -20
                   exit 1 ;;
        esac ;;
      *) echo "      run $RUN_ID: $STATUS ... ($i/240)"; sleep 3 ;;
    esac
  else
    echo "      no matching run yet ($i/240)"
    sleep 3
  fi
done
[ -n "$RUN_ID" ] || { echo "✘ no CI run found after 12 minutes"; exit 1; }

echo
echo "[2/3] locating release tag for $SHA ..."
TAG=""
for i in $(seq 1 40); do
  TAG=$(gh release list --limit 20 --json tagName --jq ".[] | select(.tagName | contains(\"$SHA\")) | .tagName" 2>/dev/null | head -1)
  [ -n "$TAG" ] && break
  echo "      tag not published yet ($i/40)"; sleep 3
done
[ -n "$TAG" ] || { echo "✘ release tag for $SHA not found"; exit 1; }
echo "      tag: $TAG"

PAGE="https://github.com/mablue/mariaplayer/releases/tag/$TAG"
APK_NAME=$(gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null | head -1)
echo "      page: $PAGE"
[ -n "$APK_NAME" ] && echo "      apk:  $APK_NAME"

echo
echo "[3/3] opening release page in browser ..."
am start -a android.intent.action.VIEW -d "$PAGE" 2>/dev/null \
  || termux-open-url "$PAGE" 2>/dev/null \
  || echo "      open manually: $PAGE"
echo
echo "✔ DONE $SHA  →  $PAGE"
