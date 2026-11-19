#!/usr/bin/env python3
"""
Draws the benchmark charts and a markdown summary from bench/results/results.csv.

Every chart is rendered twice (light and dark) so the README can show the right one
for the reader's GitHub theme. Colours follow the entity (a strategy keeps its colour
in every chart); the categorical order was checked with the dataviz palette validator.
The naive strategy is not a valid option, so it is drawn as a grey dashed reference line.
"""

import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

RESULTS = Path(sys.argv[1] if len(sys.argv) > 1 else "bench/results")

THEMES = {
    "light": {
        "surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "muted": "#898781",
        "grid": "#e1e0d9", "axis": "#c3c2b7", "reference": "#898781",
        "series": {"atomic": "#2a78d6", "optimistic": "#eb6834", "pessimistic": "#1baf7a", "redis": "#eda100",
                   # violet, not magenta: magenta next to aqua (pessimistic) fails the CVD check in dark mode
                   "pessimistic-precheck": "#6250d6"},
    },
    "dark": {
        "surface": "#1a1a19", "ink": "#f0efec", "ink2": "#c3c2b7", "muted": "#898781",
        "grid": "#2c2c2a", "axis": "#383835", "reference": "#898781",
        "series": {"atomic": "#3987e5", "optimistic": "#d95926", "pessimistic": "#199e70", "redis": "#c98500",
                   "pessimistic-precheck": "#9085e9"},
    },
}

ORDER = ["atomic", "optimistic", "pessimistic", "pessimistic-precheck", "redis", "naive"]
LABELS = {
    "atomic": "atomic",
    "optimistic": "optimistic",
    "pessimistic": "pessimistic",
    "pessimistic-precheck": "pessimistic + pre-check",
    "redis": "redis (+ PostgreSQL)",
    "naive": "naive (wrong: oversells)",
}
MARKERS = {"atomic": "o", "optimistic": "s", "pessimistic": "^", "pessimistic-precheck": "v",
           "redis": "D", "naive": "x"}


def load():
    with open(RESULTS / "results.csv") as f:
        rows = list(csv.DictReader(f))
    data = defaultdict(lambda: defaultdict(dict))  # data[mode][strategy][vus] = row
    for r in rows:
        data[r["mode"]][r["strategy"]][int(r["vus"])] = r
    return rows, data


def num(value):
    return float(value) if value not in ("", None) else None


def fmt_int(n):
    return f"{int(round(n)):,}".replace(",", ".")


def style_axes(ax, t):
    ax.set_facecolor(t["surface"])
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(t["axis"])
        ax.spines[side].set_linewidth(1)
    ax.tick_params(colors=t["muted"], labelsize=9, length=0, pad=6)
    ax.yaxis.grid(True, color=t["grid"], linewidth=0.8)
    ax.xaxis.grid(False)
    ax.set_axisbelow(True)


SETUP = "Setup: 3 app instances behind nginx, PostgreSQL 17, one laptop (16 threads), k6 closed model."


def new_figure(t, title, subtitle, ncols=1, width=8.6, setup=True):
    fig, axes = plt.subplots(1, ncols, figsize=(width, 4.9), dpi=160, squeeze=False)
    fig.patch.set_facecolor(t["surface"])
    fig.text(0.012, 0.968, title, ha="left", va="top", fontsize=13, fontweight="bold", color=t["ink"])
    fig.text(0.012, 0.910, subtitle, ha="left", va="top", fontsize=9, color=t["ink2"])
    if setup:
        fig.text(0.012, 0.868, SETUP, ha="left", va="top", fontsize=8, color=t["muted"])
    for ax in axes[0]:
        style_axes(ax, t)
    return fig, axes[0]


def plot_lines(ax, t, series_by_strategy, levels, log_y=False):
    ends = []
    xs = list(range(len(levels)))
    for name in ORDER:
        points = series_by_strategy.get(name)
        if not points:
            continue
        ys = [points.get(v) for v in levels]
        if all(y is None for y in ys):
            continue
        is_ref = name == "naive"
        color = t["reference"] if is_ref else t["series"][name]
        ax.plot(xs, ys, color=color, linewidth=2, linestyle=(0, (4, 3)) if is_ref else "-",
                marker=MARKERS[name], markersize=8 if not is_ref else 7, markeredgewidth=1.5 if not is_ref else 2,
                markeredgecolor=t["surface"] if not is_ref else color, label=LABELS[name], zorder=3)
        last = next((y for y in reversed(ys) if y is not None), None)
        if last is not None:
            ends.append((name, last))
    ax.set_xticks(xs, [fmt_int(v) for v in levels])
    if log_y:
        ax.set_yscale("log")
    return ends


