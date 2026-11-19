#!/usr/bin/env python3
"""
Benchmark driver: strategies x modes x concurrency levels -> bench/results/results.csv

For every run:
  1. reset state (TRUNCATE tables, FLUSHALL Redis) so runs do not affect each other
  2. create a fresh event (one hot stock row)
  3. scrape server metrics from all 3 instances (before)
  4. run k6 in a container on the compose network, against nginx
  5. scrape server metrics again (after) and diff
  6. read the event's stock and check the invariant (catches the naive strategy overselling)

Server metrics come from /actuator/prometheus on each instance:
  - reservation_optimistic_attempts_*  -> average / maximum retries per request
  - hikaricp_connections_acquire_*     -> time spent waiting for a DB connection

Only the Python standard library is used. Configure with environment variables:
  BENCH_STRATEGIES  (default naive,atomic,optimistic,pessimistic,redis)
  BENCH_VUS         (default 10,100,1000,5000)
  BENCH_MODES       (default contention,sellout)
  BENCH_DURATION    contention run length (default 20s)
  BENCH_WARMUP      warm-up length per strategy (default 10s)
  SELLOUT_STOCK     (default 1000)     SELLOUT_REQUESTS (default 20000)
"""

import csv
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "bench" / "results"
RAW = RESULTS / "raw"
CSV_PATH = RESULTS / "results.csv"

NGINX = "http://localhost:8000"
INSTANCES = ["http://localhost:8081", "http://localhost:8082", "http://localhost:8083"]
NETWORK = "reservation-engine_default"
K6_IMAGE = "grafana/k6:1.3.0"
APPS = ["app1", "app2", "app3"]

STRATEGIES = os.environ.get("BENCH_STRATEGIES", "naive,atomic,optimistic,pessimistic,redis").split(",")
VUS_LEVELS = [int(v) for v in os.environ.get("BENCH_VUS", "10,100,1000,5000").split(",")]
MODES = os.environ.get("BENCH_MODES", "contention,sellout").split(",")
DURATION = os.environ.get("BENCH_DURATION", "20s")
WARMUP = os.environ.get("BENCH_WARMUP", "10s")
SELLOUT_STOCK = int(os.environ.get("SELLOUT_STOCK", "1000"))
SELLOUT_REQUESTS = int(os.environ.get("SELLOUT_REQUESTS", "20000"))
CONTENTION_STOCK = 10_000_000  # never runs out during a run

# k6 runs in a container on the compose network and hits nginx by service name.
# Runs as the host user so it can write the summary into bench/results.
K6_DOCKER = [
    "docker", "run", "--rm", "--network", NETWORK, "--ulimit", "nofile=65535:65535",
    "--user", f"{os.getuid()}:{os.getgid()}",
    "-v", f"{ROOT / 'bench' / 'k6'}:/scripts:ro", "-v", f"{RESULTS}:/results",
    K6_IMAGE,
]

FIELDS = [
    "strategy", "mode", "vus", "duration_s", "requests", "rps", "reserved_per_s",
    "status_201", "status_409", "status_503", "status_other", "rate_503",
    "p50_ms", "p95_ms", "p99_ms", "max_ms",
    "avg_retries", "max_retries",
    "hikari_acquire_avg_ms", "hikari_timeouts",
    "total_stock", "db_reserved", "db_remaining", "oversold", "k6_201_equals_db",
]


# --- helpers -----------------------------------------------------------------

def sh(*cmd, env=None, check=True, quiet=True):
    result = subprocess.run(cmd, cwd=ROOT, env={**os.environ, **(env or {})},
                            stdout=subprocess.PIPE if quiet else None,
                            stderr=subprocess.STDOUT if quiet else None, text=True)
    if check and result.returncode != 0:
        sys.exit(f"command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}")
    return result


def http_json(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def start_strategy(strategy):
    log(f"starting 3 instances with RESERVATION_STRATEGY={strategy}")
    sh("docker", "compose", "--profile", "bench", "up", "-d", "--wait", "--force-recreate",
       *APPS, "nginx", env={"RESERVATION_STRATEGY": strategy})
    for base in INSTANCES:
        health = http_json("GET", f"{base}/actuator/health")
        assert health["status"] == "UP", health


def reset_state():
    sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "reservation", "-d", "reservation",
       "-q", "-c", "TRUNCATE reservations, stock, events RESTART IDENTITY")
    sh("docker", "compose", "exec", "-T", "redis", "redis-cli", "FLUSHALL")


def create_event(stock):
    return http_json("POST", f"{NGINX}/events", {"name": "bench", "stock": stock})["id"]


# --- server metrics ------------------------------------------------------------

METRIC_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([-+0-9.eE]+|NaN)$')


def scrape():
    """Sums each metric series over the three instances: {(name, labels): value}."""
    totals = {}
    for base in INSTANCES:
        with urllib.request.urlopen(f"{base}/actuator/prometheus", timeout=10) as resp:
            for line in resp.read().decode().splitlines():
                m = METRIC_LINE.match(line)
                if m and m.group(3) != "NaN":
                    key = (m.group(1), m.group(2) or "")
                    totals[key] = totals.get(key, 0.0) + float(m.group(3))
    return totals


def diff(after, before, name, label_filter=""):
    return sum(v - before.get(k, 0.0) for k, v in after.items() if k[0] == name and label_filter in k[1])


