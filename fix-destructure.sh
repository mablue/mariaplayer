#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
cd "$(dirname "$0")"
log()  { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { log "❌ $*"; exit 1; }
COMMITTED=0
rollback() {
  log "⚠️  rollback"
  if [[ "$COMMITTED" -eq 1 ]]; then git reset --hard HEAD~1 2>/dev/null || true
  else git checkout -- app/src/main/java/ir/mariaplayer/MainActivity.kt 2>/dev/null || true
  fi
}
trap 'rollback' ERR

[[ -d .git ]] || fail "no .git"
git diff --quiet && git diff --cached --quiet || fail "dirty tree"

log "پچ MainActivity.kt"
python3 <<'PYEOF'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
t = p.read_text(encoding="utf-8")
search = """                        val (minMs, maxMs): Pair<Long?, Long?> = when {
                            newKind != ir.mariaplayer.data.LibraryTab.Kind.SMART -> null to null
                            smartPreset == "under10" -> null to 10 * 60_000L
                            smartPreset == "long10"  -> 10 * 60_000L to null
                            smartPreset == "books2h" -> 2L * 60 * 60_000L to null
                            else -> null to null
                        }"""
replace = """                        val msPair: Pair<Long?, Long?> = when {
                            newKind != ir.mariaplayer.data.LibraryTab.Kind.SMART -> null to null
                            smartPreset == "under10" -> null to 10 * 60_000L
                            smartPreset == "long10"  -> 10 * 60_000L to null
                            smartPreset == "books2h" -> 2L * 60 * 60_000L to null
                            else -> null to null
                        }
                        val minMs = msPair.first
                        val maxMs = msPair.second"""
if search not in t:
    print("FAIL: block not found"); sys.exit(2)
p.write_text(t.replace(search, replace, 1), encoding="utf-8")
print("OK")
PYEOF

log "commit"
git add -A
if git diff --cached --quiet; then log "no changes"; exit 0; fi
git commit -m "fix(MainActivity): remove type annotation on destructuring (Kotlin syntax)

val (a, b): Pair<...> = ... is not valid Kotlin. Split into typed val
+ explicit .first/.second access."
COMMITTED=1
log "  ✅ $(git rev-parse --short HEAD)"

if ! git remote | grep -q .; then log "no remote — local only"; exit 0; fi
log "push"
if ! git push -u origin HEAD; then
    log "⚠️  push failed — retry: git push -u origin HEAD"; exit 2
fi
log "🎉 pushed — Actions will rebuild"
