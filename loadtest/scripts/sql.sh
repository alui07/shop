#!/bin/sh
# Usage: sql.sh <file.sql> [name=value ...]  Runs loadtest/sql/<file> on the app database as postgres, in the CNPG primary.
set -eu
file=${1:?usage: sql.sh <file.sql> [name=value ...]}
shift
dir=$(dirname "$0")/../sql
[ -f "$dir/$file" ] || { echo "no such SQL file: $file" >&2; exit 2; }
primary=$(kubectl -n shop get pod -l cnpg.io/cluster=shop-db,cnpg.io/instanceRole=primary -o jsonpath='{.items[0].metadata.name}')
[ -n "$primary" ] || { echo "no CNPG primary found for cluster shop-db" >&2; exit 1; }
vars=""
for pair in "$@"; do
    vars="$vars -v $pair"
done
# shellcheck disable=SC2086
kubectl -n shop exec -i "$primary" -c postgres -- psql -X -q -U postgres -d shop -v ON_ERROR_STOP=1 $vars <"$dir/$file"
