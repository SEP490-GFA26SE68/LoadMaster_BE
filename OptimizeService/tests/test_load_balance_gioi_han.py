"""P13 / P22 (KE-HOACH-TOI-UU, M8a): bước sửa cân bằng có giới hạn (cờ LOAD_BALANCE_BOUNDED_ENABLED, mặc định tắt).

Ngân sách riêng, dừng khi không còn hơn, trần cho mỗi lần kiểm dỡ hàng — vẫn fail-closed: chỉ phương án đã
kiểm đủ mới được giữ.
"""
import time

import pytest

from app.config.settings import settings
from app.service.optimize import load_balance_search as lbs_module
from app.service.optimize.load_balance_search import LoadBalanceSearch, validate_plan
from tests.test_load_balance_search import package, placement, problem


@pytest.fixture
def bounded(monkeypatch):
    monkeypatch.setattr(settings, "LOAD_BALANCE_BOUNDED_ENABLED", True)
    monkeypatch.setattr(settings, "LOAD_BALANCE_BUDGET_RATIO", 0.25)
    monkeypatch.setattr(settings, "LOAD_BALANCE_PATIENCE", 4)
    monkeypatch.setattr(settings, "LOAD_BALANCE_UNLOAD_CHECK_SEC", 3.0)


def test_bounded_flag_is_off_by_default():
    from app.config.settings import Settings
    assert Settings(_env_file=None, KEYCLOAK_CLIENT_SECRET="x").LOAD_BALANCE_BOUNDED_ENABLED is False


def _partial_start():
    request = problem([package("A"), package("B")], time_limit_sec=40)
    return request, [placement(request.packages[0], 290, 105)]


def test_bounded_improvement_gets_its_own_share_of_the_time_limit(monkeypatch, bounded):
    request, initial = _partial_start()
    search = LoadBalanceSearch(request, time.monotonic() + 100)
    seen = []
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda self, *args: seen.append(self.deadline) or [])
    started = time.monotonic()
    search.improve(initial, {})
    assert seen and max(seen) <= started + 40 * 0.25 + 0.5


def test_unbounded_improvement_keeps_the_pass_deadline(monkeypatch):
    monkeypatch.setattr(settings, "LOAD_BALANCE_BOUNDED_ENABLED", False)
    request, initial = _partial_start()
    deadline = time.monotonic() + 100
    search = LoadBalanceSearch(request, deadline)
    seen = []
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda self, *args: seen.append(self.deadline) or [])
    search.improve(initial, {})
    assert seen and set(seen) == {deadline}
    assert len(seen) == LoadBalanceSearch.MAX_ATTEMPTS


def test_bounded_improvement_stops_after_patience_repacks_without_gain(monkeypatch, bounded):
    request, initial = _partial_start()
    search = LoadBalanceSearch(request, time.monotonic() + 100)
    calls = []
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda self, *args: calls.append(1) or [])
    result = search.improve(initial, {})
    assert len(calls) == 4
    assert [p.package_id for p in result] == ["A"]  # phương án hợp lệ đã có vẫn được giữ


def test_patience_restarts_after_a_better_plan(monkeypatch, bounded):
    request = problem([package("A"), package("B"), package("C")], time_limit_sec=40)
    a, b = request.packages[0], request.packages[1]
    search = LoadBalanceSearch(request, time.monotonic() + 100)
    plans = iter([[], [], [placement(a, 260, 105), placement(b, 320, 105)]])
    calls = []

    def repack(self, *args):
        calls.append(1)
        return next(plans, [])

    monkeypatch.setattr(LoadBalanceSearch, "_repack", repack)
    result = search.improve([placement(a, 290, 105)], {})
    assert len(result) == 2
    assert len(calls) == 3 + 4  # lần thứ 3 hơn → đếm lại từ đầu


def test_validate_plan_unload_budget_is_fail_closed():
    request = problem([package("A")])
    metadata = {p.id: p for p in request.packages}
    plan = [placement(request.packages[0], 290, 105)]
    assert validate_plan(plan, request.vehicle, metadata, time.monotonic() + 5) == []
    assert validate_plan(plan, request.vehicle, metadata, time.monotonic() + 5,
                         unload_budget_sec=0.0) == ["UNLOAD_TIME_LIMIT_REACHED"]


def test_bounded_search_caps_every_unload_check(monkeypatch, bounded):
    request, initial = _partial_start()
    search = LoadBalanceSearch(request, time.monotonic() + 100)
    budgets = []
    real = lbs_module.validate_plan

    def spy(*args, **kwargs):
        budgets.append(kwargs.get("unload_budget_sec"))
        return real(*args, **kwargs)

    monkeypatch.setattr(lbs_module, "validate_plan", spy)
    search._consider(initial)
    search._repack(request.packages, {}, 0)
    assert budgets and set(budgets) == {3.0}


def test_unbounded_search_does_not_cap_unload_checks(monkeypatch):
    monkeypatch.setattr(settings, "LOAD_BALANCE_BOUNDED_ENABLED", False)
    request, initial = _partial_start()
    search = LoadBalanceSearch(request, time.monotonic() + 100)
    budgets = []
    real = lbs_module.validate_plan

    def spy(*args, **kwargs):
        budgets.append(kwargs.get("unload_budget_sec"))
        return real(*args, **kwargs)

    monkeypatch.setattr(lbs_module, "validate_plan", spy)
    search._consider(initial)
    assert budgets == [None]


def test_bounded_search_checks_the_initial_plan_without_the_unload_cap(monkeypatch, bounded):
    """Phương án lớn hợp lệ cần lâu hơn trần để chứng minh dỡ được — không được loại nó chỉ vì hết trần (NB, M8a)."""
    monkeypatch.setattr(settings, "LOAD_BALANCE_UNLOAD_CHECK_SEC", 0.0)  # trần 0 s: lần kiểm nào bị trần đều trượt
    request = problem([package("A")], time_limit_sec=40)
    initial = [placement(request.packages[0], 290, 105)]
    budgets = []
    real = lbs_module.validate_plan

    def spy(*args, **kwargs):
        budgets.append(kwargs.get("unload_budget_sec"))
        return real(*args, **kwargs)

    monkeypatch.setattr(lbs_module, "validate_plan", spy)
    result = LoadBalanceSearch(request, time.monotonic() + 100).improve(initial, {})
    assert [p.package_id for p in result] == ["A"]
    assert budgets[0] is None


def test_bounded_search_budget_starts_after_the_initial_check(monkeypatch, bounded):
    request, initial = _partial_start()
    real = lbs_module.validate_plan

    def slow_initial(*args, **kwargs):
        if kwargs.get("unload_budget_sec") is None:
            time.sleep(0.3)
        return real(*args, **kwargs)

    monkeypatch.setattr(lbs_module, "validate_plan", slow_initial)
    monkeypatch.setattr(settings, "LOAD_BALANCE_BUDGET_RATIO", 0.01)  # 0,4 s
    seen = []
    monkeypatch.setattr(LoadBalanceSearch, "_repack", lambda self, *args: seen.append(self.deadline) or [])
    started = time.monotonic()
    LoadBalanceSearch(request, time.monotonic() + 100).improve(initial, {})
    assert seen and min(seen) >= started + 0.3 + 0.4 - 0.05
