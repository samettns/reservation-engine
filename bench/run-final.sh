#!/usr/bin/env bash
# Stage 8 final benchmark: the README's main claims, 3 repetitions each, 100 and 1,000 users.
#
#   BATCH=1  A strategies (1 shard, contention + sellout)        + D stock-last tx order   (~60 min)
#   BATCH=2  B 16 shards (+ pessimistic-precheck at 1 shard)      + C sold-out shortcuts    (~75 min)
#
# Repetitions are INTERLEAVED (rep 1 of every config, then rep 2, ...) so a slow drift of the
# machine (heat, background work) does not favour one config. A config/rep with a complete
# results.csv is skipped, so an interrupted batch can simply be started again.
# Clients honour 503 + Retry-After (as in stage 7a). All app switches are environment variables,
# so every comparison is the SAME code with ONE setting changed. The published results were measured
# with TX_ORDER=stock-first (the default until stage 8 closed), so A/B/C pin it explicitly.
#
#   BATCH=1 bash bench/run-final.sh ; BATCH=2 bash bench/run-final.sh
#   python3 bench/summarize_final.py      -> docs/benchmarks/final/summary.md
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${FINAL_OUT:-bench/results-final}
REPS=${REPS:-3}
export BENCH_VUS=${BENCH_VUS:-100,1000} BENCH_RETRY_503=1 BENCH_DURATION=${BENCH_DURATION:-20s} \
  BENCH_WARMUP=${BENCH_WARMUP:-10s} SELLOUT_STOCK=${SELLOUT_STOCK:-1000} SELLOUT_REQUESTS=${SELLOUT_REQUESTS:-20000}
ONLY=${ONLY:-}   # e.g. ONLY=D-stock-last runs just that config (smoke tests)

# name | expected rows | environment
CONFIGS_1=(
  "A-strategies|20|TX_ORDER=stock-first BENCH_STRATEGIES=naive,atomic,optimistic,pessimistic,redis BENCH_MODES=contention,sellout BENCH_SHARDS=1"
  "D-stock-last|8|TX_ORDER=stock-last BENCH_STRATEGIES=atomic,pessimistic BENCH_MODES=contention,sellout BENCH_SHARDS=1"
)
CONFIGS_2=(
  "B-shards16|16|TX_ORDER=stock-first BENCH_STRATEGIES=atomic,optimistic,pessimistic,pessimistic-precheck BENCH_MODES=contention,sellout BENCH_SHARDS=16"
  "B-precheck1|4|TX_ORDER=stock-first BENCH_STRATEGIES=pessimistic-precheck BENCH_MODES=contention,sellout BENCH_SHARDS=1"
  "C-shortcuts-off|12|TX_ORDER=stock-first SOLDOUT_SHORTCUTS=false BENCH_STRATEGIES=atomic,pessimistic,redis BENCH_MODES=sellout BENCH_SHARDS=1,16"
  "C-redis16-on|2|TX_ORDER=stock-first BENCH_STRATEGIES=redis BENCH_MODES=sellout BENCH_SHARDS=16"
  "C-redis-verify-stale-only|4|TX_ORDER=stock-first REDIS_VERIFY_SOLD_OUT=stale-only BENCH_STRATEGIES=redis BENCH_MODES=sellout BENCH_SHARDS=1,16"
)
case "${BATCH:-}" in
  1) CONFIGS=("${CONFIGS_1[@]}") ;;
  2) CONFIGS=("${CONFIGS_2[@]}") ;;
  *) echo "set BATCH=1 or BATCH=2"; exit 1 ;;
esac

echo "==> building jar and image"
./mvnw -B -ntp -q package -DskipTests && docker compose --profile bench build -q app1 || exit 1
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1

for rep in $(seq 1 "$REPS"); do
  for entry in "${CONFIGS[@]}"; do
    IFS='|' read -r name rows envs <<< "$entry"
    [ -n "$ONLY" ] && [ "$name" != "$ONLY" ] && continue
    dir="$OUT/$name/rep$rep"
    if [ -f "$dir/results.csv" ] && [ "$(( $(wc -l < "$dir/results.csv") - 1 ))" -ge "$rows" ]; then
      echo "==> [$name rep $rep] already complete, skipped"; continue
    fi
    echo "==> [$name rep $rep] $(date +%H:%M:%S)"
    rm -rf "$dir"
    env $envs BENCH_OUT="$dir" python3 bench/run_bench.py || echo "!! [$name rep $rep] failed"
  done
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
echo "==> batch ${BATCH} done $(date +%H:%M:%S)"
