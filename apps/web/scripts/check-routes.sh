#!/bin/sh
# tsr has no check mode: regenerate, compare with the committed tree, restore it.
set -eu
tree=src/routeTree.gen.ts
committed=$(mktemp)
cp "$tree" "$committed"
trap 'cp "$committed" "$tree"; rm -f "$committed"' EXIT
pnpm exec tsr generate
if cmp -s "$tree" "$committed"; then
  exit 0
fi
echo "$tree is stale: run 'pnpm run generate:routes' and commit the result" >&2
exit 1
