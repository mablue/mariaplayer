#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
OWNER=$(gh api user -q .login)
REPO="$OWNER/mariaplayer"
LOGF="$HOME/failed.log"

RUN_ID=$(gh run list --workflow=build.yml --repo "$REPO" --limit 5 \
  --json databaseId,conclusion -q '.[] | select(.conclusion=="failure") | .databaseId' | head -1)
[ -n "$RUN_ID" ] || { echo "no failed run"; exit 1; }
echo "failed run: $RUN_ID"
echo

# Retry log fetch (GitHub log endpoint sometimes TLS-times-out on Termux)
for attempt in 1 2 3 4 5; do
  echo "fetching log — attempt $attempt ..."
  if gh run view "$RUN_ID" --repo "$REPO" --log-failed 2>"$HOME/.gh_retry_err" \
       | sed -E 's/^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+//' > "$LOGF"; then
    if [ -s "$LOGF" ]; then break; fi
  fi
  echo "  retry in 5s ($(cat "$HOME/.gh_retry_err" 2>/dev/null | head -1))"
  sleep 5
done

if [ ! -s "$LOGF" ]; then
  echo "FATAL: log fetch failed after 5 attempts"
  echo
  echo "Try fetching via gh api directly:"
  gh api "repos/$REPO/actions/runs/$RUN_ID/jobs" \
    --jq '.jobs[] | select(.conclusion=="failure") | {name, steps: [.steps[] | select(.conclusion=="failure") | {name, conclusion}]}'
  echo
  echo "Or open in browser:"
  echo "  https://github.com/$REPO/actions/runs/$RUN_ID"
  exit 1
fi

echo
echo "=== ERROR / FAILURE lines ==="
grep -nE '(^e:|^error:|error |FAILURE:|What went wrong|> Task .*FAILED|Execution failed|Caused by:|Could not|Unresolved|cannot find symbol|not found|AAPT|aapt2|Android resource linking)' "$LOGF" \
  | grep -vE '^\s*[0-9]+:\s*at ' \
  | head -40

echo
echo "=== 'What went wrong' block ==="
awk '/What went wrong/,/^\* Try/' "$LOGF" | head -25

echo
echo "=== Kotlin errors ==="
grep -nE '^e: ' "$LOGF" | head -15

echo
echo "=== Resource/AAPT errors ==="
grep -niE 'resource|aapt|drawable|mipmap|adaptive|monochrome' "$LOGF" | grep -v 'at org' | head -20
