#!/usr/bin/env python3
"""
Payment callbacks while an instance is killed (stage 7b). Standard library only.

  1. HOLDS reservations are created through nginx (3 instances).
  2. A pool of payment-provider threads confirms all of them. Like a real provider it retries a
     callback that did not get an answer: connection error, 502, 503, 504 -> exponential backoff
     with full jitter, the SAME reservation id (confirm is idempotent).
  3. When 10% of the confirmations are done, app2 is killed with SIGKILL (no graceful shutdown:
     open transactions die with their connections).
  4. Checks (from PostgreSQL, the source of truth):
       - every hold is CONFIRMED (no payment lost),
       - exactly one ReservationConfirmed outbox event per reservation (none doubled),
       - held + confirmed + remaining = total stock (the invariant).
Exit code 1 if any check fails.
"""
import concurrent.futures
import json
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

NGINX = "http://localhost:8000"
HOLDS = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
VICTIM = "reservation-engine-app2-1"
RETRYABLE = {502, 503, 504}


def post(url, body=None, headers=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else b""
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read() or b"{}")


def psql(sql):
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "reservation", "-d",
                          "reservation", "-t", "-A", "-c", sql], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main():
    event = post(f"{NGINX}/events", {"name": "kill-during-payment", "stock": HOLDS})[1]["id"]
    with concurrent.futures.ThreadPoolExecutor(50) as pool:
        ids = list(pool.map(lambda i: post(f"{NGINX}/events/{event}/reserve", {"userId": f"payer-{i}"},
                                           {"Idempotency-Key": f"kill-{event}-{i}"})[1]["reservationId"],
                            range(HOLDS)))
    print(f"{HOLDS} holds created on event {event}")

    errors = Counter()
    statuses = Counter()
    done = [0]
    lock = threading.Lock()
    killed = threading.Event()

    def kill_when_ready():
        while not killed.is_set():
            with lock:
                if done[0] >= HOLDS // 10:
                    subprocess.run(["docker", "kill", "-s", "KILL", VICTIM], capture_output=True)
                    print(f"[{time.strftime('%H:%M:%S')}] app2 killed (SIGKILL) after {done[0]} confirmations")
                    killed.set()
                    return
            time.sleep(0.005)

    def confirm(reservation_id):
        for attempt in range(20):
            try:
                status, body = post(f"{NGINX}/reservations/{reservation_id}/confirm")
                with lock:
                    done[0] += 1
                    statuses[(status, body.get("status"))] += 1
                return
            except urllib.error.HTTPError as e:
                if e.code not in RETRYABLE:
                    with lock:
                        statuses[(e.code, None)] += 1
                        done[0] += 1
                    return
                errors[f"HTTP {e.code}"] += 1
            except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
                errors[type(getattr(e, "reason", e)).__name__] += 1
            time.sleep(random.uniform(0, min(3.0, 0.2 * 2 ** attempt)))   # backoff + full jitter
        with lock:
            statuses[("gave up", None)] += 1

    watcher = threading.Thread(target=kill_when_ready, daemon=True)
    watcher.start()
    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(50) as pool:
        list(pool.map(confirm, ids))
    print(f"confirm phase: {time.time() - start:.1f} s; final answers {dict(statuses)}; errors retried {dict(errors)}")

    restart = subprocess.run(["docker", "inspect", "-f", "{{.State.Status}} restarts={{.RestartCount}}", VICTIM],
                             capture_output=True, text=True).stdout.strip()
    print(f"app2 after the kill: {restart}")

    confirmed = int(psql(f"SELECT count(*) FROM reservations WHERE event_id = {event} AND status = 'CONFIRMED'"))
    per_res = psql(f"SELECT coalesce(max(n), 0) || ' ' || coalesce(sum(n), 0) FROM (SELECT count(*) n FROM outbox o "
                   f"JOIN reservations r ON r.id = o.aggregate_id WHERE r.event_id = {event} "
                   f"AND o.event_type = 'ReservationConfirmed' GROUP BY o.aggregate_id) x").split()
    max_events, total_events = int(per_res[0]), int(per_res[1])
    held, remaining, total = map(int, psql(
        f"SELECT (SELECT count(*) FROM reservations WHERE event_id = {event} AND status = 'HELD'), "
        f"(SELECT coalesce(sum(qty), 0) FROM stock WHERE event_id = {event}), "
        f"(SELECT total_stock FROM events WHERE id = {event})").split("|"))

    checks = [
        (killed.is_set(), "app2 was killed during the confirm phase"),
        (confirmed == HOLDS, f"every hold confirmed: {confirmed}/{HOLDS}"),
        (max_events == 1 and total_events == HOLDS, f"one ReservationConfirmed per reservation: max {max_events}, total {total_events}"),
        (held + confirmed + remaining == total, f"invariant held+confirmed+remaining=total: {held}+{confirmed}+{remaining}={total}"),
    ]
    for ok, text in checks:
        print(("PASS " if ok else "FAIL ") + text)
    sys.exit(0 if all(ok for ok, _ in checks) else 1)


if __name__ == "__main__":
    main()
