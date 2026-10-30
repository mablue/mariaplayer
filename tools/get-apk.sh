#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

OWNER=$(gh api user -q .login)
REPO="$OWNER/mariaplayer"
LOCAL_SHA=$(git rev-parse --short HEAD)
LOGF="$HOME/ci-failed.log"
echo "repo: $REPO  head: $LOCAL_SHA"

# ── [1/3] wait for CI run matching local HEAD ────────────────────────
echo
echo "[1/3] waiting for CI run for $LOCAL_SHA ..."
FINAL_RUN=""
for i in $(seq 1 60); do
  ROW=$(gh run list --workflow=build.yml --repo "$REPO" --limit 15 \
    --json databaseId,headSha,status,conclusion 2>/dev/null \
    | python3 -c "
import json, sys
sha = '$LOCAL_SHA'
try: runs = json.load(sys.stdin)
except Exception: sys.exit(0)
for r in runs:
    if r.get('headSha','').startswith(sha):
        print(r['databaseId'], r['status'], r.get('conclusion') or '-')
        break
")
  if [ -n "$ROW" ]; then
    set -- $ROW
    RID=$1; RSTAT=$2; RCON=$3
    if [ "$RSTAT" = "completed" ]; then
      if [ "$RCON" = "success" ]; then FINAL_RUN=$RID; echo "      run $RID: success"; break
      else
        echo "      run $RID: FAILED — extracting exact error locations"
        gh run view "$RID" --repo "$REPO" --log-failed 2>&1 \
          | sed -E 's/^[0-9T:.Z-]+[[:space:]]+//' > "$LOGF"

        echo
        echo "════════ CI ERROR — EXACT LOCATIONS ════════"
        echo
        echo "── Kotlin/Java compile errors (file:line:col) ──"
        grep -E '^e: file:///' "$LOGF" | head -40 || true
        echo
        echo "── Other e: lines ──"
        grep -E '^e: ' "$LOGF" | grep -v '^e: file:///' | head -15 || true
        echo
        echo "── Failed tasks ──"
        grep -E '^(> Task .* FAILED|FAILURE:)' "$LOGF" | head -10 || true
        echo
        echo "── What went wrong ──"
        awk '/What went wrong/,/^\* Try/' "$LOGF" | head -20 || true
        echo
        echo "── Root causes (no stack frames) ──"
        grep -E '^(Caused by:|Could not resolve|Execution failed|Unresolved|cannot find symbol|Could not find)' "$LOGF" \
          | grep -vE '^\s+at ' | head -15 || true
        echo
        echo "── File paths mentioned ──"
        grep -oE 'file:///[^ :]+\.(kt|java):[0-9]+:[0-9]+' "$LOGF" | sort -u | head -20 || true
        echo "════════════════════════════════════════════"
        echo
        echo "full log saved to: $LOGF"
        exit 1
      fi
    else echo "      run $RID: $RSTAT ... (${i}/60)"
    fi
  else echo "      no matching run yet (${i}/60)"
  fi
  sleep 10
done
[ -n "$FINAL_RUN" ] || { echo "FATAL: no successful run"; exit 1; }

# ── [2/3] find release asset ─────────────────────────────────────────
echo
echo "[2/3] locating latest release ..."
PICK=$(gh api "repos/$REPO/releases?per_page=5" 2>/dev/null | python3 -c "
import json, sys
try: rels = json.load(sys.stdin)
except Exception: sys.exit(0)
for r in rels:
    for a in r.get('assets', []):
        if a['name'].endswith('.apk'):
            print(r['tag_name'], a['name'], a['size']); sys.exit(0)
")
[ -n "$PICK" ] || { echo "FATAL: no apk in releases"; exit 1; }
set -- $PICK; TAG=$1; APK_NAME=$2; APK_SIZE=$3
echo "      tag:   $TAG"
echo "      apk:   $APK_NAME ($APK_SIZE bytes)"
echo "      page:  https://github.com/$REPO/releases/tag/$TAG"

# ── [3/3] authenticated download + open ──────────────────────────────
echo
echo "[3/3] downloading with gh (private repo) ..."
DEST_DIR="$HOME/storage/downloads"
rm -f "$DEST_DIR/$APK_NAME"
gh release download "$TAG" --repo "$REPO" --pattern "$APK_NAME" --dir "$DEST_DIR" --clobber

SZ=$(stat -c%s "$DEST_DIR/$APK_NAME")
[ "$SZ" -gt 100000 ] || { echo "FATAL: downloaded file too small ($SZ bytes)"; exit 1; }
ls -lh "$DEST_DIR/$APK_NAME"
echo
termux-open-url "https://github.com/$REPO/releases/tag/$TAG"
termux-open "$DEST_DIR/$APK_NAME"
