#!/usr/bin/env bash
# Stage 7a overload benchmark: 5,000 concurrent users, every strategy, contention + sellout.
#
#   before    : app image built from BEFORE_REF (stage 6): 5 s pool timeout, pool timeout -> 500,
#               no load shedding
#   no-shed   : this tree, load shedding OFF (500 ms pool timeout, pool timeout -> 503)
#   after-100 : this tree + load shedding, 100 reservation requests in flight per instance
#   after-20  : this tree + load shedding, 20 in flight per instance (= DB pool size)
#   http-100  : (second round) limit 100 at the HTTP entry       } same nginx (max_fails=10, 10 s
#   db-20     : (second round) limit 20 at the DB step only      } connect timeout), Retry-After 1-3 s
#               VARIANTS="http-100 db-20" SKIP_BEFORE=1 bash bench/run-overload.sh
#
# Clients honour 503 + Retry-After (BENCH_RETRY_503=1): wait, then retry the same request, up to
# 10 attempts. This does not change "before" (it never answers 503). Without it, a closed-model
# client retries a refusal instantly, and fast refusals become a refusal storm (measured once:
# ~10,000 refusals/s and half the throughput; kept in docs as the "no-retry" run).
#
# Only the app image / its settings differ. Driver, k6 script, compose file, nginx and DB are the
# same. The old app ignores SHED_*; its pool timeout is pinned to its old 5 s explicitly.
#
#   bash bench/run-overload.sh                              (~40 min, all variants)
#   SKIP_BEFORE=1 VARIANTS="after-20" bash bench/run-overload.sh
#   python3 bench/compare_overload.py                       (-> bench/results-overload/comparison.md)
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
BEFORE_REF=${BEFORE_REF:-fa5b68a}
WT=$HOME/bench-before/reservation-engine
OUT=bench/results-overload
VARIANTS=${VARIANTS:-"no-shed after-100 after-20"}
export BENCH_VUS=${BENCH_VUS:-5000}
export BENCH_RETRY_503=${BENCH_RETRY_503:-1}

build_image() { # directory to build the app image from
  echo "==> building jar and image from $1"
  (cd "$1" && ./mvnw -B -ntp -q package -DskipTests && docker compose --profile bench build -q app1)
}

run_variant() { # name, extra env...
  local name=$1; shift
  echo "==> [$name] running"
  rm -rf "${OUT:?}/$name"
  env "$@" BENCH_OUT="$OUT/$name" python3 bench/run_bench.py
  docker compose --profile bench stop app1 app2 app3 nginx > /dev/null
}

docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis

if [ "${SKIP_BEFORE:-}" != 1 ]; then   # SKIP_BEFORE=1 keeps an existing "before" result
  echo "==> worktree for $BEFORE_REF"
  git worktree remove --force "$WT" 2>/dev/null || true
  git worktree prune
  mkdir -p "$(dirname "$WT")"
  git worktree add --detach "$WT" "$BEFORE_REF" > /dev/null
  build_image "$WT"
  git worktree remove --force "$WT"
  run_variant before DB_CONNECTION_TIMEOUT_MS=5000
fi

build_image "$REPO"
for v in $VARIANTS; do
  case $v in
    no-shed)   run_variant no-shed SHED_ENABLED=false ;;
    after-100) run_variant after-100 SHED_MAX_IN_FLIGHT=100 ;;
    after-20)  run_variant after-20 SHED_MAX_IN_FLIGHT=20 ;;
    # second round (stage 7a): limit at the HTTP entry vs at the DB step (after the sold-out shortcuts)
    http-100)  run_variant http-100 SHED_MODE=http SHED_MAX_IN_FLIGHT=100 ;;
    db-20)     run_variant db-20 SHED_MODE=db SHED_MAX_IN_FLIGHT=20 ;;
    *) echo "unknown variant $v"; exit 1 ;;
  esac
done

echo "Results: $OUT/<variant>/results.csv  ->  python3 bench/compare_overload.py"
