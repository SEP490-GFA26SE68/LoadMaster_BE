"""Luật vật lý mới (PHYSICS_RULES_V2; spec "Ràng buộc vật lý và điểm phương án" mục 5 và 11; KE-HOACH-TOI-UU M4).

Mỗi công thức một ví dụ tính tay; số trong chú thích là số máy tính ra (cm, kg; diện tích m² = cm² / 10⁴).
Cờ tắt thì luật cũ (các test test_hc0x_* giữ nguyên) — test cuối file chốt điều đó.
"""
import pytest

from app.config.settings import settings
from app.constant.optimization import violation_codes as VC
from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, StopData, VehicleData
from app.service.optimize.constraint_engine import ConstraintEngine as CE
from app.service.optimize.load_balance_search import validate_plan
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator


@pytest.fixture
def v2(monkeypatch):
    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", True)


def pkg(id, l=100, w=100, h=50, weight=10, stop=1, **kw):
    return PackageData(id=id, l=l, w=w, h=h, weight=weight, stop_index=stop, rotation_allowed=False,
                       allowed_rotations=[0], **kw)


def at(p, x=0, y=0, z=0, l=None, w=None, h=None):
    return PlacementData(package_id=p.id, x=x, y=y, z=z, packed_l=l or p.l, packed_w=w or p.w, packed_h=h or p.h,
                         rotation_type=0, step_sequence=1)


def vehicle(**kw):
    data = dict(inner_l=620, inner_w=240, inner_h=250, door_w=240, door_h=250, max_payload_kg=5000,
                max_cog_offset_ratio=0.15, front_axle_x=100, rear_axle_x=350, front_axle_limit_kg=2200,
                rear_axle_limit_kg=3300, empty_front_axle_load_kg=1500, empty_rear_axle_load_kg=1800)
    data.update(kw)
    return VehicleData(**data)


def stacking(placements, packages, **kw):
    *existing, top = placements
    return CE.check_stacking(top, existing, {p.id: p for p in packages}, **kw)


# ---------------------------------------------------------------------------------------------------- H7 áp lực

def three_tiers(sigma_b, sigma_c=None, k_c=None):
    """
    Ba tầng, tiếp xúc một phần:
      C 100×100×50 ở x 0–100 (sàn);  B 100×100×50, 30 kg, ở x 40–140 trên C → tiếp xúc B/C 60×100 = 0,60 m²;
      A 50×50×50, 20 kg, ở x 40–90, y 0–50 trên B → tiếp xúc A/B 50×50 = 0,25 m².
    F_B = 20 kg → p_B = 20 / 0,25 = 80 kg/m².   F_C = 30 + 20 = 50 kg → p_C = 50 / 0,60 = 83,33 kg/m².
    """
    c = pkg("C", weight=40, top_load_capacity_kg_m2=sigma_c, max_stack_weight_kg=k_c)
    b = pkg("B", weight=30, top_load_capacity_kg_m2=sigma_b)
    a = pkg("A", l=50, w=50, weight=20)
    return [at(c), at(b, x=40, z=50), at(a, x=40, z=100)], [c, b, a]


@pytest.mark.parametrize("sigma_b,sigma_c,ok,who", [
    (80, 83.34, True, None),      # cả hai đúng bằng / trên ngưỡng
    (79.9, 1000, False, "B"),     # p_B = 80 > 79,9
    (1000, 83.3, False, "C"),     # p_C = 83,33 > 83,3
])
def test_h7_three_tiers_partial_contact(v2, sigma_b, sigma_c, ok, who):
    placements, packages = three_tiers(sigma_b, sigma_c)
    result = stacking(placements, packages)
    assert result.passed is ok
    if not ok:
        assert result.violation_code == VC.STACK_PRESSURE_EXCEEDED
        assert f"Package {who} " in result.detail


