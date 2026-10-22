"""
Unit tests verifying JSON agent configurations, Progressive Widening MCTS Java implementation,
domain heuristics (V2 Heavy, V4 Medium, V5 Light), and generated visual artifacts.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from scripts.validate_agents_and_plot import (
    ASSETS_DIR,
    CONFIGS_DIR,
    JAVA_DIR,
    TOURNAMENT_DATA,
    validate_configs,
    validate_java_sources,
)


def test_all_json_configs_valid():
    errors = validate_configs()
    assert errors == [], f"Config validation errors: {errors}"


def test_winner_pw_mcts_config_parameters():
    winner_cfg = json.loads((CONFIGS_DIR / "PW_Heuristics_Light_WINNER.json").read_text(encoding="utf-8"))
    assert winner_cfg["class"] == "players.PW_MCTS.PW_MCTSParams"
    assert winner_cfg["K"] == 1.0
    assert winner_cfg["rolloutLength"] == 30
    assert winner_cfg["maxTreeDepth"] == 99
    assert winner_cfg["budgetType"] == "BUDGET_TIME"
    assert winner_cfg["budget"] == 100
    assert winner_cfg["progressiveWideningC"] == 2.0
    assert winner_cfg["progressiveWideningAlpha"] == 0.5
    assert winner_cfg["heuristic"]["class"] == "players.heuristics.SushiGoHeuristic_V5"


def test_rhea_b_config_parameters():
    rhea_cfg = json.loads((CONFIGS_DIR / "RHEA_B.json").read_text(encoding="utf-8"))
    assert rhea_cfg["class"] == "players.rhea.RHEAParams"
    assert rhea_cfg["horizon"] == 24
    assert math.isclose(rhea_cfg["discountFactor"], 0.93)
    assert rhea_cfg["populationSize"] == 16
    assert rhea_cfg["eliteCount"] == 2
    assert rhea_cfg["childCount"] == 14
    assert rhea_cfg["selectionType"] == "TOURNAMENT"
    assert rhea_cfg["tournamentSize"] == 3
    assert rhea_cfg["crossoverType"] == "UNIFORM"
    assert rhea_cfg["shiftLeft"] is True
    assert rhea_cfg["mutationCount"] == 3
    assert rhea_cfg["useMAST"] is True
    assert rhea_cfg["seedFromHeuristic"] == 10


def test_java_sources_structure_and_formulas():
    errors = validate_java_sources()
    assert errors == [], f"Java source validation errors: {errors}"


def test_tournament_table_statistics():
    assert len(TOURNAMENT_DATA) == 16
    # Verify key win rates from stats.pdf Table 2
    by_key = {(cat, agent): (wr, sc, g) for cat, agent, wr, sc, g in TOURNAMENT_DATA}
    assert by_key[("RHEA vs Baseline", "rhea_B (vs BasicMCTS)")] == (36.7, 45.91, 2000)
    assert by_key[("RHEA-Only\n(A + B + C)", "rhea_B")] == (38.5, 46.52, 1000)
    assert by_key[("Mixed Bracket 2\n(B + C + MCTS)", "BasicMCTS")] == (41.2, 45.89, 1000)
    assert by_key[("Mixed Bracket 3\n(A + C + MCTS)", "BasicMCTS")] == (40.9, 45.91, 1000)


def test_generated_assets_exist():
    for fname in ("tournament_win_rates.png", "heuristic_ablation_comparison.png"):
        fpath = ASSETS_DIR / fname
        assert fpath.exists(), f"Missing asset {fpath}"
        assert fpath.stat().st_size > 20_000, f"Asset {fpath} is unexpectedly small"
