#!/bin/sh
# Fails when the commits in RANGE change the app, tests, benchmark, build or workflows without updating HYBRID-CHANGELOG.md.
# Usage: scripts/check-changelog.sh <base> <head>   (CI passes the push or pull request range)
set -e
base="$1"; head="${2:-HEAD}"
[ -z "$base" ] || [ "$base" = "0000000000000000000000000000000000000000" ] && base="$head~1"
changed=$(git diff --name-only "$base" "$head")
needs=$(printf '%s\n' "$changed" | grep -E '^(app/|bench/|build\.gradle\.kts|settings\.gradle\.kts|gradle/|\.github/workflows/|scripts/)' || true)
if [ -n "$needs" ] && ! printf '%s\n' "$changed" | grep -qx 'HYBRID-CHANGELOG.md'; then
  echo "These changes need an entry in HYBRID-CHANGELOG.md (why, change, files, tests, evidence):"
  printf '%s\n' "$needs" | sed 's/^/  /'
  exit 1
fi
echo "Change record OK."
