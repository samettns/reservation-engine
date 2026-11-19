#!/usr/bin/env python3
"""
Charts for the stock-sharding benchmark (stage 3): how throughput and lock waits change
with the number of stock shards, per strategy. Small multiples (one panel per concurrency
level) share one y-axis so panels can be compared directly. Reuses the theme of plot.py.
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import plot as base  # noqa: E402

plt = base.plt
RESULTS = Path(sys.argv[1] if len(sys.argv) > 1 else "bench/results-sharding")


def load():
    with open(RESULTS / "results.csv") as f:
        rows = list(csv.DictReader(f))
    data = defaultdict(lambda: defaultdict(lambda: defaultdict(dict)))  # [mode][vus][strategy][shards]
    for r in rows:
        data[r["mode"]][int(r["vus"])][r["strategy"]][int(r["shards"])] = r
    return rows, data


def panel_lines(ax, t, by_strategy, shard_levels, field, scale=1.0):
    xs = list(range(len(shard_levels)))
    for name in base.ORDER:
        points = by_strategy.get(name)
        if not points:
            continue
        ys = [base.num(points[s][field]) * scale if s in points and base.num(points[s][field]) is not None
              else None for s in shard_levels]
        ax.plot(xs, ys, color=t["series"][name], linewidth=2, marker=base.MARKERS[name], markersize=8,
                markeredgecolor=t["surface"], markeredgewidth=1.5, label=base.LABELS[name], zorder=3)
    ax.set_xticks(xs, [str(s) for s in shard_levels])
    ax.set_xlabel("Stock shards per event", color=t["ink2"], fontsize=9, labelpad=6)


def small_multiples(data, mode, field, title, subtitle, ylabel, name, theme, shard_levels):
    t = base.THEMES[theme]
    vus_levels = sorted(data[mode])
    fig, axes = base.new_figure(t, title, subtitle, ncols=len(vus_levels), width=11.5)
    top = 0
    for ax, vus in zip(axes, vus_levels):
        panel_lines(ax, t, data[mode][vus], shard_levels, field)
        ax.set_title(f"{base.fmt_int(vus)} concurrent users", color=t["ink"], fontsize=10, loc="left", pad=8)
        top = max(top, ax.get_ylim()[1])
    for ax in axes:  # one shared scale, so panels compare honestly
        ax.set_ylim(0, top)
        ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda v, _: base.fmt_int(v)))
    axes[0].set_ylabel(ylabel, color=t["ink2"], fontsize=9.5, labelpad=8)
    handles, labels = axes[0].get_legend_handles_labels()
    leg = fig.legend(handles, labels, loc="upper left", bbox_to_anchor=(0.012, 0.83), ncol=len(labels),
                     frameon=False, fontsize=8.5, handlelength=2.2, columnspacing=1.4)
    for text in leg.get_texts():
        text.set_color(t["ink2"])
    fig.subplots_adjust(left=0.07, right=0.98, top=0.66, bottom=0.13, wspace=0.18)
    fig.savefig(RESULTS / f"{name}{'' if theme == 'light' else '-dark'}.png", facecolor=fig.get_facecolor())
    plt.close(fig)


def summary_md(rows):
    order = {name: i for i, name in enumerate(base.ORDER)}
    lines = ["# Stock sharding benchmark (stage 3)", "",
             "> ⚠️ **Preliminary: single run per cell.** Final numbers (3 runs, mean ± std dev) come in stage 8.",
             "",
             "Wait columns: share of active PostgreSQL sessions waiting on a heavyweight `Lock` (row-lock",
             "queue = hot row), on `IO` (e.g. WAL flush at commit) or on an `LWLock` (e.g. queueing to write",
             "the WAL), sampled once per second from `pg_stat_activity`. The rest is running or waiting on",
             "the client. Short runs may have no sample (blank).", ""]
    for mode in ("contention", "sellout"):
        mode_rows = [r for r in rows if r["mode"] == mode]
        if not mode_rows:
            continue
        lines += [f"## {mode}", "",
                  "| strategy | shards | VUs | reserved/s | req/s | p99 ms | failed % | retries avg/max | active DB sessions | lock wait % | IO wait % | LWLock wait % | oversold |",
                  "|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|"]
        for r in sorted(mode_rows, key=lambda r: (order[r["strategy"]], int(r["vus"]), int(r["shards"]))):
            retries = f"{r['avg_retries']}/{r['max_retries']}" if r["avg_retries"] else "-"
            lines.append(f"| {r['strategy']} | {r['shards']} | {base.fmt_int(int(r['vus']))} | "
                         f"{float(r['reserved_per_s']):.0f} | {float(r['rps']):.0f} | {float(r['p99_ms']):.0f} | "
                         f"{base.failure_pct(r, None):.1f} | {retries} | {r['db_active_avg']} | "
                         f"{r['db_lock_wait_pct']} | {r['db_io_wait_pct']} | {r['db_lwlock_wait_pct']} | "
                         f"{r['oversold']} |")
        lines.append("")
    (RESULTS / "summary.md").write_text("\n".join(lines))


def main():
    rows, data = load()
    shard_levels = sorted({int(r["shards"]) for r in rows})
    for theme in base.THEMES:
        if "contention" in data:
            small_multiples(data, "contention", "reserved_per_s",
                            "Sharding: successful reservations per second",
                            "Stock never runs out. One panel per concurrency level; same y-scale in every panel.",
                            "Reservations / second", "sharding-contention", theme, shard_levels)
            small_multiples(data, "contention", "db_lock_wait_pct",
                            "Sharding: share of DB sessions waiting on a row lock",
                            "Sampled from pg_stat_activity once per second. High = requests queue on the same row.",
                            "Sessions waiting on a lock (%)", "sharding-lock-wait", theme, shard_levels)
        if "sellout" in data:
            small_multiples(data, "sellout", "rps",
                            "Sharding: flash sale, requests handled per second",
                            "1.000 units, 20.000 requests; most answers are 'sold out'.",
                            "Requests / second (all answers)", "sharding-sellout", theme, shard_levels)
    summary_md(rows)
    print("charts + summary.md written to", RESULTS)


if __name__ == "__main__":
    main()
