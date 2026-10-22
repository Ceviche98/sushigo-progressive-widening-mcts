#!/usr/bin/env python3
"""
Validation and publication-figure generation script for the
Sushi Go! Progressive Widening MCTS & Heuristic Engineering repository.

Validates:
1. JSON agent configurations in `configs/`
2. Java source structure and Progressive Widening MCTS / Heuristic formulas in `src/main/java/`
3. Generates high-resolution figures in `docs/assets/`:
   - `tournament_win_rates.png` (Exact 3-player tournament results from stats.pdf Table 2)
   - `heuristic_ablation_comparison.png` (Progressive Widening growth & V2/V4/V5 heuristic trade-off)
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIGS_DIR = REPO_ROOT / "configs"
JAVA_DIR = REPO_ROOT / "src" / "main" / "java"
ASSETS_DIR = REPO_ROOT / "docs" / "assets"


# Exact Tournament Data from stats.pdf (Table 2: Sushi Go Tournament Results, 3-player games)
TOURNAMENT_DATA = [
    # Category, Matchup / Agent, Win Rate (%), Mean Score, Games
    ("RHEA vs Baseline", "rhea_B (vs BasicMCTS)", 36.7, 45.91, 2000),
    ("RHEA vs Baseline", "BasicMCTS (Overall)", 35.7, 45.87, 5000),
    ("RHEA vs Baseline", "rhea_A (vs BasicMCTS)", 29.4, 43.72, 2000),
    ("RHEA vs Baseline", "rhea_C (vs BasicMCTS)", 29.4, 43.54, 1000),
    ("RHEA-Only\n(A + B + C)", "rhea_B", 38.5, 46.52, 1000),
    ("RHEA-Only\n(A + B + C)", "rhea_A", 36.1, 45.41, 1000),
    ("RHEA-Only\n(A + B + C)", "rhea_C", 25.0, 43.77, 1000),
    ("Mixed Bracket 1\n(A + B + MCTS)", "rhea_B", 36.7, 45.91, 1000),
    ("Mixed Bracket 1\n(A + B + MCTS)", "BasicMCTS", 35.7, 45.87, 1000),
    ("Mixed Bracket 1\n(A + B + MCTS)", "rhea_A", 27.2, 44.20, 1000),
    ("Mixed Bracket 2\n(B + C + MCTS)", "BasicMCTS", 41.2, 45.89, 1000),
    ("Mixed Bracket 2\n(B + C + MCTS)", "rhea_B", 30.7, 44.96, 1000),
    ("Mixed Bracket 2\n(B + C + MCTS)", "rhea_C", 27.4, 44.03, 1000),
    ("Mixed Bracket 3\n(A + C + MCTS)", "BasicMCTS", 40.9, 45.91, 1000),
    ("Mixed Bracket 3\n(A + C + MCTS)", "rhea_A", 32.9, 44.40, 1000),
    ("Mixed Bracket 3\n(A + C + MCTS)", "rhea_C", 25.7, 43.09, 1000),
]


def validate_configs() -> list[str]:
    """Validate all JSON configuration files in `configs/`."""
    errors: list[str] = []

    expected_files = [
        "PW_Heuristics_Light_WINNER.json",
        "BasicMCTS_Heuristic_Light.json",
        "RHEA_B.json",
    ]
    for fname in expected_files:
        fpath = CONFIGS_DIR / fname
        if not fpath.exists():
            errors.append(f"Missing configuration file: {fpath}")
            continue
        try:
            data = json.loads(fpath.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"Invalid JSON in {fpath}: {exc}")
            continue

        if "class" not in data:
            errors.append(f"{fname}: missing 'class' field")
        if data.get("budgetType") != "BUDGET_TIME" or data.get("budget") != 100:
            errors.append(f"{fname}: expected 100ms BUDGET_TIME configuration")

        if fname == "PW_Heuristics_Light_WINNER.json":
            if data.get("class") != "players.PW_MCTS.PW_MCTSParams":
                errors.append(f"{fname}: unexpected class {data.get('class')}")
            if data.get("progressiveWideningC") != 2.0 or data.get("progressiveWideningAlpha") != 0.5:
                errors.append(f"{fname}: expected PW parameters C=2.0, alpha=0.5")
            if data.get("heuristic", {}).get("class") != "players.heuristics.SushiGoHeuristic_V5":
                errors.append(f"{fname}: expected heuristic SushiGoHeuristic_V5")

        elif fname == "BasicMCTS_Heuristic_Light.json":
            if data.get("class") != "players.basicMCTS.BasicMCTSParams":
                errors.append(f"{fname}: unexpected class {data.get('class')}")
            if data.get("heuristic", {}).get("class") != "players.heuristics.SushiGoHeuristic_V5":
                errors.append(f"{fname}: expected heuristic SushiGoHeuristic_V5")

        elif fname == "RHEA_B.json":
            if data.get("class") != "players.rhea.RHEAParams":
                errors.append(f"{fname}: unexpected class {data.get('class')}")
            if data.get("horizon") != 24 or data.get("populationSize") != 16:
                errors.append(f"{fname}: unexpected RHEA horizon/population parameters")

    return errors


def validate_java_sources() -> list[str]:
    """Validate Java source files, packages, and key algorithmic constructs."""
    errors: list[str] = []

    java_expectations = {
        JAVA_DIR / "players" / "PW_MCTS" / "PW_MCTSParams.java": [
            r"package\s+players\.PW_MCTS;",
            r"public\s+class\s+PW_MCTSParams\s+extends\s+PlayerParameters",
            r"progressiveWideningC",
            r"progressiveWideningAlpha",
        ],
        JAVA_DIR / "players" / "PW_MCTS" / "PW_MCTSPlayer.java": [
            r"package\s+players\.PW_MCTS;",
            r"public\s+class\s+PW_MCTSPlayer\s+extends\s+AbstractPlayer",
            r"new\s+PW_TreeNode",
            r"root\.mctsSearch\(\)",
            r"root\.bestAction\(\)",
        ],
        JAVA_DIR / "players" / "PW_MCTS" / "PW_TreeNode.java": [
            r"package\s+players\.PW_MCTS;",
            r"class\s+PW_TreeNode",
            r"Math\.pow\(cur\.nVisits,\s*params\.progressiveWideningAlpha\)",
            r"guidedExpand",
            r"ucb\(\)",
            r"rollOut\(\)",
        ],
        JAVA_DIR / "players" / "heuristics" / "SushiGoHeuristic_V2.java": [
            r"package\s+players\.heuristics;",
            r"public\s+class\s+SushiGoHeuristic_V2\s+implements\s+IStateHeuristic",
            r"hyperGeo",
            r"combinations",
            r"recalculateBaseWeights",
        ],
        JAVA_DIR / "players" / "heuristics" / "SushiGoHeuristic_V4.java": [
            r"package\s+players\.heuristics;",
            r"public\s+class\s+SushiGoHeuristic_V4\s+implements\s+IStateHeuristic",
            r"denialBonus",
            r"getPerfectInfoTurn",
        ],
        JAVA_DIR / "players" / "heuristics" / "SushiGoHeuristic_V5.java": [
            r"package\s+players\.heuristics;",
            r"public\s+class\s+SushiGoHeuristic_V5\s+implements\s+IStateHeuristic",
            r"WASABI_EARLY_VAL\s*=\s*4\.2",
            r"TEMPURA_VAL\s*=\s*1\.94",
            r"SASHIMI_VAL\s*=\s*1\.47",
        ],
    }

    for path, patterns in java_expectations.items():
        if not path.exists():
            errors.append(f"Missing Java source file: {path}")
            continue
        content = path.read_text(encoding="utf-8")
        for pat in patterns:
            if not re.search(pat, content):
                errors.append(f"{path.name}: missing expected pattern `{pat}`")

    return errors


def plot_tournament_results() -> Path:
    """Generate publication-grade visualization of Table 2 from stats.pdf."""
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = ASSETS_DIR / "tournament_win_rates.png"

    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "font.size": 10,
        "axes.titlesize": 12,
        "axes.titleweight": "bold",
        "axes.labelsize": 10,
        "axes.labelweight": "bold",
        "figure.facecolor": "#FFFFFF",
        "axes.facecolor": "#F8FAFC",
        "axes.edgecolor": "#CBD5E1",
        "axes.grid": True,
        "grid.color": "#E2E8F0",
        "grid.linestyle": "--",
        "grid.alpha": 0.8,
    })

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15.5, 6.8), gridspec_kw={"width_ratios": [1.35, 1.0]})

    # Color mapping by agent family
    def get_color(agent_label: str) -> str:
        if agent_label.startswith("BasicMCTS"):
            return "#1D4ED8"  # Deep Royal Blue
        if agent_label.startswith("rhea_B"):
            return "#059669"  # Emerald Green
        if agent_label.startswith("rhea_A"):
            return "#D97706"  # Amber Gold
        return "#64748B"      # Slate Grey (rhea_C)

    # Panel A: Horizontal Bar Chart grouped by Tournament Bracket
    labels = [f"{cat.replace(chr(10), ' ')} | {agent}" for cat, agent, _, _, _ in TOURNAMENT_DATA]
    win_rates = [wr for _, _, wr, _, _ in TOURNAMENT_DATA]
    scores = [sc for _, _, _, sc, _ in TOURNAMENT_DATA]
    colors = [get_color(agent) for _, agent, _, _, _ in TOURNAMENT_DATA]

    y_pos = np.arange(len(labels))
    bars = ax1.barh(y_pos, win_rates, color=colors, height=0.68, edgecolor="#1E293B", linewidth=0.7, alpha=0.92)
    ax1.axvline(33.33, color="#DC2626", linestyle=":", linewidth=1.5, label="3-Player Uniform Baseline (33.3%)")

    # Add separator lines between brackets
    bracket_boundaries = [3.5, 6.5, 9.5, 12.5]
    for b in bracket_boundaries:
        ax1.axhline(b, color="#94A3B8", linestyle="-", linewidth=0.9, alpha=0.6)

    for bar, wr, sc in zip(bars, win_rates, scores):
        ax1.text(
            wr + 0.6,
            bar.get_y() + bar.get_height() / 2.0,
            f"{wr:.1f}%  ({sc:.2f} pts)",
            va="center",
            ha="left",
            fontsize=8.6,
            fontweight="semibold",
            color="#0F172A",
        )

    ax1.set_yticks(y_pos)
    ax1.set_yticklabels(labels, fontsize=8.8)
    ax1.invert_yaxis()
    ax1.set_xlim(0, 68.0)
    ax1.set_xlabel("Tournament Win Rate (%) [1,000 - 2,000 Games per Matchup]")
    ax1.set_title("A. 3-Player Sushi Go! Tournament Win Rates & Mean Scores (Table 2)")
    ax1.legend(loc="lower right", frameon=True, facecolor="white", framealpha=0.95)

    # Panel B: Mean Score vs Win Rate Scatter with Linear Trend
    for cat, agent, wr, sc, games in TOURNAMENT_DATA:
        c = get_color(agent)
        marker = "o"
        if agent.startswith("BasicMCTS"):
            marker = "s"
        elif agent.startswith("rhea_B"):
            marker = "D"
        elif agent.startswith("rhea_A"):
            marker = "^"

        ax2.scatter(
            sc,
            wr,
            s=110 if games == 1000 else 155,
            color=c,
            marker=marker,
            edgecolor="#0F172A",
            linewidth=1.0,
            alpha=0.9,
            zorder=4,
        )

    # Fit linear regression line between mean score and win rate
    slope, intercept = np.polyfit(scores, win_rates, 1)
    x_line = np.linspace(min(scores) - 0.3, max(scores) + 0.3, 100)
    r_corr = np.corrcoef(scores, win_rates)[0, 1]
    ax2.plot(
        x_line,
        slope * x_line + intercept,
        color="#475569",
        linestyle="--",
        linewidth=1.5,
        label=f"Score-Win Trend ($r = {r_corr:.2f}$)",
        zorder=2,
    )

    # Annotate key points
    annotations = [
        (45.89, 41.2, "BasicMCTS\n(B+C+MCTS: 41.2%)", (-65, 12)),
        (45.91, 40.9, "BasicMCTS\n(A+C+MCTS: 40.9%)", (12, -4)),
        (46.52, 38.5, "rhea_B\n(RHEA-Only: 38.5%, 46.52 pts)", (-85, -78)),
        (45.91, 36.7, "rhea_B\n(vs MCTS: 36.7%)", (-108, 12)),
        (43.09, 25.7, "rhea_C\n(25.7%, 43.09 pts)", (12, -8)),
    ]
    for x_pt, y_pt, text, offset in annotations:
        ax2.annotate(
            text,
            xy=(x_pt, y_pt),
            xytext=offset,
            textcoords="offset points",
            fontsize=8.3,
            fontweight="semibold",
            color="#1E293B",
            bbox=dict(boxstyle="round,pad=0.25", facecolor="white", edgecolor="#CBD5E1", alpha=0.9),
            arrowprops=dict(arrowstyle="->", connectionstyle="arc3,rad=0.1", color="#64748B", lw=0.9),
        )

    # Custom legend for agent families
    from matplotlib.lines import Line2D
    legend_elements = [
        Line2D([0], [0], marker="s", color="w", label="BasicMCTS (UCT Baseline)", markerfacecolor="#1D4ED8", markeredgecolor="#0F172A", markersize=8),
        Line2D([0], [0], marker="D", color="w", label="RHEA_B (Best Evolutionary Agent)", markerfacecolor="#059669", markeredgecolor="#0F172A", markersize=8),
        Line2D([0], [0], marker="^", color="w", label="RHEA_A (Evolutionary Variant A)", markerfacecolor="#D97706", markeredgecolor="#0F172A", markersize=8),
        Line2D([0], [0], marker="o", color="w", label="RHEA_C (Evolutionary Variant C)", markerfacecolor="#64748B", markeredgecolor="#0F172A", markersize=8),
        Line2D([0], [0], linestyle="--", color="#475569", label=f"Linear Fit ($r = {r_corr:.2f}$)"),
    ]
    ax2.axhline(33.33, color="#DC2626", linestyle=":", linewidth=1.2)
    ax2.set_xlabel("Mean End-of-Game Points (Score)")
    ax2.set_ylabel("Win Rate (%)")
    ax2.set_title("B. Mean Score vs. Win Rate Across Matchup Regimes")
    ax2.set_xlim(42.7, 47.0)
    ax2.set_ylim(22.0, 44.5)
    ax2.legend(handles=legend_elements, loc="upper left", fontsize=8.5, frameon=True, facecolor="white", framealpha=0.95)

    fig.suptitle(
        "Sushi Go! Online Statistical Planners Benchmark (TAG Framework, 3-Player, 100ms Budget)",
        fontsize=14,
        fontweight="bold",
        y=0.98,
    )
    plt.tight_layout(rect=[0, 0, 1, 0.94])
    fig.savefig(out_path, dpi=300)
    plt.close(fig)
    return out_path


def plot_heuristic_and_pw_analysis() -> Path:
    """Generate publication-grade visualization of Progressive Widening & Heuristic V2/V4/V5 design."""
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = ASSETS_DIR / "heuristic_ablation_comparison.png"

    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "font.size": 10,
        "axes.titlesize": 12,
        "axes.titleweight": "bold",
        "axes.labelsize": 10,
        "axes.labelweight": "bold",
        "figure.facecolor": "#FFFFFF",
        "axes.facecolor": "#F8FAFC",
        "axes.edgecolor": "#CBD5E1",
        "axes.grid": True,
        "grid.color": "#E2E8F0",
        "grid.linestyle": "--",
        "grid.alpha": 0.8,
    })

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15.5, 6.4))

    # Panel A: Progressive Widening Action Expansion Schedule k <= C * N^alpha
    visits = np.arange(1, 85)
    max_hand_actions = np.full_like(visits, 9, dtype=float)  # 3-player Round start: 9 cards (up to 36 with Chopsticks)
    pw_winner = np.minimum(9.0, np.floor(2.0 * np.power(visits, 0.5)))
    pw_default = np.minimum(9.0, np.floor(1.0 * np.power(visits, 0.5)))
    pw_conservative = np.minimum(9.0, np.floor(1.0 * np.power(visits, 0.25)))

    ax1.plot(visits, max_hand_actions, color="#DC2626", linestyle="--", linewidth=2.0, label="Standard UCT (Unthrottled: expands all 9 actions first)")
    ax1.step(visits, pw_winner, where="post", color="#059669", linewidth=2.6, label=r"PW_MCTS Winner ($C=2.0, \alpha=0.5$): $k \leq 2\sqrt{N}$")
    ax1.step(visits, pw_default, where="post", color="#1D4ED8", linewidth=2.2, label=r"PW_MCTS Default ($C=1.0, \alpha=0.5$): $k \leq \sqrt{N}$")
    ax1.step(visits, pw_conservative, where="post", color="#7C3AED", linewidth=2.0, label=r"PW_MCTS Narrow ($C=1.0, \alpha=0.25$): $k \leq N^{0.25}$")

    ax1.fill_between(visits, pw_winner, max_hand_actions, color="#059669", alpha=0.08, label="Pruned Low-Heuristic Branches (Rollout Savings)")

    ax1.annotate(
        "Guided Expansion:\nTop-3 heuristic actions\nexplored until N=4",
        xy=(4, 4),
        xytext=(22, 5.4),
        fontsize=8.8,
        fontweight="semibold",
        color="#065F46",
        bbox=dict(boxstyle="round,pad=0.3", facecolor="white", edgecolor="#059669", alpha=0.92),
        arrowprops=dict(arrowstyle="->", color="#059669", lw=1.2),
    )

    ax1.set_xlabel("Node Visit Count $N(s)$")
    ax1.set_ylabel("Max Expanded Children $k_{max}(s) = \\lfloor C \\cdot N(s)^\\alpha \\rfloor$")
    ax1.set_title("A. Progressive Widening Search Control ($k < C \\cdot N^\\alpha$)")
    ax1.set_xlim(1, 82)
    ax1.set_ylim(0, 10.2)
    ax1.set_yticks(range(0, 11))
    ax1.legend(loc="lower right", fontsize=8.5, frameon=True, facecolor="white", framealpha=0.95)

    # Panel B: Card Synergy Valuation & Heuristic Architecture Comparison (V2 vs V4 vs V5)
    turns = np.arange(0, 9)  # 9 turns in a 3-player round (0-indexed)
    # V2 (Heavy): Hypergeometric in turns 0-1, switches to Perfect Info at turn >= nPlayers-1 (turn 2)
    # Wasabi valuation decay across turns in V2/V4 vs V5:
    wasabi_v2_v4 = [4.2, 4.2, 4.2, 4.2, 0.1, 0.1, 0.1, 0.1, 0.1]  # Early turns <=3 (or perfect info if Nigiri in hands)
    wasabi_v5 = [4.2, 4.2, 4.2, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1]     # V5: 4.2 for turn <= 2, then 0.1
    chopsticks_v2_v4 = [1.5, 1.5, 2.25, 2.25, 2.25, 0.1, 0.1, 0.1, 0.1]  # 50% boost in turns 2,3,4
    chopsticks_v5 = [1.5, 1.5, 1.5, 1.5, 1.5, 0.1, 0.1, 0.1, 0.1]        # V5: 1.5 for turn <= 4, then 0.1
    dumpling_marginal = [1.0, 2.0, 3.0, 4.0, 5.0, 0.0, 0.0, 0.0, 0.0]    # Marginal gain per dumpling (1->3->6->10->15)

    ax2.plot(turns + 1, wasabi_v2_v4, "o--", color="#D97706", linewidth=2.0, markersize=6, label="Wasabi Potential (V2/V4: Dynamic/Turn $\\leq 4$)")
    ax2.plot(turns + 1, wasabi_v5, "s-", color="#B45309", linewidth=2.4, markersize=6, label="Wasabi Potential (V5 Light Winner: Turn $\\leq 3$)")
    ax2.plot(turns + 1, chopsticks_v2_v4, "^--", color="#2563EB", linewidth=2.0, markersize=6, label="Chopsticks Potential (V2/V4: Peak Window Boost 2.25)")
    ax2.plot(turns + 1, chopsticks_v5, "d-", color="#1D4ED8", linewidth=2.4, markersize=6, label="Chopsticks Potential (V5 Light Winner: Static 1.50)")

    # Highlight the Perfect Information Transition boundary in 3-player game (Turn 3 / index 2)
    ax2.axvline(3.0, color="#7C3AED", linestyle=":", linewidth=1.8, label="Perfect Information Horizon ($t = n_{players} = 3$)")
    ax2.axvspan(3.0, 9.0, color="#7C3AED", alpha=0.05)

    # Inset box summarizing V2 vs V4 vs V5 complexity
    summary_text = (
        "Heuristic Complexity vs. MCTS Rollout Speed (100ms Budget):\n"
        "• V2 (Heavy): Hypergeometric PMF C(N,n) + O(P·H) Hand Scan\n"
        "• V4 (Medium): Static Base + O(P·H) Perfect-Info & Denial (+5/+10)\n"
        "• V5 (Light - WINNER): O(1) Pure Static Lookups → Max MCTS Depth"
    )
    ax2.text(
        0.03,
        0.66,
        summary_text,
        transform=ax2.transAxes,
        fontsize=8.3,
        verticalalignment="top",
        bbox=dict(boxstyle="round,pad=0.4", facecolor="#FEFCE8", edgecolor="#CA8A04", alpha=0.95),
    )

    ax2.set_xlabel("Drafting Turn within Round (1 to 9, 3-Player Game)")
    ax2.set_ylabel("Heuristic Potential Valuation (Points)")
    ax2.set_title("B. Turn-Dependent Utility Card Valuation & Information Horizon")
    ax2.set_xticks(range(1, 10))
    ax2.set_ylim(-0.2, 5.0)
    ax2.legend(loc="upper right", fontsize=8.0, frameon=True, facecolor="white", framealpha=0.95)

    fig.suptitle(
        "Progressive Widening MCTS & Domain-Heuristic Engineering in Sushi Go!",
        fontsize=14,
        fontweight="bold",
        y=0.98,
    )
    plt.tight_layout(rect=[0, 0, 1, 0.94])
    fig.savefig(out_path, dpi=300)
    plt.close(fig)
    return out_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate Sushi Go MCTS configs/sources and generate plots.")
    parser.add_argument("--check", action="store_true", help="Run validation checks and verify plots exist.")
    args = parser.parse_args()

    errors = validate_configs() + validate_java_sources()
    if errors:
        for err in errors:
            print(f"[ERROR] {err}", file=sys.stderr)
        return 1

    p1 = plot_tournament_results()
    p2 = plot_heuristic_and_pw_analysis()

    if args.check:
        for p in (p1, p2):
            if not p.exists() or p.stat().st_size < 10_000:
                print(f"[ERROR] Generated plot missing or too small: {p}", file=sys.stderr)
                return 1

    print(f"[OK] Validated 3 JSON agent configs and 6 Java source files.")
    print(f"[OK] Generated {p1.relative_to(REPO_ROOT)} ({p1.stat().st_size:,} bytes)")
    print(f"[OK] Generated {p2.relative_to(REPO_ROOT)} ({p2.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
