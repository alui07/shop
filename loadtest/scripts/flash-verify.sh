#!/bin/sh
# Usage: flash-verify.sh /reports <RUN_ID>  Waits for the flash-sale orders to settle, then writes one PASS/FAIL line per check.
set -u
dir=${1:?usage: flash-verify.sh <report dir> <run id>}
run=${2:?usage: flash-verify.sh <report dir> <run id>}
here=$(dirname "$0")
waited=0
while [ "$waited" -lt 60 ]; do
    pending=$(sh "$here/sql.sh" pending-count.sql "run=$run" 2>/dev/null | tr -d ' \r')
    [ "$pending" = 0 ] && break
    sleep 2
    waited=$((waited + 2))
done
sh "$here/sql.sh" flash-verify.sql "run=$run" >"$dir/flash-verify.raw" || { echo "flash-verify.sql failed" >&2; cat "$dir/flash-verify.raw" >&2; exit 1; }
{
    echo "settle wait ${waited}s, pending orders left: ${pending:-unknown}"
    grep -E '^(PASS|FAIL) ' "$dir/flash-verify.raw"
} >"$dir/flash-verify.txt"
cat "$dir/flash-verify.txt"
if ! grep -q '^PASS ' "$dir/flash-verify.txt" || grep -q '^FAIL ' "$dir/flash-verify.txt"; then exit 1; fi
