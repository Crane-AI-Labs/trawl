#!/bin/bash
# Copies the phone's index (debug build, via run-as) and prints files by kind and state, vectors, and size.
ADB="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
OUT=${TMPDIR:-/tmp}/findr-index; mkdir -p "$OUT"
for f in findr-index.db findr-index.db-wal findr-index.db-shm; do
  $ADB exec-out run-as com.craneai.findr cat no_backup/index/$f > "$OUT/$f" 2>/dev/null
done
python3 - "$OUT/findr-index.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
rows = db.execute("SELECT kind, state, COUNT(*) FROM files GROUP BY kind, state ORDER BY kind, state").fetchall()
kinds = {}
for k, s, n in rows: kinds.setdefault(k, {})[s] = n
for k, d in kinds.items(): print(f"{k:<11}", "  ".join(f"{s}={n}" for s, n in sorted(d.items())))
print("segments by kind:", dict(db.execute("SELECT kind, COUNT(*) FROM segments GROUP BY kind").fetchall()))
print("vectors:", db.execute("SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL").fetchone()[0])
for name, detail in db.execute("SELECT name, detail FROM files WHERE state='failed' LIMIT 8"): print("FAILED", name, "->", detail)
PY
ls -la "$OUT" | awk 'NR>1 {s+=$5} END {print "index bytes on phone:", s}'
