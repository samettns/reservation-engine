#!/usr/bin/env bash
# Copies a benchmark run (CSV, summary, charts) to docs/benchmarks/ for the README.
#   bash bench/publish.sh                              # bench/results          -> docs/benchmarks/
#   bash bench/publish.sh results-sharding sharding    # bench/results-sharding -> docs/benchmarks/sharding/
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=bench/${1:-results}
DEST=docs/benchmarks${2:+/$2}
mkdir -p "$DEST"
cp "$SRC/results.csv" "$SRC/summary.md" "$DEST/"
cp "$SRC"/*.png "$DEST/"
echo "published $SRC -> $DEST:"
ls "$DEST"
