#!/usr/bin/env bash
# Thundering herd: do refused clients come back in waves? 5,000 users, clients honouring
# Retry-After, the same app twice: Retry-After always 1 s (no jitter) vs random 1-3 s (jitter).
# k6 records a `retry_sent` sample per retry (ms timestamps); we bucket them in 100 ms and compare
# how uneven the arrivals are.
#
#   bash bench/measure-retry-herd.sh      (~3 min)   -> bench/results-herd/summary.txt
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=bench/results-herd
mkdir -p "$OUT"
K6=(docker run --rm --network reservation-engine_default --user "$(id -u):$(id -g)"
    -e K6_CSV_TIME_FORMAT=unix_milli -v "$PWD/bench/k6:/scripts:ro" -v "$PWD/$OUT:/results" grafana/k6:1.3.0)
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1
export RESERVATION_STRATEGY=atomic EVENTS_ENABLED=false RECONCILER_ENABLED=false

for variant in "no-jitter 1 1" "jitter 1 3"; do
  set -- $variant
  name=$1
  RETRY_AFTER_MIN_S=$2 RETRY_AFTER_MAX_S=$3 \
    docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
  E=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' -d '{"name":"herd","stock":10000000}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
  echo "== $name (Retry-After ${2}-${3} s)"
  "${K6[@]}" run --quiet --system-tags=status --out "csv=/results/$name.csv" \
    -e BASE_URL=http://nginx:80 -e EVENT_ID="$E" -e RUN_ID="herd-$name-$RANDOM" -e MODE=contention \
    -e VUS=5000 -e DURATION=30s -e RETRY_503=1 -e SUMMARY_FILE="/results/$name.json" /scripts/reserve.js > /dev/null 2>&1 || true
  python3 - "$OUT/$name.csv" <<'EOF' | tee "$OUT/$name.txt"
import csv, statistics, sys
times = []
with open(sys.argv[1]) as f:
    for row in csv.DictReader(f):
        if row["metric_name"] == "retry_sent":
            times.append(int(row["timestamp"]))
if not times:
    sys.exit("no retries recorded")
start = min(times)
# steady part only: skip the first 3 s (ramp) and the last 2 s
lo, hi = start + 3000, max(times) - 2000
buckets = {}
for t in times:
    if lo <= t < hi:
        buckets[(t - lo) // 100] = buckets.get((t - lo) // 100, 0) + 1
n = (hi - lo) // 100
series = [buckets.get(i, 0) for i in range(n)]
mean = statistics.mean(series)
cv = statistics.pstdev(series) / mean if mean else 0
top = sorted(series, reverse=True)
top10 = sum(top[: max(1, n // 10)]) / max(1, sum(series))
blocks = " ▁▂▃▄▅▆▇█"
peak = max(series) or 1
spark = "".join(blocks[min(8, round(8 * v / peak))] for v in series[:60])
print(f"retries in steady window: {sum(series)}  per 100 ms: mean {mean:.0f}, max {max(series)}, min {min(series)}")
print(f"peak/mean = {max(series) / mean:.2f}   coefficient of variation = {cv:.2f}   busiest 10% of buckets carry {100 * top10:.0f}% of retries")
print(f"first 6 s, 100 ms buckets: {spark}")
EOF
  rm -f "$OUT/$name.csv"   # hundreds of MB; the summary above is what we keep
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
cat "$OUT"/no-jitter.txt "$OUT"/jitter.txt > /dev/null