def finish(fig, ax, t, ends, xlabel, ylabel, log_y=False, legend=True):
    ax.set_xlabel(xlabel, color=t["ink2"], fontsize=9.5, labelpad=8)
    ax.set_ylabel(ylabel, color=t["ink2"], fontsize=9.5, labelpad=8)
    if not log_y:
        ax.set_ylim(bottom=0)
    ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda v, _: fmt_int(v) if v >= 1 or v == 0 else f"{v:g}"))
    if legend:
        # Five series: the legend carries identity (plus a distinct marker shape per strategy,
        # so colour is never the only cue). The summary.md table is the accessible table view.
        leg = ax.legend(loc="lower left", bbox_to_anchor=(-0.01, 1.01), ncol=5, frameon=False, fontsize=8.5,
                        handlelength=2.2, columnspacing=1.4)
        for text in leg.get_texts():
            text.set_color(t["ink2"])


def save(fig, name, theme):
    suffix = "" if theme == "light" else "-dark"
    fig.subplots_adjust(left=0.09, right=0.97, top=0.72, bottom=0.13, wspace=0.35)
    fig.savefig(RESULTS / f"{name}{suffix}.png", facecolor=fig.get_facecolor())
    plt.close(fig)


def series(data, mode, field, transform=lambda r, v: v):
    out = {}
    for strategy, by_vus in data[mode].items():
        out[strategy] = {}
        for vus, row in by_vus.items():
            v = num(row[field])
            out[strategy][vus] = transform(row, v) if v is not None else None
    return out


def failure_pct(row, _):
    requests = int(row["requests"])
    failed = int(row["status_other"]) + int(row["status_503"])
    return 100.0 * failed / requests if requests else 0.0


def charts(data, levels, theme):
    t = THEMES[theme]

    if "contention" in data:
        fig, (ax,) = new_figure(t, "Successful reservations per second on one hot row",
                                "Stock never runs out; every request competes for the same row.")
        ends = plot_lines(ax, t, series(data, "contention", "reserved_per_s"), levels)
        finish(fig, ax, t, ends, "Concurrent virtual users", "Reservations / second")
        save(fig, "contention-throughput", theme)

        fig, (ax,) = new_figure(t, "p99 latency under contention (log scale)",
                                "99% of reservation requests finished faster than this.")
        ends = plot_lines(ax, t, series(data, "contention", "p99_ms"), levels, log_y=True)
        finish(fig, ax, t, ends, "Concurrent virtual users", "p99 latency (ms, log)", log_y=True)
        save(fig, "contention-p99", theme)

        fig, (ax,) = new_figure(t, "Failed requests under contention",
                                "Share of requests answered 500 (DB connection pool timeout) or 503 (optimistic retries exhausted).")
        ends = plot_lines(ax, t, series(data, "contention", "requests", failure_pct), levels)
        finish(fig, ax, t, ends, "Concurrent virtual users", "Failed requests (%)")
        save(fig, "contention-failures", theme)

    if "sellout" in data:
        fig, (ax,) = new_figure(t, "Flash sale: requests handled per second",
                                "1.000 units, 20.000 requests: most answers are 'sold out'.")
        ends = plot_lines(ax, t, series(data, "sellout", "rps"), levels)
        finish(fig, ax, t, ends, "Concurrent virtual users", "Requests / second (all answers)")
        save(fig, "sellout-throughput", theme)

    # Optimistic strategy only: retries (one unit) and 503 rate (another unit) -> two panels, never a dual axis.
    opt = {m: data[m].get("optimistic", {}) for m in data}
    if any(opt.values()):
        fig, (ax1, ax2) = new_figure(t, "Optimistic locking: retries and give-ups",
                                     "Retries = attempts - 1 per request (server-side histogram). Max attempts = 10, then 503.",
                                     ncols=2, width=9.4, setup=False)
        color = t["series"]["optimistic"]
        xs = list(range(len(levels)))
        for ax, field, ylabel in ((ax1, None, "Retries per request"), (ax2, "rate_503", "Answered 503 (%)")):
            for mode, dash, marker in (("contention", "-", "s"), ("sellout", (0, (4, 3)), "o")):
                rows = opt.get(mode, {})
                if not rows:
                    continue
                if field is None:
                    for stat, ls, lw in (("avg_retries", dash, 2), ("max_retries", dash, 1.2)):
                        ys = [num(rows[v][stat]) if v in rows else None for v in levels]
                        ax.plot(xs, ys, color=color, linestyle=ls, linewidth=lw, marker=marker,
                                markersize=8 if stat == "avg_retries" else 6, markeredgecolor=t["surface"],
                                markeredgewidth=1.5, zorder=3,
                                label=f"{mode}: {'average' if stat == 'avg_retries' else 'maximum'}")
                else:
                    ys = [100 * num(rows[v][field]) if v in rows else None for v in levels]
                    ax.plot(xs, ys, color=color, linestyle=dash, linewidth=2, marker=marker, markersize=8,
                            markeredgecolor=t["surface"], markeredgewidth=1.5, zorder=3, label=mode)
            ax.set_xticks(xs, [fmt_int(v) for v in levels])
            ax.set_xlabel("Concurrent virtual users", color=t["ink2"], fontsize=9.5, labelpad=8)
            ax.set_ylabel(ylabel, color=t["ink2"], fontsize=9.5, labelpad=8)
            ax.set_ylim(bottom=0)
            leg = ax.legend(frameon=False, fontsize=8, loc="upper left")
            for text in leg.get_texts():
                text.set_color(t["ink2"])
        fig.subplots_adjust(left=0.07, right=0.98, top=0.80, bottom=0.14, wspace=0.28)
        fig.savefig(RESULTS / f"optimistic-retries{'' if theme == 'light' else '-dark'}.png",
                    facecolor=fig.get_facecolor())
        plt.close(fig)


