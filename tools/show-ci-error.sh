#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
OWNER=$(gh api user -q .login)
REPO="$OWNER/mariaplayer"
LOGF="$HOME/failed.log"

RUN_ID=$(gh run list --workflow=build.yml --repo "$REPO" --limit 5 \
  --json databaseId,conclusion -q '.[] | select(.conclusion=="failure") | .databaseId' | head -1)
[ -n "$RUN_ID" ] || { echo "no failed run found"; exit 1; }
echo "failed run: $RUN_ID"
echo

gh run view "$RUN_ID" --repo "$REPO" --log-failed 2>&1 \
  | sed -E 's/^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+//' \
  > "$LOGF"

echo "=== ERROR / FAILURE lines (no stack frames) ==="
grep -nE '(^e:|^error:|error |FAILURE:|What went wrong|> Task .*FAILED|Execution failed|Caused by:|Could not|Unresolved|cannot find symbol|not found|unsupported)' "$LOGF" \
  | grep -vE '^\s*[0-9]+:\s*at ' \
  | head -40

echo
echo "=== 'What went wrong' block (if present) ==="
awk '/What went wrong/,/^\* Try/' "$LOGF" | head -30

echo
echo "=== Kotlin compile errors ==="
grep -nE '(^e:|^e )' "$LOGF" | head -20
