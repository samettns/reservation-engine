#!/usr/bin/env bash
# Stage 3 benchmark: stock sharding x locking strategy.
#   bash bench/run-sharding.sh          # ~30 min
# Output: bench/results-sharding/ (publish with: bash bench/publish.sh results-sharding sharding)
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> building jar and app image"
./mvnw -B -ntp -q package -DskipTests
docker compose --profile bench build -q

echo "==> starting postgres and redis (kafka stopped)"
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis

echo "==> running sharding matrix"
BENCH_OUT=bench/results-sharding \
BENCH_STRATEGIES=${BENCH_STRATEGIES:-atomic,optimistic,pessimistic,pessimistic-precheck} \
BENCH_SHARDS=${BENCH_SHARDS:-1,4,16} \
BENCH_VUS=${BENCH_VUS:-10,100,1000} \
  python3 bench/run_bench.py

echo "==> drawing charts"
bash bench/plot.sh plot_sharding.py results-sharding

docker compose --profile bench stop app1 app2 app3 nginx > /dev/null
echo "Results: bench/results-sharding/"
