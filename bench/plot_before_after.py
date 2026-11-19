#!/usr/bin/env python3
"""
Stage 5b before/after comparison of the sellout scenario: same matrix, same machine, same session.
  before = code before stage 5b (no sold-out shortcuts)
  after  = Redis shortcut + versioned sold-out marker + idempotency claims

Usage: plot_before_after.py <results-dir> where <dir>/before.csv and <dir>/after.csv exist.
Colour = shard count (entity); line style = before (dashed) / after (solid).
"""

import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import plot as base  # noqa: E402

plt = base.plt
DIR = Path(sys.argv[1] if len(sys.argv) > 1 else "bench/results-sellout-5b")
STRATEGIES = ["atomic", "pessimistic-precheck", "redis"]
SHARD_COLOUR_SLOT = {1: "atomic", 16: "optimistic"}  # reuse validated slots 1 (blue) and 2 (orange)


def load(name):
    rows = {}
    with open(DIR / f"{name}.csv") as f:
        for r in csv.DictReader(f):
            if r["mode"] == "sellout":
                rows[(r["strategy"], int(r["shards"]), int(r["vus"]))] = r
    return rows


def chart(before, after, theme):
    t = base.THEMES[theme]
    vus_levels = sorted({k[2] for k in after})
    shard_levels = sorted({k[1] for k in after})
    fig, axes = base.new_figure(t, "Flash sale before / after the sold-out shortcuts (stage 5b)",
                                "Requests handled per second; 1.000 units, 20.000 requests. Dashed = before, solid = after.",
                                ncols=len(STRATEGIES), width=11.5)
    xs = list(range(len(vus_levels)))
    top = 0
    for ax, strategy in zip(axes, STRATEGIES):
        for shards in shard_levels:
            colour = t["series"][SHARD_COLOUR_SLOT[shards]]
            for label, data, style, marker in (("before", before, (0, (4, 3)), "o"), ("after", after, "-", "s")):
                ys = [float(data[(strategy, shards, v)]["rps"]) if (strategy, shards, v) in data else None
                      for v in vus_levels]
                ax.plot(xs, ys, color=colour, linestyle=style, linewidth=2, marker=marker, markersize=7,
                        markeredgecolor=t["surface"], markeredgewidth=1.5,
                        label=f"{shards} shard{'s' if shards > 1 else ''}, {label}", zorder=3)
        ax.set_title(base.LABELS[strategy], color=t["ink"], fontsize=10, loc="left", pad=8)
        ax.set_xticks(xs, [base.fmt_int(v) for v in vus_levels])
        ax.set_xlabel("Concurrent virtual users", color=t["ink2"], fontsize=9, labelpad=6)
        top = max(top, ax.get_ylim()[1])
    for ax in axes:
        ax.set_ylim(0, top)
        ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda v, _: base.fmt_int(v)))
    axes[0].set_ylabel("Requests / second (all answers)", color=t["ink2"], fontsize=9.5, labelpad=8)
    handles, labels = axes[0].get_legend_handles_labels()
    leg = fig.legend(handles, labels, loc="upper left", bbox_to_anchor=(0.012, 0.83), ncol=4, frameon=False,
                     fontsize=8.5, handlelength=2.6, columnspacing=1.4)
    for text in leg.get_texts():
        text.set_color(t["ink2"])
    fig.subplots_adjust(left=0.07, right=0.98, top=0.66, bottom=0.13, wspace=0.18)
    fig.savefig(DIR / f"sellout-before-after{'' if theme == 'light' else '-dark'}.png",
                facecolor=fig.get_facecolor())
    plt.close(fig)


def summary(before, after):
    lines = ["# Sellout before / after stage 5b", "",
             "> ⚠️ **Preliminary: single run per cell**, before and after measured back to back in one session.",
             "", "Requests/second (all answers) and p99, 1,000 units, 20,000 requests. `redis` = Redis + PostgreSQL.", "",
             "| strategy | shards | VUs | before req/s | after req/s | change | before p99 ms | after p99 ms | after unsold |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for key in sorted(after, key=lambda k: (STRATEGIES.index(k[0]), k[1], k[2])):
        a, b = after[key], before.get(key)
        if b is None:
            continue
        change = (float(a["rps"]) / float(b["rps"]) - 1) * 100
        lines.append(f"| {key[0]} | {key[1]} | {base.fmt_int(key[2])} | {float(b['rps']):.0f} | {float(a['rps']):.0f} | "
                     f"{change:+.0f}% | {float(b['p99_ms']):.0f} | {float(a['p99_ms']):.0f} | "
                     f"{a.get('unsold_after_sellout', '') or 0} |")
    (DIR / "summary.md").write_text("\n".join(lines) + "\n")


def main():
    before, after = load("before"), load("after")
    for theme in base.THEMES:
        chart(before, after, theme)
    summary(before, after)
    print("before/after chart + summary written to", DIR)


if __name__ == "__main__":
    main()
