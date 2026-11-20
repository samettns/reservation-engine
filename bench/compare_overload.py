#!/usr/bin/env python3
"""
Stage 7a: one table per metric, one column per variant, from bench/results-overload/<variant>/results.csv.
Standard library only.   python3 bench/compare_overload.py [variant ...]
"""
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results-overload"
DEFAULT = ["before", "no-shed", "after-100", "after-20"]


def load(variant):
    path = ROOT / variant / "results.csv"
    if not path.exists():
        return None
    with path.open() as f:
        return {(r["strategy"], r["mode"]): r for r in csv.DictReader(f)}


def pct(r, field):
    return f"{100 * int(r[field]) / int(r['requests']):.1f}%" if int(r["requests"]) else "-"


def num(r, field):
    v = r.get(field)
    return f"{float(v):,.0f}" if v not in ("", None) else "-"


def dead(r):
    parts = []
    if r.get("unresponsive"):
        parts.append(f"yanıtsız: {r['unresponsive']}")
    if r.get("restarts") not in (None, "", "0"):
        parts.append(f"yeniden başladı: {r['restarts']}")
    return ", ".join(parts) or "-"


def unsold(r):
    return r["db_remaining"] if r["mode"] == "sellout" else "-"


METRICS = [
    ("Başarılı rezervasyon / sn", lambda r: r["reserved_per_s"]),
    ("Başarılı isteğin p99'u, ms (tek deneme)", lambda r: num(r, "p99_201_ms")),
    ("Başarılı isteğin p99'u, ms (ilk denemeden 201'e, Retry-After beklemeleri dahil)", lambda r: num(r, "p99_201_e2e_ms")),
    ("Sellout sonunda satılmadan kalan stok (1.000'den)", unsold),
    ("Toplam 503 sayısı (tekrar denemeler dahil)", lambda r: f"{int(r['status_503']):,}"),
    ("500 oranı", lambda r: pct(r, "status_500")),
    ("10 denemede vazgeçen istemci", lambda r: r.get("gave_up_after_503") or "0"),
    ("Ölen instance (yanıtsız kalan / OOM ile kapanıp yeniden başlayan)", dead),
]


def main(variants):
    data = {v: d for v in variants if (d := load(v)) is not None}
    names = list(data)
    keys = sorted(next(iter(data.values())), key=lambda k: (k[1], k[0]))
    out = []
    for title, fn in METRICS:
        out.append(f"**{title}**\n")
        out.append("| Strateji | Mod | " + " | ".join(names) + " |")
        out.append("|---|---|" + "---|" * len(names))
        for key in keys:
            cells = [fn(data[v][key]) if key in data[v] else "-" for v in names]
            out.append(f"| {key[0]} | {key[1]} | " + " | ".join(cells) + " |")
        out.append("")
    text = "\n".join(out)
    (ROOT / "comparison.md").write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main(sys.argv[1:] or DEFAULT)
