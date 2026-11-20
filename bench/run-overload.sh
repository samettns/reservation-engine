#!/usr/bin/env bash
# Stage 7a overload benchmark: 5,000 concurrent users, every strategy, contention + sellout.
#
#   before  : app image built from BEFORE_REF (stage 6): 5 s pool timeout, pool timeout -> 500,
#             no load shedding
#   no-shed : this tree, load shedding OFF (500 ms pool timeout, pool timeout -> 503)
#   after   : this tree as configured (+ load shedding, 100 requests in flight per instance)
#
# Only the app image differs. The driver, k6 script, compose file, nginx and DB are the same for
# all three. (The old app ignores the new SHED_* variables; its pool timeout is pinned to the old
# 5 s explicitly, because the compose file now defaults to 500 ms.)
#
#   bash bench/run-overload.sh            (~25 min)
#   python3 bench/compare_overload.py     (table -> bench/results-overload/comparison.md)
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
BEFORE_REF=${BEFORE_REF:-fa5b68a}
WT=$HOME/bench-before/reservation-engine
OUT=bench/results-overload
export BENCH_VUS=${BENCH_VUS:-5000}

build_image() { # directory to build the app image from
  echo "==> building jar and image from $1"
  (cd "$1" && ./mvnw -B -ntp -q package -DskipTests && docker compose --profile bench build -q app1)
}

run_variant() { # name, extra env...
  local name=$1; shift
  echo "==> [$name] running"
  rm -rf "$OUT/$name"
  env "$@" BENCH_OUT="$OUT/$name" python3 bench/run_bench.py
  docker compose --profile bench stop app1 app2 app3 nginx > /dev/null
}

docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis

echo "==> worktree for $BEFORE_REF"
git worktree remove --force "$WT" 2>/dev/null || true
git worktree prune
mkdir -p "$(dirname "$WT")"
git worktree add --detach "$WT" "$BEFORE_REF" > /dev/null
build_image "$WT"
git worktree remove --force "$WT"
run_variant before DB_CONNECTION_TIMEOUT_MS=5000

build_image "$REPO"
run_variant no-shed SHED_ENABLED=false
run_variant after

echo "Results: $OUT/{before,no-shed,after}/results.csv  ->  python3 bench/compare_overload.py"
