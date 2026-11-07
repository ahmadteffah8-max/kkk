"""Splits spec.txt into stub .java files (type-check helper only; never shipped in the APK)."""
import os, sys
out = sys.argv[1]
cur = None; buf = []
def flush():
    if cur:
        p = os.path.join(out, cur); os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'w').write("\n".join(buf) + "\n")
for line in open(os.path.join(os.path.dirname(__file__), 'spec.txt')):
    if line.startswith('//// '):
        flush(); cur = line[5:].strip(); buf = []
    else:
        buf.append(line.rstrip("\n"))
flush()
