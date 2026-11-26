"""Năm bài demo của spec mục 11 (phần ràng buộc cứng) với luật mới PHYSICS_RULES_V2.

Bài nằm ở tests/fixtures/demo_r1/*.json (sinh bằng LoadMaster_AI/do-b0/m4/tao_demo_r1.py; mỗi file có `_ghi_chu`
ghi kết quả mong đợi). Test chạy một lượt bộ dựng (solve_in_process, cách xếp mặc định) để không chạm hạn thời gian:
kết quả không phụ thuộc tốc độ máy.
"""
import json
import time
from pathlib import Path

import pytest

from app.config.settings import settings
from app.constant.optimization import violation_codes as VC
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import ProblemRequest
from app.service.optimize.constraint_engine import ConstraintEngine as CE
from app.service.optimize.load_balance_search import validate_plan
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator

DEMO = Path(__file__).parent / "fixtures" / "demo_r1"


@pytest.fixture(autouse=True)
def v2(monkeypatch):
    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", True)


def load(name):
    data = json.loads((DEMO / f"{name}.json").read_text(encoding="utf-8"))
    data.pop("_ghi_chu")
    return ProblemRequest(**data)


def solve(request):
    started = time.monotonic()
    result = OptimizationClient().solve_in_process(request)
    assert time.monotonic() - started < request.time_limit_sec / 2, "demo must not depend on the deadline"
    return result


def assert_valid(result, request):
    packages = {str(p.id): p for p in request.packages}
    assert validate_plan(result.placements, request.vehicle, packages, time.monotonic() + 60) == []
    placed = {str(p.package_id) for p in result.placements}
    assert placed | {str(u.package_id) for u in result.unplaced} == set(packages)
    return packages


def test_demo1_mixed_sizes_all_loaded():
    request = load("1-hon-hop-nhieu-co")
    result = solve(request)
    assert_valid(result, request)
    assert len(result.placements) == 30 and result.unplaced == []


def test_demo2_heavy_boxes_never_rest_on_low_sigma_cartons():
    request = load("2-nang-va-sigma-thap")
    result = solve(request)
    packages = assert_valid(result, request)
    assert len(result.placements) == 8
    assert all(p.z == 0 for p in result.placements if str(p.package_id).startswith("N"))
    # H7 chặn đúng: thùng 80 kg trên thùng σ 150 → 80 / 0,25 m² = 320 kg/m² > 150.
    light, heavy = packages["T0"], packages["N0"]
    below = PlacementData(package_id="T0", x=0, y=0, z=0, packed_l=50, packed_w=50, packed_h=25, rotation_type=0)
    above = below.model_copy(update={"package_id": "N0", "z": 25})
    result_h7 = CE.check_stacking(above, [below], {"T0": light, "N0": heavy})
    assert result_h7.violation_code == VC.STACK_PRESSURE_EXCEEDED and "320.0 kg/m²" in result_h7.detail


def test_demo3_five_stops_unload_in_order():
    request = load("3-nam-diem-giao")
    result = solve(request)
    packages = assert_valid(result, request)
    assert len(result.placements) == 20
    unload = UnloadFeasibilityValidator.validate(result.placements, packages, request.vehicle)
    assert unload.passed
    stop = {str(p.id): p.stop_index for p in request.packages}
    order = [stop[k] for k in unload.unload_sequence]
    assert order == sorted(order)  # dỡ theo thứ tự điểm giao 1 → 5 (ca bị chặn → LIFO_BLOCKED: test_luat_vat_ly_v2)


def test_demo4_lopsided_load_is_kept_within_lateral_and_axle_limits():
    request = load("4-don-lech")
    result = solve(request)
    packages = assert_valid(result, request)
    assert len(result.placements) == 16
    w = request.vehicle.inner_w
    assert abs(result.metrics.cog_y - w / 2) <= CE.cog_offset_limit_y(request.vehicle) * w
    assert CE.check_axle_load(result.placements, request.vehicle, packages).passed
    # Dồn cả 6 thùng 300 kg sát vách phải → H11 chặn.
    heavy = [PlacementData(package_id=f"H{i}", x=100 + 60 * i, y=w - 60, z=0, packed_l=60, packed_w=60,
                           packed_h=60, rotation_type=0) for i in range(6)]
    assert CE.check_cog(heavy, request.vehicle, packages).violation_code == "CENTER_OF_GRAVITY_OUT_OF_RANGE"


def test_demo5_over_capacity_reports_the_rule_for_each_package():
    request = load("5-vuot-suc-chua")
    result = solve(request)
    assert_valid(result, request)
    reasons = {str(u.package_id): u.violated_constraints for u in result.unplaced}
    assert reasons.pop("EP-SAN") == [VC.FLOOR_LOAD_EXCEEDED]
    assert reasons.pop("CAO") == ["ORIENTATION_NOT_ALLOWED"]
    assert reasons and all(codes == ["WEIGHT_LIMIT_EXCEEDED"] for codes in reasons.values())
    total = sum(float(p.weight) for p in request.packages if str(p.id) in {str(x.package_id) for x in result.placements})
    assert total <= request.vehicle.max_payload_kg


# ---------------------------------------------------------------------------------------------- J (M6, spec 6 + 11)
# Số chốt bằng máy trên phương án một lượt ở trên, hồ sơ "default" (trọng số giả định). Đổi bộ dựng thì chạy lại và
# cập nhật cùng lúc với số kiện ở các test trên.
DEMO_J = {
    "1-hon-hop-nhieu-co": (0.0, 0.164005, 52.905534),
    "2-nang-va-sigma-thap": (0.0, 0.220879, 140.579509),
    "3-nam-diem-giao": (0.0, 0.172766, 40.172144),
    "4-don-lech": (0.0, 0.147756, 47.389171),
    "5-vuot-suc-chua": (0.0, 0.147619, 43.39922),
}


@pytest.mark.parametrize("name", sorted(DEMO_J))
def test_demo_j_breakdown(name):
    from app.service.optimize import plan_score as PS

    request = load(name)
    result = solve(request)
    s = PS.score(request, result.placements)
    s1, s3, j = DEMO_J[name]
    assert s.values["S1"] == pytest.approx(s1, abs=1e-6)
    assert s.values["S1"] == pytest.approx(result.metrics.rehandling_count / len(request.packages))
    assert s.computed["S3"] and s.values["S3"] == pytest.approx(s3, abs=1e-6)
    v = request.vehicle   # S3 khớp tải trục của metrics (làm tròn 0,01 kg)
    front, rear = result.metrics.front_axle_load, result.metrics.rear_axle_load
    assert s.values["S3"] == pytest.approx(abs(front / v.front_axle_limit_kg - rear / v.rear_axle_limit_kg), abs=1e-5)
    assert s.j == pytest.approx(j, abs=1e-6)
