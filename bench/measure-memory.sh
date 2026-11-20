#!/usr/bin/env bash
# Memory per component during one benchmark-like run: 5,000 users, contention, 30 s.
# Samples every 2 s: every container (k6 included), WSL's own view (free) and Windows' view of the
# WSL VM (vmmem / vmmemWSL working set). Prints the peak per component.
#
#   bash bench/measure-memory.sh [label] [extra k6 args...]
#   K6_MEM_LIMIT=2g bash bench/measure-memory.sh limited     (k6 container with a memory limit)
set -uo pipefail
cd "$(dirname "$0")/.."
LABEL=${1:-baseline}; shift || true
OUT=bench/results-memory; mkdir -p "$OUT"
SAMPLES="$OUT/$LABEL.csv"
echo "time,component,mib" > "$SAMPLES"

to_mib() { # "1.23GiB" / "456.7MiB" / "12kB" -> MiB
  awk -v v="$1" 'BEGIN { n = v + 0; u = v; gsub(/[0-9.]/, "", u);
    if (u == "GiB") n *= 1024; else if (u == "KiB" || u == "kB") n /= 1024; else if (u == "B") n /= 1048576;
    printf "%.0f", n }'
}
sample() {
  local t; t=$(date +%s)
  docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | while read -r name used _; do
    echo "$t,${name#reservation-engine-},$(to_mib "$used")"
  done
  echo "$t,wsl-used,$(free -m | awk '/^Mem:/ {print $3}')"
  echo "$t,wsl-buff-cache,$(free -m | awk '/^Mem:/ {print $6}')"
  local vm
  vm=$(powershell.exe -NoProfile -Command \
    "(Get-Process -Name vmmem,vmmemWSL -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum" 2>/dev/null | tr -d '\r')
  [ -n "$vm" ] && echo "$t,windows-vmmem,$(( vm / 1048576 ))"
}

docker compose stop kafka > /dev/null 2>&1 || true
docker compose up -d --wait postgres redis > /dev/null 2>&1
RESERVATION_STRATEGY=atomic EVENTS_ENABLED=false RECONCILER_ENABLED=false \
  docker compose --profile bench up -d --wait --force-recreate app1 app2 app3 nginx > /dev/null 2>&1
E=$(curl -s -X POST localhost:8000/events -H 'Content-Type: application/json' -d '{"name":"mem","stock":10000000}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

sample >> "$SAMPLES"   # idle baseline
( while true; do sample >> "$SAMPLES"; sleep 2; done ) &
SAMPLER=$!
docker run --rm --name k6-mem ${K6_MEM_LIMIT:+--memory "$K6_MEM_LIMIT"} --network reservation-engine_default \
  --user "$(id -u):$(id -g)" -v "$PWD/bench/k6:/scripts:ro" -v "$PWD/$OUT:/results" ${K6_ENV:-} grafana/k6:1.3.0 \
  run --quiet "$@" -e BASE_URL=http://nginx:80 -e EVENT_ID="$E" -e RUN_ID="mem-$LABEL-$RANDOM" -e MODE=contention \
  -e VUS=5000 -e DURATION=30s -e RETRY_503=1 -e SUMMARY_FILE="/results/$LABEL.json" /scripts/reserve.js > /dev/null 2>&1
echo "k6 exit code: $? (137 = killed by its memory limit)"
sleep 4; kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null
docker compose --profile bench stop app1 app2 app3 nginx > /dev/null 2>&1

python3 - "$SAMPLES" "$LABEL" <<'EOF'
import csv, sys, collections
first, peak = {}, collections.defaultdict(int)
for r in csv.DictReader(open(sys.argv[1])):
    v = int(r["mib"]); c = r["component"]
    first.setdefault(c, v); peak[c] = max(peak[c], v)
print(f"== {sys.argv[2]}: MiB, idle -> peak")
for c in sorted(peak, key=lambda c: -peak[c]):
    print(f"  {c:<16} {first.get(c, 0):>6} -> {peak[c]:>6}")
EOF
