#!/usr/bin/env bash
# Does the WSL VM give memory back to Windows after a run (.wslconfig autoMemoryReclaim=gradual)?
# One 5,000-user run (bench/measure-memory.sh, no cache dropping), then vmmem as Windows sees it and
# WSL's own page cache, every 30 s for 10 minutes of idleness.
#   bash bench/measure-reclaim.sh      -> bench/results-memory/reclaim.csv
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=bench/results-memory; mkdir -p "$OUT"
vm() { powershell.exe -NoProfile -Command \
  "(Get-Process -Name vmmem,vmmemWSL -ErrorAction SilentlyContinue | Measure-Object WorkingSet64 -Sum).Sum" | tr -d '\r'; }
line() { echo "$1,$(( $(vm) / 1048576 )),$(free -m | awk '/^Mem:/ {print $3","$6}')"; }

echo "phase,vmmem_mib,wsl_used_mib,wsl_cache_mib" > "$OUT/reclaim.csv"
line before-run >> "$OUT/reclaim.csv"
bash bench/measure-memory.sh reclaim-run 2>&1 | grep -E "vmmem|wsl-used|k6 exit"
line after-run >> "$OUT/reclaim.csv"
for i in $(seq 1 20); do sleep 30; line "idle+$((i * 30))s" >> "$OUT/reclaim.csv"; done
column -t -s, "$OUT/reclaim.csv"
