#!/usr/bin/env python3
"""Stage 7a: before / no-shed / after table from bench/results-overload/*/results.csv (standard library only)."""
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results-overload"
VARIANTS = ["before", "no-shed", "after"]


def load(variant):
    path = ROOT / variant / "results.csv"
    with path.open() as f:
        return {(r["strategy"], r["mode"]): r for r in csv.DictReader(f)}


def pct(part, whole):
    return f"{100 * int(part) / int(whole):.1f}%" if int(whole) else "-"


def ms(value):
    return f"{float(value):,.0f}" if value not in ("", None) else "-"


def main():
    data = {v: load(v) for v in VARIANTS}
    keys = sorted(data["after"], key=lambda k: (k[1], k[0]))
    lines = [
        "| Strateji | Mod | 500 oranı (önce → shed'siz → sonra) | 503 oranı (önce → shed'siz → sonra) "
        "| Başarılı p99 ms (önce → shed'siz → sonra) | rezervasyon/sn (önce → sonra) |",
        "|---|---|---|---|---|---|",
    ]
    for key in keys:
        rows = [data[v].get(key) for v in VARIANTS]
        if any(r is None for r in rows):
            continue
        r500 = " → ".join(pct(r["status_500"], r["requests"]) for r in rows)
        others = [int(r["status_other"]) for r in rows]
        if any(others):
            print(f"note: {key} has other statuses (502/504/timeouts): {others}", file=sys.stderr)
        r503 = " → ".join(pct(r["status_503"], r["requests"]) for r in rows)
        p99 = " → ".join(ms(r["p99_201_ms"]) for r in rows)
        rps = f"{rows[0]['reserved_per_s']} → {rows[2]['reserved_per_s']}"
        lines.append(f"| {key[0]} | {key[1]} | {r500} | {r503} | {p99} | {rps} |")
    table = "\n".join(lines)
    (ROOT / "comparison.md").write_text(table + "\n")
    print(table)


if __name__ == "__main__":
    sys.exit(main())
