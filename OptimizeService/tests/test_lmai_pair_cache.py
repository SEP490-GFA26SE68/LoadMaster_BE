"""Bộ nhớ đệm kiểm từng cặp của bộ dựng lmai (`app.ml.lmai.state.PAIR_CACHE`, 28/11/2026): mọi cách xếp của bộ dựng
M9 (`lmai_builder.BUILDS`) phải ra cùng phương án khi bật và khi tắt — từng chỗ đặt, kiện không xếp được, số dỡ-xếp
lại, kết luận hợp lệ. Bộ đối chiếu đầy đủ (420 bài) ở repo AI: do-b0/tang-toc/so_bo_nho_dem.py."""
import pytest

pytest.importorskip("numpy")

import app.ml.lmai.state as LS  # noqa: E402
from app.config.settings import settings  # noqa: E402
from app.service.optimize import lmai_builder as LB  # noqa: E402
from app.service.optimize.constraint_engine import ConstraintEngine  # noqa: E402
from tests.test_lmai_builder import _random_problem  # noqa: E402


def _sig(res):
    return ([(p.package_id, p.x, p.y, p.z, p.packed_l, p.packed_w, p.packed_h, p.rotation_type, p.step_sequence)
             for p in res.placements], sorted(res.unplaced), res.rehandling, res.verdict.valid)


@pytest.mark.parametrize("v2", [False, True])
@pytest.mark.parametrize("seed", range(3))
def test_same_plan_with_and_without_pair_cache(monkeypatch, v2, seed):
    from app.ml.lmai.problem import Problem

    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", v2)
    request = _random_problem(700 + seed, n=40, stops=3)
    lm = Problem.from_request(request.model_dump(mode="json"), physics_v2=ConstraintEngine.physics_v2())
    for name, _ in LB.BUILDS:
        monkeypatch.setattr(LS, "PAIR_CACHE", False)
        old = LB._build(lm, name)
        monkeypatch.setattr(LS, "PAIR_CACHE", True)
        new = LB._build(lm, name)
        assert old.placements, name
        assert _sig(old) == _sig(new), name


def test_pair_cache_is_on_by_default():
    assert LS.PAIR_CACHE is True
