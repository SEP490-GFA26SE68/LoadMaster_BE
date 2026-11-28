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


def test_flag_is_on_by_default():
    """Bật ở mọi môi trường từ 27/11/2026; numpy là phụ thuộc chính (requirements.txt) để không lặng lẽ lùi bộ cũ."""
    import pathlib

    from app.config.settings import Settings

    assert Settings.model_fields["OPTIMIZATION_LMAI_BUILDER_ENABLED"].default is True
    req = (pathlib.Path(__file__).resolve().parents[1] / "requirements.txt").read_text(encoding="utf-8")
    assert any(line.startswith("numpy==") for line in req.splitlines())


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
    """Hạn chót đã qua: bước kiểm từ chối (fail-closed), không chạy quá giờ."""
    request = _random_problem(5, n=10, stops=2)
    out = LB.build_layouts(request, OptimizationClient(), time.monotonic() + 60)
    pls = out.layouts[0].placements
    layout, reason = OptimizationClient().evaluate_fixed_layout(request, pls, "EP_DBLF", deadline=time.monotonic() - 1)
    assert layout is None and reason == "OPTIMIZATION_TIME_LIMIT_REACHED"
    layout, reason = OptimizationClient().evaluate_fixed_layout(request, pls, "EP_DBLF")
    assert layout is not None and reason is None


def test_builder_does_not_open_a_build_it_cannot_finish(monkeypatch):
    """Lượt dựng đầu tốn 0,3 s, hạn còn 0,5 s: không mở lượt thứ hai (lượt đó sẽ vượt hạn)."""
    real = LB._build
    built = []

    def slow(lm, name):
        built.append(name)
        time.sleep(0.3)
        return real(lm, name)

    monkeypatch.setattr(LB, "_build", slow)
    LB.build_layouts(_random_problem(6, n=8, stops=1), OptimizationClient(), time.monotonic() + 0.5)
    assert built == ["EP-DBLF"]


def test_first_check_leaves_time_for_other_builds(monkeypatch):
    """Chưa có cách xếp nào qua kiểm mà còn cách khác chờ: lần kiểm chỉ được dùng FIRST_CHECK_SHARE thời gian còn lại."""
    seen = []

    def fake_eval(self, problem, placements, name, started_at=None, deadline=None):
        seen.append(deadline - time.monotonic())
        return None, "UNLOAD_TIME_LIMIT_REACHED"

    monkeypatch.setattr(OC.OptimizationClient, "evaluate_fixed_layout", fake_eval)
    out = LB.build_layouts(_random_problem(7, n=8, stops=1), OptimizationClient(), time.monotonic() + 100)
    assert out.layouts == [] and seen
    assert all(s <= LB.FIRST_CHECK_SHARE * 100 + 0.5 for s in seen[:-1])


def test_builder_estimates_heuristic4_from_ep(monkeypatch):
    """Hai lượt EP 0,2 s; còn ~0,7 s: không mở lượt Heuristic-4 đầu tiên (ước 4 × 0,2 = 0,8 s)."""
    real = LB._build
    built = []

    def slow(lm, name):
        built.append(name)
        time.sleep(0.2)
        return real(lm, name)

    monkeypatch.setattr(LB, "_build", slow)
    list(LB._plans(_lm(_random_problem(8, n=6, stops=1)), time.monotonic() + 1.15))
    assert built == ["EP-DBLF", "EP-trong-tam"]


def _lm(request):
    from app.ml.lmai.problem import Problem
    return Problem.from_request(request.model_dump(mode="json"))
