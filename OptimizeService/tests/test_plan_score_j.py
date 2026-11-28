"""M6 — hàm điểm J, hồ sơ ưu tiên, A/B/C theo J, lý do đặt (spec mục 6, 7.3). Cờ PLAN_SCORE_J."""
import pytest

from app.config.settings import settings
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData
from app.dto.optimization.engine.problem_request import (
    PackageData, PlanProfileRequest, ProblemRequest, StopData, VehicleData,
)
from app.service.optimize import plan_score as PS
from app.service.optimize.candidate_plans import pick_candidates
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.placement_facts import annotate_placements


@pytest.fixture
def j_on(monkeypatch):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", True)


def vehicle(**kw):
    data = dict(inner_l=400, inner_w=200, inner_h=200, door_w=200, door_h=200, max_payload_kg=3000,
                max_cog_offset_ratio=0.15, front_axle_x=50, rear_axle_x=300, front_axle_limit_kg=2000,
                rear_axle_limit_kg=3000, empty_front_axle_load_kg=1000, empty_rear_axle_load_kg=1500)
    data.update(kw)
    return VehicleData(**data)


def pkg(id, l=100, w=100, h=100, weight=50, stop=1, **kw):
    return PackageData(id=id, l=l, w=w, h=h, weight=weight, stop_index=stop, rotation_allowed=False,
                       allowed_rotations=[0], **kw)


def at(p, x=0, y=0, z=0):
    return PlacementData(package_id=p.id, x=x, y=y, z=z, packed_l=p.l, packed_w=p.w, packed_h=p.h,
                         rotation_type=0, step_sequence=1)


def hand_example(**vkw):
    """
    Thùng 400 × 200 × 200 (16 m³ = 16·10⁶ cm³), hai điểm giao. N = 3, xếp cả ba.
      A 100×100×100, 100 kg, điểm 2, góc (0, 0, 0);
      B 100×100×100,  50 kg, điểm 1, priority 3, góc (280, 100, 0);
      C 100×100×50,   50 kg, điểm 2, góc (200, 0, 0).
    Vùng (thể tích): điểm 2 = 1,5/2,5 · 390 = 234 → [0; 234]; điểm 1 → [244; 400].
      S1  tâm X của C = 250 ∈ vùng điểm 1 → R = 1 → V₁ = 1/3.
      S2  ȳ = (100·50 + 50·150 + 50·50)/200 = 75; |75 − 100| / (0,15·200) = 25/30 = 5/6.
      S3  hàng sau trục = Σm(x̄ − 50)/250 = (0 + 50·280 + 50·200)/250 = 96 kg; trước = 104 kg;
          |(1000 + 104)/2000 − (1500 + 96)/3000| = |0,552 − 0,532| = 0,02.
      S4  z̄ = (100·50 + 50·50 + 50·25)/200 = 43,75 → /200 = 0,21875.
      S5  nhóm = điểm giao: điểm 2 có A và C cách nhau 100 cm → 2 cụm; điểm 1: 1 cụm → (3 − 2)/3 = 1/3.
      S6  B: (400 − 380)/400 = 0,05.
      U_vol = 2,5·10⁶ / 16·10⁶ = 0,15625; U_n = 1.
    J(default) = 100·(0,15625 + 0,5 − (0,5/3 + 0,15·5/6 + 0,15·0,02 + 0,05·0,21875 + 0,1/3 + 0,1·0,05))
               = 100·(0,65625 − 0,3439375) = 31,23125   (trọng số chốt 26/11/2026, Q-D4)
    """
    a, b, c = pkg("A", weight=100, stop=2), pkg("B", stop=1, priority=3), pkg("C", h=50, stop=2)
    problem = ProblemRequest(vehicle=vehicle(**vkw), packages=[a, b, c],
                             stops=[StopData(id="S1", sequence=1), StopData(id="S2", sequence=2)])
    return problem, [at(a), at(b, x=280, y=100), at(c, x=200)]


def test_j_hand_example():
    problem, placements = hand_example()
    s = PS.score(problem, placements)
    assert s.profile.code == "default"
    assert s.u_vol == pytest.approx(0.15625) and s.u_n == pytest.approx(1.0)
    expected = {"S1": 1 / 3, "S2": 5 / 6, "S3": 0.02, "S4": 0.21875, "S5": 1 / 3, "S6": 0.05, "S7": 0.0, "S8": 0.0}
    for code, value in expected.items():
        assert s.values[code] == pytest.approx(value, abs=1e-12), code
    assert s.j == pytest.approx(100 * (0.65625 - (0.5 / 3 + 0.15 * 5 / 6 + 0.15 * 0.02 + 0.05 * 0.21875 + 0.1 / 3
                                                  + 0.1 * 0.05)))
    assert s.j == pytest.approx(31.23125, abs=1e-6)
    data = PS.to_data(s)
    assert sum(c.contribution for c in data.components) == pytest.approx(s.j - 100 * (0.15625 + 0.5))
    assert [c.computed for c in data.components] == [True] * 6 + [False, False]


def test_s3_not_computed_without_axle_data():
    problem, placements = hand_example(front_axle_limit_kg=None)
    s = PS.score(problem, placements)
    assert s.values["S3"] == 0.0 and s.computed["S3"] is False


