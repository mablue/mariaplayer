#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

echo "=== local HEAD ==="
git log --oneline -2
echo "=== remote HEAD ==="
git fetch origin main >/dev/null 2>&1 || true
git log --oneline -1 origin/main

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
echo "local:  $LOCAL"
echo "remote: $REMOTE"

if [ "$LOCAL" = "$REMOTE" ]; then
  echo "ALREADY PUSHED — nothing to do"
  exit 0
fi

echo
echo "=== retrying push with settings tuned for slow networks ==="
git config http.postBuffer 524288000          # 500 MB buffer
git config http.lowSpeedLimit 0
git config http.lowSpeedTime 999999
git config core.compression 0                  # store raw, avoid CPU

for attempt in 1 2 3 4 5; do
  echo
  echo "attempt $attempt ..."
  if git push origin main; then
    echo "PUSH OK on attempt $attempt"
    exit 0
  fi
  echo "attempt $attempt failed — retrying in 5s"
  sleep 5
done

echo "FATAL: 5 attempts failed"
exit 1
