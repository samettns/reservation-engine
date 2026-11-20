#!/usr/bin/env bash
# Live check (stage 7a): a broken instance must not stay "healthy" and keep receiving traffic.
#
#   A. Out of memory: small heap (128 MB), no load shedding, 3,000 users. Expected: the JVM exits
#      (-XX:+ExitOnOutOfMemoryError), Docker restarts the container (restart: unless-stopped),
#      and all three instances end up healthy again.
#   B. Hung instance (no OOM): app2 is frozen with `docker pause` (process exists, answers nothing).
#      Expected: Docker marks it unhealthy within ~10 s; nginx stops sending it traffic after its
#      first timeout (passive check), so errors are limited to the requests caught in that window.
#
#   bash bench/verify-failfast-live.sh          (~4 min; needs postgres + redis from docker compose)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=bench/results-failfast
mkdir -p "$OUT"
NET=reservation-engine_default
K6=(docker run --rm --network "$NET" --user "$(id -u):$(id -g)" -v "$PWD/bench/k6:/scripts:ro" -v "$PWD/$OUT:/results" grafana/k6:1.3.0)
APPS=(app1 app2 app3)
c() { echo "reservation-engine-$1-1"; }
ts() { date +%H:%M:%S; }
restarts() { docker inspect -f '{{.RestartCount}}' "$(c "$1")"; }
health() { docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$(c "$1")"; }
status_line() { local s=""; for a in "${APPS[@]}"; do s+="$a=$(health "$a") restarts=$(restarts "$a")  "; done; echo "[$(ts)] $s"; }
new_event() { curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' -d '{"name":"failfast","stock":10000000}' | sed -E 's/.*"id":([0-9]+).*/\1/'; }
k6_run() { # vus duration summary-name
  "${K6[@]}" run --quiet -e BASE_URL=http://nginx:80 -e EVENT_ID="$EVENT" -e RUN_ID="ff-$3-$RANDOM" -e MODE=contention \
    -e VUS="$1" -e DURATION="$2" -e SUMMARY_FILE="/results/$3.json" /scripts/reserve.js > /dev/null 2>&1 || true
}
k6_counts() { python3 - "$OUT/$1.json" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1]))["metrics"]
get = lambda n: int(m.get(n, {}).get("values", {}).get("count", 0))
print(f"201={get('status_201')} 503={get('status_503')} 500={get('status_500')} other(502/504/timeout)={get('status_other')}")
EOF
}
start_apps() { docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null; }

docker compose up -d --wait postgres redis > /dev/null
export RESERVATION_STRATEGY=atomic EVENTS_ENABLED=false RECONCILER_ENABLED=false

if [ "${SKIP_A:-}" != 1 ]; then
echo "=== A. out of memory -> exit -> restart"
SHED_ENABLED=false APP_JAVA_OPTS="-Xms64m -Xmx128m -XX:+ExitOnOutOfMemoryError" start_apps
EVENT=$(new_event)
status_line | tee "$OUT/a-timeline.txt"
( for _ in $(seq 1 75); do status_line; sleep 2; done ) >> "$OUT/a-timeline.txt" &
POLLER=$!
k6_run 3000 40s a-load
echo "k6 (A): $(k6_counts a-load)"
for _ in $(seq 1 45); do   # wait until every instance is healthy again
  ok=1; for a in "${APPS[@]}"; do [ "$(health "$a")" = "running/healthy" ] || ok=0; done
  [ $ok = 1 ] && break; sleep 2
done
kill $POLLER 2>/dev/null || true
status_line | tee -a "$OUT/a-timeline.txt"
total=0; for a in "${APPS[@]}"; do total=$((total + $(restarts "$a"))); done
for a in "${APPS[@]}"; do
  n=$(docker logs "$(c "$a")" 2>&1 | grep -c "Terminating due to java.lang.OutOfMemoryError" || true)
  echo "$a: OOM exits in log=$n restarts=$(restarts "$a") now=$(health "$a")"
done | tee "$OUT/a-result.txt"
grep -E "restarts=[1-9]" "$OUT/a-timeline.txt" | head -1 | sed 's/^/first restart seen: /' || true
[ "$total" -gt 0 ] && echo "A: PASS ($total restart(s), all healthy again)" || echo "A: OOM NOT TRIGGERED (no restart)"
fi

echo "=== B. hung instance -> unhealthy -> nginx routes around it"
start_apps   # default settings: normal heap, load shedding on
EVENT=$(new_event)
k6_run 100 5s b-warmup
# SIGSTOP freezes the JVM while the container keeps running, so the healthcheck really runs and
# times out. (`docker pause` would freeze the whole container, and Docker reports a paused
# container as unhealthy at once, which would not test the healthcheck at all.)
docker kill -s STOP "$(c app2)" > /dev/null
paused_at=$(date +%s)
echo "[$(ts)] app2 JVM frozen (SIGSTOP)" | tee "$OUT/b-timeline.txt"
k6_run 100 40s b-load &
K6PID=$!
unhealthy_after=""
for _ in $(seq 1 40); do
  h=$(health app2); echo "[$(ts)] app2=$h" >> "$OUT/b-timeline.txt"
  if [ -z "$unhealthy_after" ] && [[ "$h" == */unhealthy ]]; then unhealthy_after=$(( $(date +%s) - paused_at )); fi
  sleep 1
done
wait $K6PID
docker kill -s CONT "$(c app2)" > /dev/null
echo "app2 marked unhealthy after: ${unhealthy_after:-never}s" | tee "$OUT/b-result.txt"
echo "k6 (B, 100 users, 40 s with app2 frozen): $(k6_counts b-load)" | tee -a "$OUT/b-result.txt"
for _ in $(seq 1 30); do [ "$(health app2)" = "running/healthy" ] && break; sleep 1; done
echo "[$(ts)] after SIGCONT: app2=$(health app2)" | tee -a "$OUT/b-result.txt"
grep -E "proxy|upstream" "$OUT/b-nginx.log" > /dev/null 2>&1 || true
docker logs --since "${paused_at}" "$(c nginx)" 2>&1 | grep -oE "upstream timed out|no live upstreams|connect\(\) failed" \
  | sort | uniq -c | sed 's/^/nginx: /' | tee -a "$OUT/b-result.txt" || true
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null
