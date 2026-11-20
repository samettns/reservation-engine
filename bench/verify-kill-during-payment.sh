#!/usr/bin/env bash
# Stage 7b: an instance is killed (SIGKILL) while payment callbacks are being confirmed.
# See bench/kill_during_payment.py for what is checked. Exit code 1 if a check fails.
#   bash bench/verify-kill-during-payment.sh [holds]      (~2 min)
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1
RESERVATION_STRATEGY=atomic EVENTS_ENABLED=false RECONCILER_ENABLED=false \
  docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
docker compose exec -T postgres psql -U reservation -d reservation -q -c \
  "TRUNCATE reservations, stock, events, idempotency_keys, outbox, consumer_inbox, notifications RESTART IDENTITY" > /dev/null
set +e
python3 bench/kill_during_payment.py "${1:-3000}"
status=$?
set -e
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
exit $status
