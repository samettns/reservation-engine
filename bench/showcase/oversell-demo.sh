#!/usr/bin/env bash
# Overselling, side by side: 1,000 concurrent requests for 100 tickets, naive vs atomic strategy.
# Real run: 3 app instances behind nginx, k6, then the invariant read from PostgreSQL.
#   bash bench/showcase/oversell-demo.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PSQL="docker compose exec -T postgres psql -U reservation -d reservation -t -A"
OUT=bench/results-showcase; mkdir -p "$OUT"
K6=(docker run --rm --network reservation-engine_default --user "$(id -u):$(id -g)"
    -v "$PWD/bench/k6:/scripts:ro" -v "$PWD/$OUT:/results" grafana/k6:1.3.0)
docker compose up -d --wait postgres redis > /dev/null 2>&1

printf '%-9s %8s %10s %10s %10s %10s  %s\n' strategy tickets "HTTP 201" "DB holds" remaining oversold "invariant holds+remaining=tickets"
for s in naive atomic; do
  RESERVATION_STRATEGY=$s EVENTS_ENABLED=false RECONCILER_ENABLED=false \
    docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
  $PSQL -q -c "TRUNCATE reservations, stock, events, idempotency_keys, outbox, consumer_inbox, notifications RESTART IDENTITY" > /dev/null
  E=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' -d '{"name":"oversell","stock":100}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
  "${K6[@]}" run --quiet -e BASE_URL=http://nginx:80 -e EVENT_ID="$E" -e RUN_ID="show-$s-$RANDOM" -e MODE=sellout \
    -e VUS=1000 -e ITERATIONS=1000 -e SUMMARY_FILE="/results/oversell-$s.json" /scripts/reserve.js > /dev/null 2>&1 || true
  created=$(python3 -c "import json;print(int(json.load(open('$OUT/oversell-$s.json'))['metrics'].get('status_201',{}).get('values',{}).get('count',0)))")
  holds=$($PSQL -c "SELECT count(*) FROM reservations WHERE event_id = $E AND status IN ('HELD','CONFIRMED')")
  remaining=$($PSQL -c "SELECT COALESCE(SUM(qty),0) FROM stock WHERE event_id = $E")
  over=$((holds + remaining - 100))
  ok=$([ "$over" -eq 0 ] && echo "OK" || echo "VIOLATED: $holds + $remaining = $((holds + remaining))")
  printf '%-9s %8s %10s %10s %10s %10s  %s\n' "$s" 100 "$created" "$holds" "$remaining" "$over" "$ok"
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
