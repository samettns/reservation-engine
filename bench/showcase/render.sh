#!/usr/bin/env bash
# Builds every image in docs/showcase/ from REAL runs (nothing is edited by hand):
# terminal output is captured to docs/showcase/raw/*.txt and turned into PNG with freeze; the API
# flow is recorded with vhs (GIF); the architecture diagram is rendered with mermaid-cli.
#
#   bash bench/showcase/render.sh            (~12 min)
#   STEPS="api oversell" bash bench/showcase/render.sh
#
# Needs: freeze in ~/.local/bin, Docker (vhs + mermaid-cli images).
set -uo pipefail
cd "$(dirname "$0")/../.."
SHOW=docs/showcase; RAW=$SHOW/raw
mkdir -p "$RAW"
FREEZE=${FREEZE:-$HOME/.local/bin/freeze}
STEPS=${STEPS:-"build ps api oversell reconciler mutation tests failfast diagram"}
has() { [[ " $STEPS " == *" $1 "* ]]; }

# capture <name> <command...>: "$ command" header + real output -> raw/<name>.txt -> <name>.png
capture() {
  local name=$1; shift
  { echo "\$ $*"; echo; bash -c "$*" 2>&1 | sed -E 's/\x1b\[[0-9;]*m//g'; } > "$RAW/$name.txt"
  png "$name"
}
# freeze -> SVG, rsvg-convert -> PNG. (freeze's own PNG export segfaults here, and freeze reads
# stdin when it is not a terminal, so the text is piped in on purpose.)
png() {
  cat "$RAW/$1.txt" | "$FREEZE" -l text -o "/tmp/showcase-$1.svg" --window -t dracula > /dev/null || return 1
  docker run --rm -u "$(id -u):$(id -g)" -e XDG_CACHE_HOME=/tmp -v /tmp:/in -v "$PWD/$SHOW:/out" reservation-rsvg \
    rsvg-convert -z 1.5 -o "/out/$1.png" "/in/showcase-$1.svg" && echo "  -> $SHOW/$1.png"
}
apps() { # env... : (re)start the three instances + nginx with these settings
  env "$@" docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
}

if has build; then
  echo "== build"
  ./mvnw -B -ntp -q package -DskipTests && docker compose --profile bench build -q app1
  docker build -q -t reservation-showcase-vhs -f bench/showcase/vhs.Dockerfile bench/showcase > /dev/null
  docker build -q -t reservation-rsvg -f bench/showcase/rsvg.Dockerfile bench/showcase > /dev/null
fi

if has ps; then
  echo "== docker compose ps (3 instances + nginx + PostgreSQL + Redis + Kafka)"
  docker compose up -d --wait postgres redis kafka > /dev/null 2>&1
  apps RESERVATION_STRATEGY=atomic
  capture compose-ps "docker compose --profile bench ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'"
fi

if has api; then
  echo "== API flow GIF (hold TTL 15 s for the expiry step)"
  docker compose up -d --wait postgres redis > /dev/null 2>&1
  apps RESERVATION_STRATEGY=atomic HOLD_TTL=PT15S EXPIRY_INTERVAL=PT1S EVENTS_ENABLED=false RECONCILER_ENABLED=false
  # fresh state: the demo's idempotency keys (key-1...) must not exist from an earlier recording
  docker compose exec -T postgres psql -U reservation -d reservation -q -c \
    "TRUNCATE reservations, stock, events, idempotency_keys, outbox, consumer_inbox, notifications RESTART IDENTITY" > /dev/null
  docker compose exec -T redis redis-cli FLUSHALL > /dev/null
  docker run --rm --network host -v "$PWD:/vhs" -w /vhs reservation-showcase-vhs bench/showcase/api-flow.tape > /dev/null
  docker run --rm -v "$PWD/$SHOW:/out" alpine chown "$(id -u):$(id -g)" /out/api-flow.gif
  echo "  -> $SHOW/api-flow.gif"
fi

if has oversell; then
  echo "== overselling: naive vs atomic, 1,000 concurrent requests"
  capture oversell "bash bench/showcase/oversell-demo.sh"
fi

if has reconciler; then
  echo "== reconciler live check"
  capture reconciler "bash bench/verify-reconciler-live.sh"
fi

if has mutation; then
  echo "== mutation example (deadlock fix removed)"
  docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
  capture mutation-deadlock "bash bench/showcase/mutation-deadlock.sh"
fi

if has tests; then
  echo "== full test suite summary (from the last ./mvnw verify log)"
  LOGF=${VERIFY_LOG:-$HOME/verify-final.log}
  { echo "\$ ./mvnw -B -ntp verify"; echo "  ..."
    grep -E '^\[INFO\] Tests run: [0-9]+.* -- in ' "$LOGF" | sed -E 's/io\.github\.samettns\.reservation\.//'
    echo; grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$LOGF" | tail -1
    grep -E 'BUILD (SUCCESS|FAILURE)' "$LOGF" | tail -1; } > "$RAW/tests.txt"
  png tests
fi

if has failfast; then
  echo "== fail fast: OOM -> exit -> restart; frozen JVM -> unhealthy -> routed around"
  capture failfast "bash bench/verify-failfast-live.sh 2>&1 | grep -v ' Container '"
fi

if has png; then   # re-render every PNG from the captured raw output (no re-run)
  for f in "$RAW"/*.txt; do png "$(basename "$f" .txt)"; done
fi

if has diagram; then
  echo "== architecture diagram"
  docker run --rm -u "$(id -u):$(id -g)" -v "$PWD/docs:/data" minlag/mermaid-cli:11.4.0 \
    -i /data/architecture.mmd -o /data/showcase/architecture.png -b white -w 1600 > /dev/null 2>&1 \
    && echo "  -> $SHOW/architecture.png"
fi

docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1
docker compose stop kafka > /dev/null 2>&1
echo "done"
