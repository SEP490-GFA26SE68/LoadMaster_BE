"""Bước Lumo của gói ULTIMATE dừng đúng hạn job (đo 28/11/2026: bài 230–290 kiện có job ULTIMATE 64–75 s vì bước Lumo
vẫn mở lượt greedy khi chỉ còn vài giây và bước kiểm của service không có hạn).

Luật: mọi lượt xếp xong trước deadline − check_reserve_sec; lượt chưa xong lúc đó bị bỏ (TIME_LIMIT); không mở lượt khi
lượt lâu nhất đã thấy không kịp; bước kiểm của service dừng ở deadline (hết giờ = TIME_LIMIT, không phải vi phạm)."""
import time

import pytest

from app.config.settings import settings
from app.ml import lumo_models
from app.service.optimize.optimization_client import OptimizationClient, check_reserve_sec
from tests.test_lmai_builder import _random_problem

pytest.importorskip("torch")
for _code in ("LUMO_R1", "LUMO_R1_1"):
    if not lumo_models.model_path(_code).is_file():
        pytest.skip(f"thiếu trọng số {_code}", allow_module_level=True)

CODE = "LUMO_R1_1"


@pytest.fixture(autouse=True)
def la_off(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_LOOKAHEAD_ENABLED", False)


def _lm(request):
    from app.ml.lmai.problem import Problem
    from app.service.optimize.constraint_engine import ConstraintEngine

    return Problem.from_request(request.model_dump(mode="json"), physics_v2=ConstraintEngine.physics_v2())


def test_rollout_returns_none_when_stop_time_passed():
    model, env_cfg = lumo_models._load(CODE)
    lm = _lm(_random_problem(400, n=20))
    assert lumo_models._rollout(model, env_cfg, lm, stop_at=time.monotonic()) is None
    assert lumo_models._rollout(model, env_cfg, lm) is not None


def test_slow_model_is_cut_before_job_deadline(monkeypatch):
    """Máy chậm hẳn (mỗi bước 0,1 s): lượt greedy bị cắt ở deadline − phần giữ, job không chạy quá hạn."""
    from app.ml.lmai.env import PackingEnv

    real_step = PackingEnv.step

    def slow_step(self, action):
        time.sleep(0.1)
        return real_step(self, action)

    monkeypatch.setattr(PackingEnv, "step", slow_step)
    request = _random_problem(401, n=40).model_copy(update={"time_limit_sec": 10})
    reserve = check_reserve_sec(request)
    deadline = time.monotonic() + reserve + 1.0
    run = lumo_models.run_lumo_models(request, [CODE], OptimizationClient(), deadline=deadline, samples=0)
    assert time.monotonic() < deadline
    assert run.skipped.get(CODE) == "TIME_LIMIT"
    assert run.layouts == [] and run.tried == []


def test_next_model_not_started_when_longest_rollout_does_not_fit(monkeypatch):
    model, env_cfg = lumo_models._load(CODE)
    request = _random_problem(402, n=20).model_copy(update={"time_limit_sec": 10})
    plan = lumo_models._rollout(model, env_cfg, _lm(request))
    calls = []

    def one_second(model, env_cfg, lm_problem, generator=None, stop_at=None):
        calls.append(stop_at)
        time.sleep(1.0)
        return plan

    monkeypatch.setattr(lumo_models, "_rollout", one_second)
    deadline = time.monotonic() + check_reserve_sec(request) + 1.5
    run = lumo_models.run_lumo_models(request, ["LUMO_R1", CODE], OptimizationClient(), deadline=deadline, samples=0)
    assert len(calls) == 1                       # sau lượt 1 s, còn 0,5 s tới lúc phải xong → không mở lượt thứ hai
    assert calls[0] == pytest.approx(deadline - check_reserve_sec(request))
    assert run.tried == ["LUMO_R1"]
    assert run.skipped.get(CODE) == "TIME_LIMIT"


def test_service_check_stops_at_job_deadline(monkeypatch):
    client = OptimizationClient()
    seen = []

    def timed_out(*args, **kwargs):
        seen.append(kwargs.get("deadline"))
        return None, "OPTIMIZATION_TIME_LIMIT_REACHED"

    monkeypatch.setattr(client, "evaluate_fixed_layout", timed_out)
    deadline = time.monotonic() + 60
    run = lumo_models.run_lumo_models(_random_problem(301, n=25), [CODE], client, deadline=deadline, samples=0)
    assert seen == [deadline]
    assert run.skipped.get(CODE) == "TIME_LIMIT"   # hết giờ kiểm, không phải vi phạm ràng buộc
    assert run.layouts == []


def test_no_deadline_keeps_old_behaviour():
    run = lumo_models.run_lumo_models(_random_problem(340, n=15), [CODE], OptimizationClient(), samples=0)
    assert run.tried == [CODE] and [l.algorithm_name for l in run.layouts] == [CODE]


def test_ultimate_job_on_large_problem_meets_time_limit():
    """Cả job ULTIMATE (bộ dựng lmai + Lumo) trên bài lớn, giới hạn ngắn: xong trong giới hạn của request."""
    request = _random_problem(405, n=120, stops=3).model_copy(update={"time_limit_sec": 8, "algorithm_name": "ULTIMATE"})
    t0 = time.monotonic()
    res = OptimizationClient(engine_url=None).solve_candidates(request)
    assert time.monotonic() - t0 <= 8 + 1.0, res.algorithms_tried
