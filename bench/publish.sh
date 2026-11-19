#!/usr/bin/env bash
# Copies the latest benchmark run (CSV, summary, charts) to docs/benchmarks/ for the README.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p docs/benchmarks
cp bench/results/results.csv bench/results/summary.md docs/benchmarks/
cp bench/results/*.png docs/benchmarks/
echo "published to docs/benchmarks/:"
ls docs/benchmarks
