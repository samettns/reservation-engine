#!/usr/bin/env bash
# Live multi-process check of hold expiry: 3 app containers (each running its own sweeper),
# 3,000 holds that are never paid, a 3-second TTL. Afterwards every hold must be EXPIRED exactly
# once, all stock must be back, and the work must be visible in more than one instance's metrics.
#
#   bash bench/verify-expiry-live.sh
set -euo pipefail
cd "$(dirname "$0")/.."
HOLDS=3000

./mvnw -B -ntp -q package -DskipTests
docker compose --profile bench build -q
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1
HOLD_TTL=PT3S EXPIRY_INTERVAL=PT0.5S RESERVATION_STRATEGY=atomic \
  docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1

docker compose exec -T postgres psql -U reservation -d reservation -q \
  -c "TRUNCATE reservations, stock, events RESTART IDENTITY" > /dev/null
EVENT=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' \
  -d "{\"name\":\"expiry-live\",\"stock\":$HOLDS,\"shards\":4}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

echo "creating $HOLDS holds through nginx (never paid)..."
docker run --rm --network reservation-engine_default --user "$(id -u):$(id -g)" \
  -v "$PWD/bench/k6:/scripts:ro" -v /tmp:/results grafana/k6:1.3.0 run --quiet \
  -e BASE_URL=http://nginx:80 -e EVENT_ID="$EVENT" -e RUN_ID=expiry-live -e MODE=sellout \
  -e VUS=100 -e ITERATIONS=$HOLDS -e SUMMARY_FILE=/results/expiry-live.json /scripts/reserve.js > /dev/null
echo "right after:  $(curl -s localhost:8000/events/$EVENT/stock)"

sleep 8   # 3 s TTL + a few sweep intervals
echo "8 s later:    $(curl -s localhost:8000/events/$EVENT/stock)"
echo "shards:       $(docker compose exec -T postgres psql -U reservation -d reservation -t -A \
  -c "SELECT string_agg(qty::text, ',' ORDER BY shard_no) FROM stock WHERE event_id = $EVENT")"
for port in 8081 8082 8083; do
  n=$(curl -s localhost:$port/actuator/prometheus | awk '/^reservation_holds_expired_total/ {print int($2)}')
  echo "instance :$port expired ${n:-0} holds"
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
