"""M11 (KE-HOACH-TOI-UU, spec R2): Lumo-R1.1 + "biết tính trước" cho gói ULTIMATE, sau cờ
OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED; chấm theo J của hồ sơ job; không kém lượt greedy; đúng hạn; lỗi → giữ greedy."""
import time

import pytest

from app.config.settings import settings
from app.dto.optimization.engine.problem_request import PlanProfileRequest
from app.ml import lumo_models
from app.service.optimize.optimization_client import OptimizationClient
from tests.test_lmai_builder import _random_problem
from tests.test_load_balance_search import assert_safe

pytest.importorskip("torch")
if not lumo_models.model_path("LUMO_R1_1").is_file():
    pytest.skip("thiếu trọng số LUMO_R1_1", allow_module_level=True)

CODE = "LUMO_R1_1"


@pytest.fixture
def la_on(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED", True)
    monkeypatch.setattr(settings, "LMAI_LOOKAHEAD_SEC", 4.0)


def test_flag_is_off_by_default():
    from app.config.settings import Settings
    assert Settings.model_fields["OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED"].default is False
    assert Settings.model_fields["LMAI_LOOKAHEAD_SEC"].default == 12.0


def test_flag_off_does_not_run_lookahead(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED", False)
    called = []
    monkeypatch.setattr(lumo_models, "_run_lookahead", lambda *a, **k: called.append(1))
    lumo_models.run_lumo_models(_random_problem(1, n=15), [CODE], OptimizationClient(), samples=0)
    assert called == []


@pytest.mark.parametrize("j", [False, True])
@pytest.mark.parametrize("seed", range(2))
def test_lookahead_never_worse_than_greedy_and_passes_service_rules(monkeypatch, la_on, j, seed):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", j)
    request = _random_problem(300 + seed, n=25).model_copy(
        update={"plan_profile": PlanProfileRequest(code="C") if j else None})
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED", False)   # lượt so sánh: greedy thật
    greedy = lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), samples=0)
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED", True)
    seen = []
    real = lumo_models._run_lookahead

    def spy(*a, **k):
        seen.append(1)
        return real(*a, **k)

    monkeypatch.setattr(lumo_models, "_run_lookahead", spy)
    la = lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), samples=0)
    assert seen == [1]
    assert [l.algorithm_name for l in la.layouts] == [CODE]
    assert_safe(la.layouts[0], request)
    if greedy.layouts:
        def key(layout):
            from app.service.optimize import plan_score
            if j:
                return plan_score.selection_key(request, layout.placements)
            return len(layout.placements)
        assert key(la.layouts[0]) >= key(greedy.layouts[0])


def test_lookahead_uses_job_profile(monkeypatch, la_on):
    """Chấm theo hồ sơ của job (C, có ghi đè trọng số), truyền tường minh — không qua biến toàn cục của lmai."""
    from app.ml.lmai import lookahead as LA
    from app.ml.lmai import plan_score as lps
    from app.service.optimize import plan_score

    monkeypatch.setattr(settings, "PLAN_SCORE_J", True)
    req_prof = PlanProfileRequest(code="C", weights={"S2": 0.4})
    request = _random_problem(310, n=15).model_copy(update={"plan_profile": req_prof})
    got = []
    real_init = LA.Lookahead.__init__

    def spy(self, *a, **k):
        got.append(k.get("profile"))
        real_init(self, *a, **k)

    monkeypatch.setattr(LA.Lookahead, "__init__", spy)
    before = lps.active_profile()
    lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), samples=0)
    want = plan_score.resolve_profile(req_prof)
    assert len(got) == 1 and got[0] is not None
    assert (got[0].w_vol, got[0].w_n, tuple(got[0].weights)) == (want.w_vol, want.w_n, tuple(want.weights))
    assert lps.active_profile() is before


def test_lookahead_respects_deadline(monkeypatch, la_on):
    """"Biết tính trước" xong trước hạn job (phần giữ = 2 × lượt greedy + 1 s cho bước kiểm; lượt thử chỉ mở khi ước tính xong trước giờ). Đo lúc nó kết thúc,
    không đo cả hàm: bước kiểm của service sau đó không nhận hạn job (như trước M11) và chậm hẳn khi máy bận."""
    from app.ml.lmai.lookahead import Lookahead

    monkeypatch.setattr(settings, "LMAI_LOOKAHEAD_SEC", 60.0)
    ends = []
    real = Lookahead.solve

    def timed(self, problem):
        res = real(self, problem)
        ends.append(time.monotonic())
        return res

    monkeypatch.setattr(Lookahead, "solve", timed)
    request = _random_problem(320, n=40)
    deadline = time.monotonic() + 15.0      # đủ rộng để biết tính trước chạy cả khi máy bận
    lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), deadline=deadline, samples=0)
    assert len(ends) == 1 and ends[0] < deadline + 0.5


def test_lookahead_stops_at_job_target(monkeypatch, la_on):
    """Mục tiêu cả job (`target` = lúc bắt đầu job + LMAI_JOB_TARGET_SEC) chặn biết tính trước dù hạn request còn dài."""
    from app.ml.lmai.lookahead import Lookahead

    monkeypatch.setattr(settings, "LMAI_LOOKAHEAD_SEC", 60.0)
    ends = []
    real = Lookahead.solve

    def timed(self, problem):
        res = real(self, problem)
        ends.append(time.monotonic())
        return res

    monkeypatch.setattr(Lookahead, "solve", timed)
    now = time.monotonic()
    lumo_models.run_lumo_models(_random_problem(321, n=40), [CODE], OptimizationClient(), deadline=now + 60,
                                target=now + 15.0, samples=0)
    assert len(ends) == 1 and ends[0] < now + 15.0 + 0.5


def test_lookahead_skipped_when_no_time(monkeypatch, la_on):
    called = []
    monkeypatch.setattr("app.ml.lmai.lookahead.Lookahead.solve", lambda *a, **k: called.append(1))
    request = _random_problem(330, n=15)
    lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), deadline=time.monotonic() + 1.5, samples=0)
    assert called == []


def test_service_rejecting_lookahead_plan_falls_back_to_greedy(monkeypatch, la_on):
    """lmai và service chưa khớp tuyệt đối: phương án biết tính trước bị luật service từ chối → vẫn giữ ứng viên
    LUMO_R1_1 bằng phương án greedy (không mất ứng viên)."""
    def fake_la(problem, lm_problem, best, cost, deadline):
        previous = best[CODE]
        best[CODE] = previous          # coi như biết tính trước đã thay phương án
        return previous

    monkeypatch.setattr(lumo_models, "_run_lookahead", fake_la)
    client = OptimizationClient()
    real = client.evaluate_fixed_layout
    calls = []

    def first_rejected(*a, **k):
        calls.append(1)
        if len(calls) == 1:
            return None, "TEST_REJECT"
        return real(*a, **k)

    monkeypatch.setattr(client, "evaluate_fixed_layout", first_rejected)
    run = lumo_models.run_lumo_models(_random_problem(301, n=25), [CODE], client, samples=0)
    assert len(calls) == 2
    assert [l.algorithm_name for l in run.layouts] == [CODE]


def test_lookahead_crash_keeps_greedy(monkeypatch, la_on):
    def boom(*a, **k):
        raise RuntimeError("lookahead hỏng")

    monkeypatch.setattr("app.ml.lmai.lookahead.Lookahead.solve", boom)
    run = lumo_models.run_lumo_models(_random_problem(340, n=15), [CODE], OptimizationClient(), samples=0)
    assert run.tried == [CODE] and [l.algorithm_name for l in run.layouts] == [CODE]
