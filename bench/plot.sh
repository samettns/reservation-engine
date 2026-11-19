#!/usr/bin/env bash
# Draws charts from bench/results/results.csv inside a container (no local Python packages needed).
set -euo pipefail
cd "$(dirname "$0")/.."
docker build -q -t reservation-bench-plot -f - bench > /dev/null <<'EOF'
FROM python:3.12-slim
RUN pip install --no-cache-dir matplotlib==3.9.2
EOF
docker run --rm --user "$(id -u):$(id -g)" -e MPLCONFIGDIR=/tmp \
  -v "$PWD/bench:/bench" reservation-bench-plot python /bench/plot.py /bench/results
