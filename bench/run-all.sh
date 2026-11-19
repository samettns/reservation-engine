#!/usr/bin/env bash
# One command for the whole benchmark:
#   build jar -> build app image -> start infra -> run the matrix -> draw charts.
#
#   bench/run-all.sh                                   # full matrix (~20-25 min)
#   BENCH_STRATEGIES=atomic,redis BENCH_VUS=100 BENCH_DURATION=5s bench/run-all.sh   # quick look
#
# Output: bench/results/results.csv, bench/results/*.png, bench/results/summary.md
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> building jar"
./mvnw -B -ntp -q package -DskipTests

echo "==> building app image"
docker compose --profile bench build -q

echo "==> starting postgres and redis (kafka is stopped: it would compete for resources)"
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis

echo "==> running benchmark matrix"
python3 bench/run_bench.py

echo "==> drawing charts"
bash bench/plot.sh

echo "==> stopping app instances and nginx"
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null

echo "Results: bench/results/  (to publish: bench/publish.sh copies them to docs/benchmarks/)"
