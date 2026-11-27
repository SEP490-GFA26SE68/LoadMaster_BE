"""M8 (KE-HOACH-TOI-UU, D1 26/11/2026): bộ dựng EP chừa thời gian cho bước kiểm phương án.

check_reserve_sec = max(MIN, RATIO × time_limit_sec), không quá nửa giới hạn; RATIO = 0 tắt.
"""
import time

import pytest

from app.config.settings import settings
from app.service.optimize import optimization_client as OC
from app.service.optimize.optimization_client import OptimizationClient, check_reserve_sec
from tests.test_load_balance_search import package, problem


@pytest.fixture(autouse=True)
def old_ep_builder(monkeypatch):
    """Các test này kiểm bộ dựng EP cũ của service (vẫn chạy khi bộ dựng lmai của M9 không dùng được: kiện ghim, thiếu
    numpy, không cách nào qua kiểm). Cờ M9 bật mặc định từ 27/11/2026 nên ghim tắt ở đây."""
    monkeypatch.setattr(settings, "OPTIMIZATION_LMAI_BUILDER_ENABLED", False)


def test_reserve_defaults():
    assert settings.OPTIMIZATION_CHECK_RESERVE_RATIO == 0.15
    assert settings.OPTIMIZATION_CHECK_RESERVE_MIN_SEC == 2.0
    assert check_reserve_sec(problem([package("A")], time_limit_sec=60)) == pytest.approx(9.0)
    assert check_reserve_sec(problem([package("A")], time_limit_sec=10)) == pytest.approx(2.0)
    assert check_reserve_sec(problem([package("A")], time_limit_sec=2)) == pytest.approx(1.0)   # không quá nửa


def test_reserve_off_when_ratio_zero(monkeypatch):
    monkeypatch.setattr(settings, "OPTIMIZATION_CHECK_RESERVE_RATIO", 0.0)
    assert check_reserve_sec(problem([package("A")], time_limit_sec=60)) == 0.0


def test_builder_stops_inside_the_reserve(monkeypatch):
    """Hạn chót của job còn 1 s < phần giữ lại 9 s: bộ dựng EP không đặt kiện nào (phương án đưa vào bước sửa / kiểm
    rỗng); tắt phần giữ lại thì bộ dựng đặt đủ. Phần giữ lại vẫn dùng được cho bước sửa (LoadBalanceSearch)."""
    from app.service.optimize.load_balance_search import LoadBalanceSearch
    monkeypatch.setattr(settings, "OPTIMIZATION_SHARED_DEADLINE_ENABLED", True)
    built = []
    real = LoadBalanceSearch.improve

    def spy(self, initial, zones):
        built.append(len(initial))
        return real(self, initial, zones)

    monkeypatch.setattr(LoadBalanceSearch, "improve", spy)
    request = problem([package("A"), package("B")], time_limit_sec=60)
    OptimizationClient().solve_in_process(request, job_deadline=time.monotonic() + 1.0)
    monkeypatch.setattr(settings, "OPTIMIZATION_CHECK_RESERVE_RATIO", 0.0)
    OptimizationClient().solve_in_process(request, job_deadline=time.monotonic() + 1.0)
    assert built == [0, 2]


@pytest.mark.parametrize("ratio, passes", [(0.5, 1), (0.0, 2)])
def test_no_new_pass_inside_the_reserve(monkeypatch, ratio, passes):
    """Lượt EP đầu tốn 1,1 s trên giới hạn 2 s: phần giữ lại 1 s → không mở lượt thứ hai; tắt → mở thêm một lượt."""
    monkeypatch.setattr(settings, "OPTIMIZATION_CHECK_RESERVE_RATIO", ratio)
    seen = []
    real = OptimizationClient.solve_in_process

    def slow(self, request, *args, **kwargs):
        seen.append(kwargs.get("strategy"))
        out = real(self, request, *args, **kwargs)
        time.sleep(1.1)
        return out

    monkeypatch.setattr(OC.OptimizationClient, "solve_in_process", slow)
    request = problem([package("A"), package("B")], time_limit_sec=2)
    request = request.model_copy(update={"algorithm_name": "BASIC"})
    OptimizationClient().solve_candidates(request)
    assert len(seen) == passes


@pytest.mark.parametrize("bounded", [True, False])
def test_checked_ep_plan_is_never_dropped(monkeypatch, bounded):
    """D1 (26/11/2026): đã có phương án EP qua kiểm thì không trả phương án rỗng — kể cả khi bước sửa không tìm được
    gì hơn và mọi lần kiểm lại sau đó đều hết giờ. C quá tải trọng xe nên phương án đủ kiện không tồn tại."""
    monkeypatch.setattr(settings, "LOAD_BALANCE_BOUNDED_ENABLED", bounded)
    monkeypatch.setattr(OC, "validate_plan", lambda *a, **k: ["OPTIMIZATION_TIME_LIMIT_REACHED"])
    request = problem([package("A"), package("B"), package("C", weight=6000)], time_limit_sec=10)
    result = OptimizationClient().solve_in_process(request)
    assert sorted(str(p.package_id) for p in result.placements) == ["A", "B"]
    assert [str(u.package_id) for u in result.unplaced] == ["C"]
