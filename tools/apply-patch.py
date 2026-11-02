#!/usr/bin/env python3
"""Apply aider-style SEARCH/DIFF blocks. Fail loudly on any ambiguity.
Usage: python3 tools/apply-patch.py <patch.txt|-> <target_file>
"""
import re, sys
from pathlib import Path

def main():
    if len(sys.argv) < 3:
        print('usage: apply-patch.py <patch.txt|-> <target>'); return 1
    src = sys.argv[1]
    target = Path(sys.argv[2])
    text = sys.stdin.read() if src == '-' else Path(src).read_text()
    if not target.exists():
        print(f'ERROR: {target} not found'); return 1
    s = target.read_text()
    blocks = re.findall(r'<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n?>>>>>>> REPLACE', text, re.DOTALL)
    if not blocks:
        print('ERROR: no SEARCH/DIFF blocks found'); return 1
    applied = 0
    for i, (search, replace) in enumerate(blocks, 1):
        cnt = s.count(search)
        if cnt == 0:
            # If REPLACE text is already present and non-trivial, treat as
            # already applied (safe re-run after a mid-flight failure).
            if replace.strip() and replace in s:
                print(f'[{i}] SKIP: already applied')
                continue
            print(f'[{i}] FAIL: SEARCH not found and REPLACE not present'); return 1
        if cnt > 1:
            print(f'[{i}] FAIL: appears {cnt} times'); return 1
        s = s.replace(search, replace, 1)
        applied += 1
        print(f'[{i}] OK  ({len(search)} -> {len(replace)} chars)')
    target.write_text(s)
    print(f'\nApplied {applied} block(s) to {target}')
    return 0

if __name__ == '__main__':
    sys.exit(main())
