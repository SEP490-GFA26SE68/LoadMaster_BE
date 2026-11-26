"""M9 (KE-HOACH-TOI-UU, D1 26/11/2026): bước EP dựng bằng bộ dựng chung của LoadMaster_AI, sau cờ
OPTIMIZATION_LMAI_BUILDER_ENABLED; kiểm cuối vẫn bằng luật của service; lỗi / không dùng được → bộ dựng EP cũ."""
import random
import time

import pytest

from app.config.settings import settings
from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.service.optimize import lmai_builder as LB
from app.service.optimize import optimization_client as OC
from app.service.optimize.optimization_client import OptimizationClient
from tests.test_load_balance_search import assert_safe, package, problem

pytest.importorskip("numpy")


def _random_problem(seed, n=None, stops=None):
    rng = random.Random(seed)
    n = n or rng.randint(12, 40)
    stops = stops or rng.randint(1, 3)
    pkgs = [package(f"P{i}", stop=rng.randint(1, stops), weight=rng.uniform(5, 45),
                    l=rng.uniform(25, 80), w=rng.uniform(20, 60), h=rng.uniform(15, 60),
                    rotation_allowed=True, allowed_rotations=[0, 1], max_stack_weight_kg=rng.choice([60, 150, 400]))
            for i in range(n)]
    return problem(pkgs, time_limit_sec=30)


@pytest.fixture
def lmai_on(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", True)


def _spy_old_builder(monkeypatch):
    calls = []
    real = OptimizationClient.solve_in_process

    def spy(self, request, *args, **kwargs):
        calls.append(kwargs.get("strategy"))
        return real(self, request, *args, **kwargs)

    monkeypatch.setattr(OC.OptimizationClient, "solve_in_process", spy)
    return calls


def test_flag_is_off_by_default():
    assert settings.OPTIMIZATION_LMAI_BUILDER_ENABLED is False


def test_basic_job_uses_lmai_builder(monkeypatch, lmai_on):
    calls = _spy_old_builder(monkeypatch)
    request = _random_problem(1).model_copy(update={"algorithm_name": "BASIC"})
    result = OptimizationClient().solve_candidates(request)
    assert calls == []                                   # bộ dựng EP cũ không chạy
    assert result.placements and result.algorithms_tried == [PlanAlgorithm.EP_DBLF.value]
    for cand in result.candidates:
        assert cand.result.algorithm_name == PlanAlgorithm.EP_DBLF.value
        assert_safe(cand.result, request)


@pytest.mark.parametrize("seed", range(10))
@pytest.mark.parametrize("v2", [False, True])
def test_every_lmai_plan_passes_service_rules(monkeypatch, seed, v2):
    """Parity: phương án lmai cho là hợp lệ thì luật của service (ConstraintEngine từng chỗ đặt, HC-11 / HC-12,
    kiểm dỡ hàng, H13) cũng nhận — cách xếp chỉ bị bỏ vì hết giờ, không vì vi phạm."""
    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", v2)
    request = _random_problem(100 + seed)
    out = LB.build_layouts(request, OptimizationClient(), time.monotonic() + 120)
    assert out.skipped is None
    bad = {k: v for k, v in out.rejected.items() if not v.startswith(("LMAI_INVALID", "TIME_LIMIT"))}
    assert bad == {}
    assert out.layouts and len(out.layouts) == len(out.ep_runs)


def test_pinned_job_falls_back_to_old_builder(monkeypatch, lmai_on):
    from app.dto.optimization.engine.problem_request import PinnedData
    calls = _spy_old_builder(monkeypatch)
    base = _random_problem(2, n=6, stops=1)
    pin = PinnedData(package_id="P0", pos_x=0, pos_y=0, pos_z=0, rotation_type=0)
    request = base.model_copy(update={"pinned": [pin], "algorithm_name": "BASIC"})
    assert LB.build_layouts(request, OptimizationClient(), time.monotonic() + 30).skipped == "PINNED"
    OptimizationClient().solve_candidates(request)
    assert calls                                          # bộ dựng cũ chạy


def test_all_rejected_falls_back_to_old_builder(monkeypatch, lmai_on):
    calls = _spy_old_builder(monkeypatch)
    monkeypatch.setattr(OC.OptimizationClient, "evaluate_fixed_layout", lambda *a, **k: (None, "HC_TEST"))
    request = _random_problem(3).model_copy(update={"algorithm_name": "BASIC"})
    result = OptimizationClient().solve_candidates(request)
    assert calls and result.placements


def test_builder_crash_falls_back_to_old_builder(monkeypatch, lmai_on):
    calls = _spy_old_builder(monkeypatch)

    def boom(*a, **k):
        raise RuntimeError("lmai hỏng")

    monkeypatch.setattr(LB, "build_layouts", boom)
    request = _random_problem(4).model_copy(update={"algorithm_name": "BASIC"})
    assert OptimizationClient().solve_candidates(request).placements and calls


def test_evaluate_fixed_layout_respects_deadline():
    """Hạn chót đã qua: bước kiểm dỡ hàng từ chối (fail-closed), không chạy quá giờ."""
    request = _random_problem(5, n=10, stops=2)
    out = LB.build_layouts(request, OptimizationClient(), time.monotonic() + 60)
    pls = out.layouts[0].placements
    layout, reason = OptimizationClient().evaluate_fixed_layout(request, pls, "EP_DBLF", deadline=time.monotonic() - 1)
    assert layout is None and reason == "UNLOAD_TIME_LIMIT_REACHED"
    layout, reason = OptimizationClient().evaluate_fixed_layout(request, pls, "EP_DBLF")
    assert layout is not None and reason is None
