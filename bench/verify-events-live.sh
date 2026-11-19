#!/usr/bin/env bash
# Live multi-process check of the outbox: 3 app containers, each running a relay AND a Kafka
# consumer, against a real Kafka. 2,000 holds, 1,000 confirmed -> 3,000 events. Afterwards every
# outbox row must be published, every event must have produced exactly one notification, and no
# reservation's events may have arrived out of order.
#
#   bash bench/verify-events-live.sh
set -euo pipefail
cd "$(dirname "$0")/.."
HOLDS=2000
CONFIRMS=1000
PSQL="docker compose exec -T postgres psql -U reservation -d reservation -t -A"

./mvnw -B -ntp -q package -DskipTests
docker compose --profile bench build -q
docker compose up -d --wait postgres redis kafka > /dev/null 2>&1
# Start the apps first: Flyway migrations (incl. the outbox tables) run on application startup.
RESERVATION_STRATEGY=atomic EVENTS_ENABLED=true \
  docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
$PSQL -q -c "TRUNCATE reservations, stock, events, outbox, consumer_inbox, notifications RESTART IDENTITY" > /dev/null

EVENT=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' \
  -d "{\"name\":\"events-live\",\"stock\":$HOLDS,\"shards\":4}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "creating $HOLDS holds, then confirming $CONFIRMS of them, all through nginx..."
docker run --rm --network reservation-engine_default --user "$(id -u):$(id -g)" \
  -v "$PWD/bench/k6:/scripts:ro" -v /tmp:/results grafana/k6:1.3.0 run --quiet \
  -e BASE_URL=http://nginx:80 -e EVENT_ID="$EVENT" -e RUN_ID=events-live -e MODE=sellout \
  -e VUS=100 -e ITERATIONS=$HOLDS -e SUMMARY_FILE=/results/events-live.json /scripts/reserve.js > /dev/null
$PSQL -c "SELECT id FROM reservations WHERE event_id = $EVENT ORDER BY id LIMIT $CONFIRMS" \
  | xargs -P 50 -I{} curl -s -o /dev/null -X POST localhost:8000/reservations/{}/confirm

for i in $(seq 1 60); do
  pending=$($PSQL -c "SELECT count(*) FROM outbox WHERE published_at IS NULL")
  applied=$($PSQL -c "SELECT count(*) FROM notifications")
  [ "$pending" = "0" ] && [ "$applied" -ge $((HOLDS + CONFIRMS)) ] && break
  sleep 1
done

echo "outbox rows:            $($PSQL -c "SELECT count(*) FROM outbox")  (unpublished: $pending)"
echo "notifications applied:  $($PSQL -c "SELECT count(*) FROM notifications")"
echo "distinct messages:      $($PSQL -c "SELECT count(DISTINCT message_id) FROM notifications")"
echo "reservations whose notifications are not in outbox order: $($PSQL -c "
  SELECT count(*) FROM (
    SELECT reservation_id,
           bool_and(outbox_id >= coalesce(prev, -1)) AS ordered
    FROM (SELECT reservation_id, outbox_id, lag(outbox_id) OVER (PARTITION BY reservation_id ORDER BY id) AS prev
          FROM notifications) x
    GROUP BY reservation_id) y WHERE NOT ordered")"
for port in 8081 8082 8083; do
  m=$(curl -s localhost:$port/actuator/prometheus)
  pub=$(echo "$m" | awk '/^reservation_outbox_published_total/ {print int($2)}')
  app=$(echo "$m" | awk '/^reservation_consumer_applied_total/ {print int($2)}')
  dup=$(echo "$m" | awk '/^reservation_consumer_duplicates_total/ {print int($2)}')
  ooo=$(echo "$m" | awk '/^reservation_consumer_out_of_order_total/ {print int($2)}')
  echo "instance :$port relay published ${pub:-0}, consumer applied ${app:-0}, duplicates ${dup:-0}, out-of-order ${ooo:-0}"
done
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
