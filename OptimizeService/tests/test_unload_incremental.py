"""Kiểm dỡ hàng tăng dần (UnloadFeasibilityValidator, 28/11/2026): sau khi lấy một kiện ra, chỉ kiểm lại HC-05 / HC-06
cho các kiện nó có thể đang đỡ. Phải cùng kết luận — đạt / mã lỗi / trình tự dỡ — với cách kiểm lại mọi kiện còn trong
xe (`incremental=False`), cả khi thứ tự giao đổi khiến tìm kiếm phải lùi nhiều."""
import random

import pytest

from app.config.settings import settings
from app.service.optimize import lmai_builder as LB
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator as V
from tests.test_lmai_builder import _random_problem

pytest.importorskip("numpy")

BUILDS = ("EP-DBLF", "EP-trong-tam", "H4-0", "H4-2")   # đủ các kiểu bộ dựng, test nhanh hơn chạy cả 6


def _plans(seed, n, stops):
    from app.ml.lmai.problem import Problem

    request = _random_problem(seed, n=n, stops=stops)
    lm = Problem.from_request(request.model_dump(mode="json"), physics_v2=ConstraintEngine.physics_v2())
    for name in BUILDS:
        res = LB._build(lm, name)
        if res.placements:
            yield request, name, LB._to_placements(res)


def _verdict(result):
    return result.passed, result.violation_code, list(result.unload_sequence)


def _compare(placements, packages_map, vehicle):
    full = V.validate(placements, packages_map, vehicle, incremental=False)
    fast = V.validate(placements, packages_map, vehicle)
    assert _verdict(fast) == _verdict(full)
    return full


@pytest.mark.parametrize("v2", [False, True])
@pytest.mark.parametrize("seed", range(3))
def test_same_verdict_on_builder_plans(monkeypatch, v2, seed):
    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", v2)
    for request, _, placements in _plans(500 + seed, n=40, stops=3):
        _compare(placements, {str(p.id): p for p in request.packages}, request.vehicle)


@pytest.mark.parametrize("seed", range(6))
def test_same_verdict_when_stop_order_changes(seed):
    """Đổi điểm giao của kiện: nhiều phương án không còn dỡ được (LIFO / mất đỡ khi lấy kiện dưới ra), tìm kiếm phải lùi."""
    rng = random.Random(seed)
    verdicts = []
    for request, _, placements in _plans(600 + seed, n=30, stops=3):
        packages_map = {str(p.id): p.model_copy(update={"stop_index": rng.randint(1, 4)}) for p in request.packages}
        verdicts.append(_compare(placements, packages_map, request.vehicle).passed)
    assert verdicts


def test_stop_order_cases_cover_both_outcomes():
    """Bộ ca đổi điểm giao ở trên có cả ca đạt lẫn ca không đạt (để phần so sánh thật sự đi qua nhánh lùi)."""
    outcomes = set()
    for seed in range(6):
        rng = random.Random(seed)
        for request, _, placements in _plans(600 + seed, n=30, stops=3):
            packages_map = {str(p.id): p.model_copy(update={"stop_index": rng.randint(1, 4)}) for p in request.packages}
            outcomes.add(V.validate(placements, packages_map, request.vehicle).passed)
    assert outcomes == {True, False}