def test_profiles_from_config_and_request_override():
    known = PS.profiles()
    assert set(known) == {"default", "A", "B", "C"} and not any(p.assumed for p in known.values())
    assert known["A"].w_vol > known["default"].w_vol
    assert known["C"].weights[0] > known["default"].weights[0]          # S1
    custom = PS.resolve_profile(PlanProfileRequest(code="B", weights={"S1": 5.0}))
    assert custom.code == "B*" and custom.weights[0] == 5.0 and custom.weights[1] == known["B"].weights[1]
    with pytest.raises(ValueError):
        PS.resolve_profile(PlanProfileRequest(code="Z"))
    with pytest.raises(ValueError):
        PS.resolve_profile(PlanProfileRequest(weights={"S9": 1.0}))


def test_empty_plan_has_no_score():
    problem, _ = hand_example()
    assert PS.selection_key(problem, []) == (False, 0, 0.0)
    assert PS.selection_scalar(problem, []) == PS.NO_SCORE
    assert PS.score(problem, []).j == 0.0


def test_abc_by_j_marks_duplicates(j_on):
    problem, placements = hand_example()
    full = EngineOptimizationResponse(placements=placements)
    fewer = EngineOptimizationResponse(placements=placements[:2])
    out = pick_candidates([fewer, full], problem.vehicle, problem)
    assert [c.label for c in out] == ["A", "B", "C"] and [c.profile for c in out] == ["A", "B", "C"]
    assert all(len(c.result.placements) == 3 for c in out)       # pack_count_first: đủ kiện trước
    assert [c.duplicate_of for c in out] == [None, "A", "A"]
    assert out[1].result.plan_score.profile == "B"


def test_pure_j_may_leave_a_package_behind(j_on, monkeypatch):
    """
    Ghi lại hệ quả của trọng số (chốt 26/11/2026): bỏ C (đặt ngoài vùng, tách nhóm) làm J cao hơn ở hồ sơ default, B, C
    (default 31,23 → 35,32; B −1,92 → 11,14; C −16,44 → 34,32); hồ sơ A vẫn giữ đủ kiện (58,92 > 48,91).
    Vì vậy sản phẩm giữ pack_count_first = true cho cả 4 hồ sơ.
    """
    problem, placements = hand_example()
    for code in ("default", "B", "C"):
        profile = PS.profiles()[code]
        assert PS.score(problem, placements[:2], profile).j > PS.score(problem, placements, profile).j
    profile = PS.profiles()["A"]
    assert PS.score(problem, placements[:2], profile).j < PS.score(problem, placements, profile).j
    monkeypatch.setitem(PS._config(), "pack_count_first", False)
    out = pick_candidates([EngineOptimizationResponse(placements=placements[:2]),
                           EngineOptimizationResponse(placements=placements)], problem.vehicle, problem)
    assert [len(c.result.placements) for c in out] == [3, 2, 2]


def test_flag_off_keeps_old_selection():
    assert settings.PLAN_SCORE_J is False
    problem, placements = hand_example()
    out = pick_candidates([EngineOptimizationResponse(placements=placements)], problem.vehicle, problem)
    assert all(c.profile is None and c.duplicate_of is None and c.result.plan_score is None for c in out)


def test_placement_facts():
    a, b = pkg("A", weight=100, stop=2, top_load_capacity_kg_m2=5000), pkg("B", l=50, w=50, h=50, weight=20, stop=2)
    problem = ProblemRequest(vehicle=vehicle(), packages=[a, b], stops=[StopData(id="S2", sequence=2)])
    facts = {str(p.package_id): p for p in annotate_placements(problem, [at(a), at(b, z=100)])}
    assert facts["A"].stack_pressure_kg_m2 == pytest.approx(20 / 0.25)     # 20 kg trên 50×50 cm
    assert facts["A"].top_load_capacity_kg_m2 == 5000 and facts["A"].support_ratio == 1.0
    assert "BOTTOM_HIGH_CAPACITY" in facts["A"].reason_codes and "ZONE_STOP_2" in facts["A"].reason_codes
    assert facts["B"].support_ratio == 1.0 and facts["B"].stack_pressure_kg_m2 == 0.0
    assert facts["B"].distance_to_door_cm == 350.0


def test_solve_candidates_reports_j_and_reasons(j_on):
    problem, _ = hand_example()
    result = OptimizationClient(engine_url=None).solve_candidates(problem.model_copy(update={"time_limit_sec": 5}))
    assert result.plan_score is not None
    for c in result.candidates:
        assert c.result.plan_score.profile == c.label
        if c.result.placements:
            assert all(p.reason_codes for p in c.result.placements)


def test_remote_engine_result_gets_j_from_the_service(j_on):
    from app.dto.optimization.engine.engine_response import CandidatePlanData

    problem, placements = hand_example()
    remote = EngineOptimizationResponse(placements=placements, candidates=[
        CandidatePlanData(label="A", objective="MAX_VOLUME", result=EngineOptimizationResponse(placements=placements)),
    ])
    checked = OptimizationClient._validated_remote(problem, remote)
    assert checked.plan_score.profile == "default"
    assert checked.plan_score.j == pytest.approx(PS.score(problem, placements).j)
    assert checked.candidates[0].profile == "A" and checked.candidates[0].result.plan_score.profile == "A"
    assert all(p.reason_codes for p in checked.placements)
