"""M10 (KE-HOACH-TOI-UU, spec R2): SA tối đa J trên bộ dựng lmai cho gói PRO / ULTIMATE, sau cờ
OPTIMIZATION_LMAI_SA_ENABLED; thay GA + SA cũ; phương án SA qua kiểm của service; lỗi → job vẫn chạy."""
import time

import pytest

from app.config.settings import settings
from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.service.optimize import lmai_builder as LB
from app.service.optimize import lmai_sa as LS
from app.service.optimize import plan_score
from app.service.optimize.optimization_client import OptimizationClient
from tests.test_lmai_builder import _random_problem
from tests.test_load_balance_search import assert_safe

pytest.importorskip("numpy")


@pytest.fixture
def sa_on(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", True)
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_SA_ENABLED", True)
    monkeypatch.setattr(settings, "LMAI_SA_BUDGET_SEC", 3.0)


def test_flag_is_off_by_default():
    from app.config.settings import Settings
    assert Settings.model_fields["OPTIMIZATION_LMAI_SA_ENABLED"].default is False
    assert Settings.model_fields["LMAI_SA_BUDGET_SEC"].default == 30.0


@pytest.mark.parametrize("j", [False, True])
def test_pro_job_runs_lmai_sa_instead_of_ga(monkeypatch, sa_on, j):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", j)
    called = []
    monkeypatch.setattr("app.service.optimize.metaheuristic.run_ga_sa", lambda *a, **k: called.append(1))
    request = _random_problem(5, n=30, stops=2).model_copy(update={"algorithm_name": "PRO"})
    result = OptimizationClient().solve_candidates(request)
    assert called == []                                        # GA + SA cũ không chạy
    assert PlanAlgorithm.SA.value in result.algorithms_tried
    assert PlanAlgorithm.GA.value not in result.algorithms_tried
    for cand in result.candidates:
        assert cand.result.algorithm_name in (PlanAlgorithm.EP_DBLF.value, PlanAlgorithm.SA.value)
        assert_safe(cand.result, request)


def test_flag_off_keeps_old_ga_sa(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", True)
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_SA_ENABLED", False)
    called = []
    from app.service.optimize import metaheuristic

    real = metaheuristic.run_ga_sa

    def spy(*a, **k):
        called.append(1)
        return real(*a, **k)

    monkeypatch.setattr(metaheuristic, "run_ga_sa", spy)
    request = _random_problem(6, n=12, stops=1).model_copy(update={"algorithm_name": "PRO", "time_limit_sec": 10})
    OptimizationClient().solve_candidates(request)
    assert called == [1]


@pytest.mark.parametrize("seed", range(4))
def test_sa_plans_are_never_worse_and_pass_service_rules(monkeypatch, seed):
    """Mỗi chuỗi trả phương án tốt nhất đã thấy (≥ điểm xuất phát theo J của hồ sơ); phương án SA vào job thì đã qua
    luật của service (không bị từ chối vì vi phạm)."""
    monkeypatch.setattr(settings, "PLAN_SCORE_J", True)
    monkeypatch.setattr(settings, "LMAI_SA_BUDGET_SEC", 3.0)
    request = _random_problem(200 + seed, n=25)
    client = OptimizationClient()
    built = LB.build_layouts(request, client, time.monotonic() + 60)
    assert built.lm_problem is not None and any(k.startswith("H4-") for k in built.lm_results)
    rejected = []
    real = client.evaluate_fixed_layout

    def spy(*a, **k):
        layout, reason = real(*a, **k)
        if layout is None:
            rejected.append(reason)
        return layout, reason

    monkeypatch.setattr(client, "evaluate_fixed_layout", spy)
    out = LS.run_lmai_sa(request, client, built, time.monotonic() + 30)
    assert rejected == []
    assert out.evaluations[PlanAlgorithm.SA.value] > len(plan_score.CANDIDATE_PROFILES)
    starts = list(built.layouts)
    for label in plan_score.CANDIDATE_PROFILES:
        prof = plan_score.profiles()[label]
        before = max(plan_score.selection_scalar(request, l.placements, prof) for l in starts)
        after = max(plan_score.selection_scalar(request, l.placements, prof) for l in starts + out.layouts)
        assert after >= before - 1e-6
    for layout in out.layouts:
        assert layout.algorithm_name == PlanAlgorithm.SA.value
        assert_safe(layout, request)


def test_sa_respects_deadline(monkeypatch):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", True)
    monkeypatch.setattr(settings, "LMAI_SA_BUDGET_SEC", 60.0)
    request = _random_problem(9, n=25)
    client = OptimizationClient()
    built = LB.build_layouts(request, client, time.monotonic() + 60)
    t0 = time.monotonic()
    LS.run_lmai_sa(request, client, built, t0 + 2.5)
    assert time.monotonic() - t0 < 2.5 + 1.0          # một lần giải mã / kiểm cuối có thể vượt nhẹ


def test_sa_skips_without_heuristic4_start_or_budget(monkeypatch):
    request = _random_problem(10, n=15)
    client = OptimizationClient()
    built = LB.build_layouts(request, client, time.monotonic() + 60)
    assert LS.run_lmai_sa(request, client, built, time.monotonic() + 0.2).layouts == []   # không đủ giờ
    built.lm_results = {k: v for k, v in built.lm_results.items() if not k.startswith("H4-")}
    assert LS.run_lmai_sa(request, client, built, time.monotonic() + 30).layouts == []
    assert LS.run_lmai_sa(request, client, None, time.monotonic() + 30).layouts == []


def test_sa_crash_does_not_break_job(monkeypatch, sa_on):
    def boom(*a, **k):
        raise RuntimeError("SA hỏng")

    monkeypatch.setattr(LS, "run_lmai_sa", boom)
    request = _random_problem(11, n=15).model_copy(update={"algorithm_name": "PRO"})
    result = OptimizationClient().solve_candidates(request)
    assert result.placements


def test_sa_profiles_match_backend_profiles():
    for code in plan_score.CANDIDATE_PROFILES:
        lp, bp = LS._lmai_profile(code), plan_score.profiles()[code]
        assert (lp.w_vol, lp.w_n, tuple(lp.weights)) == (bp.w_vol, bp.w_n, tuple(bp.weights))