def test_h7_partial_contact_is_stricter_than_the_old_kg_rule(v2, monkeypatch):
    """C có tải chồng cũ K = 60 kg (σ = 60 / 1 m² = 60). Luật cũ: 50 kg ≤ 60 → qua. Luật mới: 83,33 > 60 → chặn."""
    placements, packages = three_tiers(sigma_b=1000, k_c=60)
    assert stacking(placements, packages).violation_code == VC.STACK_PRESSURE_EXCEEDED
    monkeypatch.setattr(settings, "PHYSICS_RULES_V2", False)
    old = [p.model_copy(update={"max_stack_weight_kg": 1000}) if p.id == "B" else p for p in packages]
    assert stacking(placements, old).passed


def test_h7_sigma_sources(v2):
    """σ đã khai > dễ vỡ (0) > K / A_top (V55, spec 8.2) > không có số (không nhận tải)."""
    assert CE.top_load_capacity(pkg("x", top_load_capacity_kg_m2=250, fragile=True, max_stack_weight_kg=9)) == 250
    assert CE.top_load_capacity(pkg("x", fragile=True, max_stack_weight_kg=60)) == 0
    assert CE.top_load_capacity(pkg("x", l=60, w=40, max_stack_weight_kg=120)) == pytest.approx(500)  # 120 / 0,24
    assert CE.top_load_capacity(pkg("x")) is None
    bottom, top = pkg("B"), pkg("T", weight=1)
    assert stacking([at(bottom), at(top, z=50)], [bottom, top]).violation_code == VC.STACK_PRESSURE_EXCEEDED
    fragile = pkg("F", fragile=True, top_load_capacity_kg_m2=40)  # σ đã khai thắng cờ dễ vỡ (HC-08 bỏ)
    assert stacking([at(fragile), at(top, z=50)], [fragile, top]).passed


def test_h7_fragile_flag_alone_is_not_checked_separately(v2):
    """HC-08 bỏ: kiện dễ vỡ có σ > 0 nhận được tải tới σ; check_all không còn FRAGILITY_VIOLATION."""
    fragile = pkg("F", fragile=True, top_load_capacity_kg_m2=100)
    top = pkg("T", weight=50)  # 50 / 1 m² = 50 ≤ 100
    context = {"vehicle": vehicle(), "existing_placements": [at(fragile)], "packages_map": {"F": fragile, "T": top}}
    assert CE.check_all(at(top, z=50), context) == []


# ---------------------------------------------------------------------------------------------------- H8 đỡ đáy

def test_h8_support_ratio_per_package(v2):
    """Kiện 100×100 đặt lệch 25 cm khỏi kiện đỡ → được đỡ 75 × 100 = 75 %."""
    base = pkg("B", top_load_capacity_kg_m2=1000)
    vt = vehicle()

    def check(top):
        ctx = {"vehicle": vt, "existing_placements": [at(base)], "packages_map": {"B": base, top.id: top}}
        return [r.violation_code for r in CE.check_all(at(top, x=25, z=50), ctx)]

    assert check(pkg("T")) == []                                   # ρ mặc định 0,70 ≤ 0,75
    assert check(pkg("T", min_support_ratio=0.8)) == ["INSUFFICIENT_SUPPORT_AREA"]
    assert check(pkg("T", min_support_ratio=0.75)) == []           # đúng bằng


def test_h8_unload_check_uses_the_same_ratio(v2):
    """Sau khi dỡ kiện đỡ phụ, kiện còn lại chỉ được đỡ 75 % → hợp lệ với ρ 0,70, không hợp lệ với ρ 0,80."""
    vt = vehicle(inner_l=300, inner_w=100, inner_h=100, door_w=100, door_h=100)
    a, b = pkg("A", l=150, w=100, stop=2, top_load_capacity_kg_m2=1000), pkg("B", l=50, w=100, stop=1,
                                                                              top_load_capacity_kg_m2=1000)

    def unload(rho):
        top = pkg("T", l=100, w=100, stop=2, min_support_ratio=rho)
        # T nằm trên A (x 75–150) và B (x 150–175) → sau khi dỡ B (điểm 1) chỉ còn A đỡ 75 %.
        pl = [at(a, x=0), at(b, x=150), at(top, x=75, z=50)]
        return UnloadFeasibilityValidator.validate(pl, {"A": a, "B": b, "T": top}, vt).passed

    assert unload(None)
    assert not unload(0.8)


