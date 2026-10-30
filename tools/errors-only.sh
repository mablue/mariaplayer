#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
LOGF="${1:-$HOME/ci-failed.log}"
[ -f "$LOGF" ] || { echo "no log at $LOGF"; exit 1; }
echo "log: $LOGF ($(wc -l < "$LOGF") lines)"
echo
echo "── e: file:///… (Kotlin/Java compile errors with exact line:col) ──"
grep -E '^e: file:///' "$LOGF" || echo "  (none)"
echo
echo "── Failed tasks ──"
grep -E '^(> Task .* FAILED|FAILURE:)' "$LOGF" || echo "  (none)"
echo
echo "── Root causes ──"
grep -E '^(Caused by:|Could not resolve|Execution failed|Unresolved|cannot find symbol|Could not find)' "$LOGF" \
  | grep -vE '^\s+at ' || echo "  (none)"