def retry_stats(before, after):
    """Average and maximum retries (= attempts - 1) per request, from the attempts histogram."""
    count = diff(after, before, "reservation_optimistic_attempts_count")
    if count == 0:
        return "", ""
    total = diff(after, before, "reservation_optimistic_attempts_sum")
    # Cumulative buckets: the max is the smallest bucket bound whose count already equals the total.
    buckets = {}
    for (name, labels), value in after.items():
        if name == "reservation_optimistic_attempts_bucket":
            le = re.search(r'le="([^"]+)"', labels).group(1)
            if le != "+Inf":
                buckets[float(le)] = buckets.get(float(le), 0.0) + value - before.get((name, labels), 0.0)
    max_attempts = next(le for le in sorted(buckets) if buckets[le] >= count)
    return round(total / count - 1, 3), int(max_attempts - 1)


def hikari_stats(before, after):
    count = diff(after, before, "hikaricp_connections_acquire_seconds_count")
    total = diff(after, before, "hikaricp_connections_acquire_seconds_sum")
    timeouts = diff(after, before, "hikaricp_connections_timeout_total")
    avg_ms = round(total / count * 1000, 3) if count else ""
    return avg_ms, int(timeouts)


# --- k6 --------------------------------------------------------------------------

def run_k6(mode, vus, event_id, run_id, summary_name):
    env = [
        "-e", "BASE_URL=http://nginx:80", "-e", f"EVENT_ID={event_id}", "-e", f"RUN_ID={run_id}",
        "-e", f"MODE={mode}", "-e", f"VUS={vus}", "-e", f"DURATION={DURATION}",
        "-e", f"ITERATIONS={SELLOUT_REQUESTS}", "-e", f"SUMMARY_FILE=/results/raw/{summary_name}",
    ]
    sh(*K6_DOCKER, "run", "--quiet", *env, "/scripts/reserve.js")
    with open(RAW / summary_name) as f:
        return json.load(f)


def metric(summary, name, stat="count"):
    return summary["metrics"].get(name, {}).get("values", {}).get(stat, 0)


def warm_up(strategy):
    log(f"warm-up {WARMUP} ({strategy})")
    reset_state()
    event_id = create_event(CONTENTION_STOCK)
    sh(*K6_DOCKER, "run", "--quiet", "-e", "BASE_URL=http://nginx:80", "-e", f"EVENT_ID={event_id}",
       "-e", "RUN_ID=warmup", "-e", "MODE=contention", "-e", "VUS=100", "-e", f"DURATION={WARMUP}",
       "-e", "SUMMARY_FILE=/results/raw/warmup.json", "/scripts/reserve.js")


def run_one(strategy, mode, vus):
    reset_state()
    stock = SELLOUT_STOCK if mode == "sellout" else CONTENTION_STOCK
    event_id = create_event(stock)
    run_id = f"{strategy}-{mode}-{vus}"

    before = scrape()
    summary = run_k6(mode, vus, event_id, run_id, f"{run_id}.json")
    after = scrape()
    snapshot = http_json("GET", f"{NGINX}/events/{event_id}/stock")

    duration_s = summary["state"]["testRunDurationMs"] / 1000
    s201, s409 = metric(summary, "status_201"), metric(summary, "status_409")
    s503, other = metric(summary, "status_503"), metric(summary, "status_other")
    requests = s201 + s409 + s503 + other
    avg_retries, max_retries = retry_stats(before, after)
    acquire_ms, timeouts = hikari_stats(before, after)
    latency = summary["metrics"]["http_req_duration"]["values"]

    return {
        "strategy": strategy, "mode": mode, "vus": vus,
        "duration_s": round(duration_s, 2), "requests": int(requests),
        "rps": round(requests / duration_s, 1), "reserved_per_s": round(s201 / duration_s, 1),
        "status_201": int(s201), "status_409": int(s409), "status_503": int(s503), "status_other": int(other),
        "rate_503": round(s503 / requests, 4) if requests else 0,
        "p50_ms": round(latency["med"], 2), "p95_ms": round(latency["p(95)"], 2),
        "p99_ms": round(latency["p(99)"], 2), "max_ms": round(latency["max"], 2),
        "avg_retries": avg_retries, "max_retries": max_retries,
        "hikari_acquire_avg_ms": acquire_ms, "hikari_timeouts": timeouts,
        "total_stock": snapshot["totalStock"], "db_reserved": snapshot["reserved"],
        "db_remaining": snapshot["remaining"],
        # > 0 means more reservations exist than units that left the stock counter.
        "oversold": snapshot["reserved"] + snapshot["remaining"] - snapshot["totalStock"],
        "k6_201_equals_db": int(s201) == snapshot["reserved"],
    }


def main():
    RAW.mkdir(parents=True, exist_ok=True)
    log(f"strategies={STRATEGIES} modes={MODES} vus={VUS_LEVELS} duration={DURATION}")
    with open(CSV_PATH, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for strategy in STRATEGIES:
            start_strategy(strategy)
            warm_up(strategy)
            for mode in MODES:
                for vus in VUS_LEVELS:
                    row = run_one(strategy, mode, vus)
                    writer.writerow(row)
                    f.flush()
                    log(f"{strategy:<11} {mode:<10} vus={vus:<5} rps={row['rps']:<8} "
                        f"reserved/s={row['reserved_per_s']:<8} p99={row['p99_ms']}ms "
                        f"503={row['status_503']} other={row['status_other']} "
                        f"retries(avg/max)={row['avg_retries']}/{row['max_retries']} "
                        f"acquire={row['hikari_acquire_avg_ms']}ms oversold={row['oversold']}")
    log(f"done -> {CSV_PATH}")


if __name__ == "__main__":
    main()