# ---------------------------------------------------------------------------------------------------- H10 tải sàn

def test_h10_floor_load(v2):
    """
    Kiện sàn 50×50 (0,25 m²) 100 kg: 100 / 0,25 = 400 kg/m² ≤ 500.
    Thêm 30 kg lên trên: (100 + 30) / 0,25 = 520 > 500 → FLOOR_LOAD_EXCEEDED. Xe không khai q_sàn: không kiểm.
    """
    floor = pkg("F", l=50, w=50, weight=100, top_load_capacity_kg_m2=10_000)
    top = pkg("T", l=50, w=50, weight=30)
    assert CE.check_stacking(at(floor), [], {"F": floor}, floor_load_capacity_kg_m2=500).passed
    result = stacking([at(floor), at(top, z=50)], [floor, top], floor_load_capacity_kg_m2=500)
    assert result.violation_code == VC.FLOOR_LOAD_EXCEEDED and "520.0 kg/m²" in result.detail
    assert stacking([at(floor), at(top, z=50)], [floor, top]).passed

    ctx = {"vehicle": vehicle(floor_load_capacity_kg_m2=500), "existing_placements": [at(floor)],
           "packages_map": {"F": floor, "T": top}}
    assert [r.violation_code for r in CE.check_all(at(top, z=50), ctx)] == [VC.FLOOR_LOAD_EXCEEDED]


# ---------------------------------------------------------------------------------------------------- H11 lệch ngang

def test_h11_lateral_only(v2):
    """Xe rộng 240, r_y 0,10 → cho lệch 24 cm. Tâm kiện ở y = 150 → lệch 30 cm = 12,5 % → chặn; lệch dọc không xét."""
    box = pkg("A", l=40, w=60, weight=500)
    lopsided_y = [at(box, x=290, y=120)]           # tâm y = 150
    far_back_x = [at(box, x=570, y=90)]            # tâm y = 120 (giữa), tâm x = 590 (lệch dọc 45 %)
    m = {"A": box}
    assert CE.check_cog(lopsided_y, vehicle(max_cog_offset_ratio_y=0.10), m).violation_code == \
        "CENTER_OF_GRAVITY_OUT_OF_RANGE"
    assert CE.check_cog(lopsided_y, vehicle(), m).passed     # trống r_y → 0,15 cũ: 12,5 % ≤ 15 %
    assert CE.check_cog(far_back_x, vehicle(max_cog_offset_ratio_y=0.10), m).passed


# ---------------------------------------------------------------------------------------------------- H12 mã mới

def test_h12_code_and_alias(v2):
    vt = vehicle(inner_l=300, inner_w=100, inner_h=50, door_w=100, door_h=50)
    early, late = pkg("E", w=100, stop=1), pkg("L", w=100, stop=2)
    blocked = [at(early, x=0), at(late, x=150)]     # kiện giao sau chắn kiện giao trước, kín cả tiết diện
    result = UnloadFeasibilityValidator.validate(blocked, {"E": early, "L": late}, vt)
    assert result.violation_code == VC.LIFO_BLOCKED
    assert VC.canonical("MULTI_DROP_LIFO_VIOLATION") == VC.canonical(result.violation_code) == VC.LIFO_BLOCKED
    assert VC.canonical("STACKING_WEIGHT_EXCEEDED") == VC.canonical("FRAGILITY_VIOLATION") == VC.STACK_PRESSURE_EXCEEDED
    assert VC.canonical("NO_FEASIBLE_SPACE") == "NO_FEASIBLE_SPACE" and VC.canonical(None) is None


# ---------------------------------------------------------------------------------------------------- H13 kiện bắt buộc

