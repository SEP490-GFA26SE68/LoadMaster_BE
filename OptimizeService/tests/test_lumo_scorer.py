from __future__ import annotations

import uuid

import pytest

from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, VehicleData
from app.ml.lumo_features import candidate_features, global_features, placed_features
from app.ml.lumo_scorer import uses_lumo
from app.service.optimize.optimization_client import OptimizationClient

torch = pytest.importorskip("torch")

from app.ml.lumo_scorer import LumoScorer  # noqa: E402
from app.ml.pct_policy import PCTPolicy  # noqa: E402


def test_uses_lumo_only_for_ultimate_algorithm():
    assert uses_lumo("EP_DBLF_GA_AI") is True
    assert uses_lumo("AI") is True
    assert uses_lumo("EP_DBLF") is False
    assert uses_lumo(None) is False


def test_feature_widths():
    vehicle = VehicleData(inner_l=1000, inner_w=240, inner_h=240, max_payload_kg=5000)
    pkg = PackageData(id="p1", l=100, w=80, h=60, weight=50, allowed_rotations=[0])
    placed = PlacementData(
        package_id="p1",
        x=0,
        y=0,
        z=0,
        packed_l=100,
        packed_w=80,
        packed_h=60,
        rotation_type=0,
        step_sequence=1,
        stop_zone_id="s1",
    )
    assert len(placed_features(placed, vehicle, pkg)) == 10
    assert len(candidate_features(placed, vehicle, pkg, 0, 400, True)) == 22
    assert len(global_features(vehicle, [placed], {"p1": pkg}, 3, 2, 4, 80, 128)) == 14


def test_checkpoint_loads_strict():
    scorer = LumoScorer("app/ml/models/Lumo-R1.1.pt")
    assert scorer.max_placed == 80
    assert scorer.max_candidates == 128


def test_policy_matches_checkpoint_keys():
    ckpt = torch.load("app/ml/models/Lumo-R1.1.pt", map_location="cpu", weights_only=False)
    policy = PCTPolicy(**ckpt["model_cfg"])
    incompatible = policy.load_state_dict(ckpt["model"], strict=True)
    assert incompatible.missing_keys == []
    assert incompatible.unexpected_keys == []


def test_pick_returns_index_of_candidate():
    scorer = LumoScorer("app/ml/models/Lumo-R1.1.pt")
    vehicle = VehicleData(inner_l=600, inner_w=240, inner_h=240, max_payload_kg=2000)
    pkg = PackageData(id="a", l=80, w=60, h=50, weight=40, allowed_rotations=[0, 1])
    cands = [
        PlacementData(
            package_id="a",
            x=0,
            y=0,
            z=0,
            packed_l=80,
            packed_w=60,
            packed_h=50,
            rotation_type=0,
            step_sequence=1,
            stop_zone_id="s",
        ),
        PlacementData(
            package_id="a",
            x=100,
            y=10,
            z=0,
            packed_l=60,
            packed_w=80,
            packed_h=50,
            rotation_type=1,
            step_sequence=1,
            stop_zone_id="s",
        ),
    ]
    index = scorer.pick(vehicle, {"a": pkg}, [], cands, 1, 1, 0.0, 300.0)
    assert index in (0, 1)


def test_ultimate_solver_loads_lumo(monkeypatch):
    loaded = []
    scorer = LumoScorer("app/ml/models/Lumo-R1.1.pt")

    def fake_get():
        loaded.append(True)
        return scorer

    monkeypatch.setattr("app.service.optimize.optimization_client.get_lumo_scorer", fake_get)
    problem = ProblemRequest(
        vehicle=VehicleData(inner_l=6000.0, inner_w=2400.0, inner_h=2400.0, max_payload_kg=5000.0),
        packages=[
            PackageData(id=uuid.uuid4(), l=1000.0, w=800.0, h=600.0, weight=50.0, allowed_rotations=[0]),
        ],
        algorithm_name="EP_DBLF_GA_AI",
    )
    result = OptimizationClient().solve_in_process(problem)
    assert loaded == [True]
    assert result.placements is not None
    assert result.unplaced is not None
