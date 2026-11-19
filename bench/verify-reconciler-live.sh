#!/usr/bin/env bash
# Live check of the Redis/DB reconciler on three app containers: every instance tries every second,
# the Redis lock lets one of them work per round. A drift is injected into Redis (as if a process
# had crashed between DECR and commit) and must be corrected.
#
#   bash bench/verify-reconciler-live.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PSQL="docker compose exec -T postgres psql -U reservation -d reservation -t -A"
RCLI="docker compose exec -T redis redis-cli"

./mvnw -B -ntp -q package -DskipTests
docker compose --profile bench build -q 2>/dev/null
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1
RESERVATION_STRATEGY=redis EVENTS_ENABLED=false RECONCILER_ENABLED=true RECONCILER_INTERVAL=PT1S \
  docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
$PSQL -q -c "TRUNCATE reservations, stock, events, idempotency_keys, outbox, consumer_inbox, notifications RESTART IDENTITY" > /dev/null
$RCLI FLUSHALL > /dev/null

EVENT=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' \
  -d '{"name":"reconcile-live","stock":100}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
for i in $(seq 1 10); do
  curl -s -o /dev/null -X POST localhost:8000/events/$EVENT/reserve -H 'Content-Type: application/json' \
    -H "Idempotency-Key: live-$i" -d "{\"userId\":\"u$i\"}"
done
sleep 2
echo "before drift:  redis=$($RCLI GET stock:$EVENT)  db=$($PSQL -c "SELECT sum(qty) FROM stock WHERE event_id=$EVENT")"
$RCLI DECRBY stock:$EVENT 7 > /dev/null
echo "drift injected: redis=$($RCLI GET stock:$EVENT)"
sleep 3
echo "3 s later:     redis=$($RCLI GET stock:$EVENT)  db=$($PSQL -c "SELECT sum(qty) FROM stock WHERE event_id=$EVENT")"
echo "fence token issued so far: $($RCLI GET fence:redis-stock-reconciler)"
for port in 8081 8082 8083; do
  m=$(curl -s localhost:$port/actuator/prometheus)
  acq=$(echo "$m" | awk '/^reservation_reconciler_runs_total\{.*outcome="acquired"/ {print int($2)}')
  skp=$(echo "$m" | awk '/^reservation_reconciler_runs_total\{.*outcome="skipped"/ {print int($2)}')
  cor=$(echo "$m" | awk '/^reservation_reconciler_corrections_total/ {print int($2)}')
  echo "instance :$port rounds acquired ${acq:-0}, skipped ${skp:-0}, corrections ${cor:-0}"
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