def problem(packages, **kw):
    return ProblemRequest(vehicle=kw.pop("vehicle", vehicle()), packages=packages,
                          stops=[StopData(id=f"S{s}", sequence=s) for s in sorted({p.stop_index for p in packages})],
                          time_limit_sec=10, **kw)


def test_h13_must_load_is_considered_first(v2):
    """Thùng chỉ vừa một kiện. Luật cũ: kiện to xét trước. Luật mới: kiện bắt buộc (nhỏ hơn) được xếp."""
    vt = vehicle(inner_l=100, inner_w=100, inner_h=100, door_w=100, door_h=100, front_axle_x=10, rear_axle_x=90)
    big, small = pkg("big", h=100, weight=10), pkg("small", h=90, weight=10, must_load=True)
    result = OptimizationClient().solve_in_process(problem([big, small], vehicle=vt))
    assert [str(p.package_id) for p in result.placements] == ["small"]


def test_h13_plan_without_a_must_load_package_is_invalid(v2):
    """Kiện bắt buộc quá cao cho thùng → cả phương án 0 kiện; kiện bắt buộc mang mã H13 cạnh lý do riêng."""
    vt = vehicle(inner_h=100, door_h=100)
    ok, too_tall = pkg("ok", weight=10), pkg("tall", h=120, weight=10, must_load=True)
    result = OptimizationClient().solve_in_process(problem([ok, too_tall], vehicle=vt))
    assert result.placements == []
    reasons = {str(u.package_id): u.violated_constraints for u in result.unplaced}
    assert reasons["tall"][-1] == VC.MUST_LOAD_UNPLACED and len(reasons["tall"]) >= 2
    assert reasons["ok"] == [VC.MUST_LOAD_UNPLACED]


def test_h13_fixed_layout_from_another_solver_is_rejected(v2):
    vt = vehicle()
    a, b = pkg("a"), pkg("b", must_load=True)
    layout, reason = OptimizationClient().evaluate_fixed_layout(problem([a, b], vehicle=vt), [at(a, x=200, y=70)],
                                                                "LUMO_R1_1")
    assert layout is None and reason == VC.MUST_LOAD_UNPLACED


# ---------------------------------------------------------------------------------------------------- cờ tắt

def test_flag_off_keeps_old_rules():
    """Mặc định tắt: kg (HC-07), dễ vỡ (HC-08), ρ 0,70, trọng tâm dọc, mã cũ, không có H10 / H13."""
    assert settings.PHYSICS_RULES_V2 is False
    placements, packages = three_tiers(sigma_b=1, sigma_c=1)          # σ bị bỏ qua
    packages = [p.model_copy(update={"max_stack_weight_kg": 1000}) for p in packages]
    assert stacking(placements, packages).passed
    fragile = pkg("F", fragile=True, top_load_capacity_kg_m2=100, max_stack_weight_kg=1000)
    top = pkg("T", weight=1)
    ctx = {"vehicle": vehicle(floor_load_capacity_kg_m2=1), "existing_placements": [at(fragile)],
           "packages_map": {"F": fragile, "T": top}}
    assert [r.violation_code for r in CE.check_all(at(top, z=50), ctx)] == ["FRAGILITY_VIOLATION"]
    assert CE.support_ratio_for(pkg("x", min_support_ratio=0.95)) == 0.70
    box = pkg("A", l=40, w=60, weight=500)
    assert not CE.check_cog([at(box, x=570, y=90)], vehicle(max_cog_offset_ratio_y=0.5), {"A": box}).passed
    vt = vehicle(inner_h=100, door_h=100)
    result = OptimizationClient().solve_in_process(problem([pkg("ok"), pkg("tall", h=120, must_load=True)],
                                                           vehicle=vt))
    assert [str(p.package_id) for p in result.placements] == ["ok"]
    assert not validate_plan(result.placements, vt, {"ok": pkg("ok"), "tall": pkg("tall", h=120)}, 1e18)
