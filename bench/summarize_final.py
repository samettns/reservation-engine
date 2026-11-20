#!/usr/bin/env python3
"""
Stage 8: mean +- standard deviation over the repetitions in bench/results-final/<config>/rep*/,
one table per claim -> docs/benchmarks/final/summary.md (and printed). Standard library only.
"""
import csv
import os
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT.parent / os.environ.get("FINAL_OUT", "bench/results-final")
OUT = Path(os.environ.get("SUMMARY_OUT", ROOT.parent / "docs" / "benchmarks" / "final" / "summary.md"))

METRICS = [
    ("reserved_per_s", "rezervasyon/sn"),
    ("rps", "istek/sn"),
    ("p99_201_ms", "başarılı p99 ms"),
    ("p99_ms", "tüm istekler p99 ms"),
]


def load():
    data = defaultdict(lambda: defaultdict(list))  # (config, strategy, mode, shards, vus) -> metric -> values
    for csv_path in sorted(RESULTS.glob("*/rep*/results.csv")):
        config = csv_path.parent.parent.name
        for r in csv.DictReader(csv_path.open()):
            key = (config, r["strategy"], r["mode"], int(r["shards"]), int(r["vus"]))
            for field in [m for m, _ in METRICS] + ["status_503", "requests", "oversold", "gate_sold_out",
                                                    "gate_sold_out_db_had_stock"]:
                if r.get(field) not in (None, ""):
                    data[key][field].append(float(r[field]))
    return data


def fmt(values):
    if not values:
        return "-"
    mean = statistics.mean(values)
    sd = statistics.stdev(values) if len(values) > 1 else 0.0
    return f"{mean:,.0f} ± {sd:,.0f}" if mean >= 100 else f"{mean:,.1f} ± {sd:,.1f}"


def table(data, title, rows):
    lines = [f"### {title}", "",
             "| Konfigürasyon | Strateji | Mod | Shard | VU | n | " + " | ".join(label for _, label in METRICS) + " |",
             "|---|---|---|---|---|---|" + "---|" * len(METRICS)]
    for key in rows:
        if key not in data:
            continue
        d = data[key]
        n = len(d["reserved_per_s"])
        lines.append(f"| {key[0]} | {key[1]} | {key[2]} | {key[3]} | {key[4]} | {n} | "
                     + " | ".join(fmt(d[m]) for m, _ in METRICS) + " |")
    return "\n".join(lines) + "\n"


def main():
    data = load()
    keys = sorted(data)
    by_config = lambda *names: [k for k in keys if k[0] in names]
    oversold = [k for k in keys if any(v > 0 for v in data[k]["oversold"]) and k[1] != "naive"]
    out = ["# Final benchmark (Aşama 8)", "",
           "Ortalama ± standart sapma, n = tekrar sayısı. Aynı kod; her karşılaştırmada tek bir ayar farklı.",
           f"`naive` dışında fazla satış görülen koşu: {len(oversold)}.", ""]
    out.append(table(data, "A. Strateji karşılaştırması (1 shard)", by_config("A-strategies")))
    out.append(table(data, "B. 1 shard / 16 shard",
                     [k for k in keys if (k[0] == "A-strategies" and k[1] in
                                          ("atomic", "optimistic", "pessimistic"))
                      or k[0] in ("B-precheck1", "B-shards16")]))
    out.append(table(data, "C. \"Tükendi\" kısayolları açık / kapalı (sellout)",
                     [k for k in keys if k[2] == "sellout" and (
                         (k[0] in ("A-strategies", "B-shards16", "C-redis16-on") and k[1] in ("atomic", "pessimistic", "redis"))
                         or k[0] in ("C-shortcuts-off", "C-redis-verify-stale-only"))]))
    gate = [k for k in keys if k[1] == "redis" and k[2] == "sellout"]
    lines = ["### C-ek. Redis kapısında SOLD_OUT yolu (her zaman / sadece bayatken doğrula)", "",
             "| Konfigürasyon | Shard | VU | rezervasyon/sn | kapıda SOLD_OUT (istek) | DB'de stok vardı |",
             "|---|---|---|---|---|---|"]
    for k in gate:
        d = data[k]
        lines.append(f"| {k[0]} | {k[3]} | {k[4]} | {fmt(d['reserved_per_s'])} | {fmt(d['gate_sold_out'])} | "
                     f"{fmt(d['gate_sold_out_db_had_stock'])} |")
    out.append("\n".join(lines) + "\n")
    out.append(table(data, "D. Transaction sırası: stok önce (A) / stok en sonda",
                     [k for k in keys if k[1] in ("atomic", "pessimistic") and k[3] == 1
                      and k[0] in ("A-strategies", "D-stock-last")]))
    text = "\n".join(out)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
