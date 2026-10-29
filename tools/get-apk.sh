#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

OWNER=$(gh api user -q .login)
REPO="$OWNER/mariaplayer"
LOCAL_SHA=$(git rev-parse --short HEAD)
echo "repo: $REPO  head: $LOCAL_SHA"

# ── [1] wait for a completed run matching local HEAD ─────────────────
echo
echo "[1/3] waiting for CI run for $LOCAL_SHA ..."
FINAL_RUN=""
for i in $(seq 1 60); do
  ROW=$(gh run list --workflow=build.yml --repo "$REPO" --limit 15 \
    --json databaseId,headSha,status,conclusion 2>/dev/null \
    | python3 -c "
import json, sys
sha = '$LOCAL_SHA'
try:
    runs = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for r in runs:
    if r.get('headSha','').startswith(sha):
        print(r['databaseId'], r['status'], r.get('conclusion') or '-')
        break
")
  if [ -n "$ROW" ]; then
    set -- $ROW
    RID=$1; RSTAT=$2; RCON=$3
    if [ "$RSTAT" = "completed" ]; then
      if [ "$RCON" = "success" ]; then
        FINAL_RUN=$RID
        echo "      run $RID: success"
        break
      else
        echo "      run $RID: FAILED ($RCON) — last 30 lines:"
        gh run view "$RID" --repo "$REPO" --log-failed 2>&1 | tail -30
        exit 1
      fi
    else
      echo "      run $RID: $RSTAT ... (${i}/60)"
    fi
  else
    echo "      no matching run yet (${i}/60)"
  fi
  sleep 10
done

if [ -z "$FINAL_RUN" ]; then
  echo "FATAL: no successful run found after 10 min"
  gh run list --workflow=build.yml --repo "$REPO" --limit 5
  exit 1
fi

# ── [2] wait for the release asset to exist ──────────────────────────
echo
echo "[2/3] waiting for release asset ..."
APK_URL=""; APK_NAME=""
for i in $(seq 1 30); do
  JSON=$(gh api "repos/$REPO/releases?per_page=5" 2>/dev/null || echo '[]')
  PICK=$(echo "$JSON" | python3 -c "
import json, sys
try:
    rels = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for r in rels:
    for a in r.get('assets', []):
        if a['name'].endswith('.apk'):
            print(a['name'], a['browser_download_url'], a['size'])
            sys.exit(0)
")
  if [ -n "$PICK" ]; then
    set -- $PICK
    APK_NAME=$1
    APK_URL=$2
    APK_SIZE=$3
    echo "      found: $APK_NAME ($APK_SIZE bytes)"
    break
  fi
  echo "      no apk yet (${i}/30)"
  sleep 10
done

if [ -z "$APK_URL" ]; then
  echo "FATAL: no apk found in recent releases"
  exit 1
fi

# ── [3] download via curl (direct URL, no gh wrapper) ────────────────
echo
echo "[3/3] downloading $APK_NAME"
DEST="$HOME/storage/downloads/$APK_NAME"
curl -fL --retry 3 --retry-delay 2 -m 120 -o "$DEST" "$APK_URL"

SZ=$(stat -c%s "$DEST")
if [ "$SZ" -lt 100000 ]; then
  echo "FATAL: downloaded file too small ($SZ bytes) — not a real apk"
  head -c 200 "$DEST"
  rm -f "$DEST"
  exit 1
fi
ls -lh "$DEST"

echo
echo "opening installer ..."
termux-open "$DEST"
