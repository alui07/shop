#!/bin/sh
# Usage: db-stats.sh /reports/<label>.txt  Snapshot of database counters and connection figures.
set -eu
out=${1:?usage: db-stats.sh <output file>}
here=$(dirname "$0")
{
    date -u +%Y-%m-%dT%H:%M:%SZ
    sh "$here/sql.sh" db-stats.sql "run=${RUN_ID:-none}"
} >"$out"
cat "$out"
