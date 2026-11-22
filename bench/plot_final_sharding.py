#!/usr/bin/env python3
"""
Final benchmark chart: 1 vs 16 shards, contention mode, mean +- standard deviation of the 3
repetitions, at 100 and 1,000 users -> docs/benchmarks/final/sharding-final{,-dark}.png.

Same data as summary.md (summarize_final.load, including a re-measured cell). Needs matplotlib:
    python3 bench/plot_final_sharding.py
Colors: categorical slots 1-2 of the reference palette, validated for both surfaces.
"""
import statistics
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyBboxPatch, Rectangle  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from summarize_final import load  # noqa: E402

OUT = Path(__file__).resolve().parent.parent / "docs" / "benchmarks" / "final"
STRATEGIES = [("atomic", "atomic"), ("optimistic", "optimistic"), ("pessimistic", "pessimistic"),
              ("pessimistic-precheck", "pessimistic\n+ pre-check")]
THEMES = {
    "light": dict(surface="#fcfcfb", ink="#0b0b0b", ink2="#52514e", muted="#898781", grid="#e1e0d9",
                  axis="#c3c2b7", s1="#2a78d6", s2="#eb6834"),
    "dark": dict(surface="#1a1a19", ink="#f0efec", ink2="#c3c2b7", muted="#898781", grid="#2c2c2a",
                 axis="#383835", s1="#3987e5", s2="#d95926"),
}
DPI = 200
BAR_PX = 16          # ~22 px thick at the README's full-width display (<= 24)
GAP_PX = 2           # surface gap between the two bars of a group
RADIUS_PX = 4        # rounded data end, square at the baseline


def cell(data, originals, strategy, shards, vus):
    if strategy == "pessimistic-precheck" and shards == 1:
        config = "B-precheck1"
    elif shards == 1:
        config = "A-strategies"
    else:
        config = "B-shards16"
    key = (config, strategy, "contention", shards, vus)
    values = data[key]["reserved_per_s"]
    return statistics.mean(values), statistics.stdev(values), key in originals


def rounded_bar(ax, x, width, height, color, surface_px_to_data):
    """Bar with a 4 px rounded top and a square base (patch rounded at both ends + square body)."""
    rx, ry = surface_px_to_data(RADIUS_PX)
    r_y = min(ry, height / 2)
    ax.add_patch(FancyBboxPatch((x, 0), width, height, boxstyle=f"round,pad=0,rounding_size={rx}",
                                mutation_aspect=r_y / rx, linewidth=0, facecolor=color, zorder=3))
    ax.add_patch(Rectangle((x, 0), width, height - r_y, linewidth=0, facecolor=color, zorder=3))


def draw(theme_name):
    t = THEMES[theme_name]
    data, originals = load()
    fig, axes = plt.subplots(1, 2, figsize=(6.4, 3.0), dpi=DPI, sharey=True)
    fig.patch.set_facecolor(t["surface"])
    plt.rcParams["font.family"] = ["Segoe UI", "DejaVu Sans", "sans-serif"]
    ymax = 2000
    for ax, vus, title in zip(axes, (100, 1000), ("100 concurrent users", "1,000 concurrent users")):
        ax.set_facecolor(t["surface"])
        ax.set_xlim(-0.6, len(STRATEGIES) - 0.4)
        ax.set_ylim(0, ymax)
        fig.canvas.draw()
        bbox = ax.get_window_extent()
        px_x = (ax.get_xlim()[1] - ax.get_xlim()[0]) / bbox.width     # data units per pixel
        px_y = (ax.get_ylim()[1] - ax.get_ylim()[0]) / bbox.height
        scale = DPI / 100                                             # "px" specs are at 100 % zoom
        to_data = lambda px: (px * scale * px_x, px * scale * px_y)   # noqa: E731
        bar_w = BAR_PX * scale * px_x
        gap = GAP_PX * scale * px_x
        for i, (strategy, _) in enumerate(STRATEGIES):
            m1, s1, _ = cell(data, originals, strategy, 1, vus)
            m16, s16, remeasured = cell(data, originals, strategy, 16, vus)
            x1, x16 = i - gap / 2 - bar_w, i + gap / 2
            rounded_bar(ax, x1, bar_w, m1, t["s1"], to_data)
            rounded_bar(ax, x16, bar_w, m16, t["s2"], to_data)
            for x, m, s in ((x1, m1, s1), (x16, m16, s16)):
                ax.errorbar(x + bar_w / 2, m, yerr=s, fmt="none", ecolor=t["ink2"], elinewidth=0.9,
                            capsize=2.2, capthick=0.9, zorder=4)
            label = f"{m16 / m1:.1f}×" + ("¹" if remeasured else "")
            ax.text(x16 + bar_w / 2, m16 + s16 + ymax * 0.025, label, ha="center", va="bottom",
                    fontsize=6.5, color=t["ink"], zorder=5)
        ax.set_title(title, fontsize=7.5, color=t["ink"], loc="left", pad=6)
        ax.set_xticks(range(len(STRATEGIES)), [name for _, name in STRATEGIES], fontsize=6, color=t["ink2"])
        ax.tick_params(axis="x", length=0, pad=4)
        ax.tick_params(axis="y", length=0, labelsize=6, labelcolor=t["muted"])
        ax.set_yticks([0, 500, 1000, 1500, 2000], ["0", "500", "1,000", "1,500", "2,000"])
        ax.grid(axis="y", color=t["grid"], linewidth=0.5, zorder=0)
        for side in ("top", "right", "left"):
            ax.spines[side].set_visible(False)
        ax.spines["bottom"].set_color(t["axis"])
        ax.spines["bottom"].set_linewidth(0.6)
    axes[0].set_ylabel("Successful reservations / s", fontsize=6.5, color=t["ink2"])
    handles = [Rectangle((0, 0), 1, 1, facecolor=t["s1"], linewidth=0),
               Rectangle((0, 0), 1, 1, facecolor=t["s2"], linewidth=0)]
    fig.legend(handles, ["1 shard (one hot row)", "16 shards"], loc="upper right", ncol=2, frameon=False,
               fontsize=6.5, labelcolor=t["ink"], handlelength=0.9, handleheight=0.9, bbox_to_anchor=(0.99, 1.0))
    fig.text(0.01, 0.01, "Contention mode, mean ± std of 3 runs. Labels: 16-shard / 1-shard throughput. "
             "¹ re-measured cell, see docs/benchmarks.md.", fontsize=5.8, color=t["muted"])
    fig.subplots_adjust(left=0.09, right=0.99, top=0.84, bottom=0.2, wspace=0.08)
    suffix = "" if theme_name == "light" else "-dark"
    path = OUT / f"sharding-final{suffix}.png"
    fig.savefig(path, facecolor=t["surface"])
    plt.close(fig)
    print("->", path)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        draw(theme)
