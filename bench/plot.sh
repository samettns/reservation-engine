#!/usr/bin/env bash
# Draws charts inside a container (no local Python packages needed).
#   bash bench/plot.sh                                         # stage 2 charts from bench/results
#   bash bench/plot.sh plot_sharding.py results-sharding       # stage 3 charts
set -euo pipefail
cd "$(dirname "$0")/.."
SCRIPT=${1:-plot.py}
DIR=${2:-results}
docker build -q -t reservation-bench-plot -f - bench > /dev/null <<'EOF'
FROM python:3.12-slim
RUN pip install --no-cache-dir matplotlib==3.9.2
EOF
docker run --rm --user "$(id -u):$(id -g)" -e MPLCONFIGDIR=/tmp \
  -v "$PWD/bench:/bench" reservation-bench-plot python "/bench/$SCRIPT" "/bench/$DIR"