def summary_md(rows, levels):
    lines = ["# Benchmark results", "",
             "> ⚠️ **Preliminary: single run per cell.** The final benchmark (stage 8) repeats the main",
             "> configurations 3 times and reports mean ± standard deviation.",
             ">",
             "> **The 5,000-VU rows show failure behaviour, not capacity:** they are dominated by DB connection",
             "> pool timeouts (HTTP 500). Compare strategies on the 10-1,000 VU rows.",
             "",
             "> **`redis` = Redis + PostgreSQL.** The Redis strategy is a Lua gate in front of the same atomic",
             "> PostgreSQL UPDATE; every admitted request still commits to PostgreSQL, and every request still",
             "> does the idempotency lookup in PostgreSQL. These are not 'pure Redis' numbers.",
             "", "Columns: reservations/s (201 only), all requests/s, p99 latency, failed % (500 + 503),",
             "average/maximum optimistic retries, and `oversold` (> 0 means the invariant was broken).", ""]
    for mode in ("contention", "sellout"):
        mode_rows = [r for r in rows if r["mode"] == mode]
        if not mode_rows:
            continue
        lines += [f"## {mode}", "",
                  "| strategy | VUs | reserved/s | req/s | p99 ms | failed % | 500s | 503s | retries avg/max | DB acquire avg ms | oversold |",
                  "|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|"]
        for r in sorted(mode_rows, key=lambda r: (ORDER.index(r["strategy"]), int(r["vus"]))):
            failed = failure_pct(r, None)
            retries = f"{r['avg_retries']}/{r['max_retries']}" if r["avg_retries"] else "-"
            lines.append(f"| {r['strategy']} | {fmt_int(int(r['vus']))} | {float(r['reserved_per_s']):.0f} | "
                         f"{float(r['rps']):.0f} | {float(r['p99_ms']):.0f} | {failed:.1f} | {r['status_other']} | "
                         f"{r['status_503']} | {retries} | {r['hikari_acquire_avg_ms']} | {r['oversold']} |")
        lines.append("")
    (RESULTS / "summary.md").write_text("\n".join(lines))


def main():
    rows, data = load()
    levels = sorted({int(r["vus"]) for r in rows})
    for theme in THEMES:
        charts(data, levels, theme)
    summary_md(rows, levels)
    print("charts + summary.md written to", RESULTS)


if __name__ == "__main__":
    main()
