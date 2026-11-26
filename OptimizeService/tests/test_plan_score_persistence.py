"""
V57 — lưu điểm J, hồ sơ, nhãn trùng của phương án và lý do đặt của từng kiện (cờ PLAN_SCORE_J).

Seam: OptimizationResultPersistenceService.save(job_uuid, result, db) với kết quả thật của
OptimizationClient.solve_candidates. Cờ bật: cột có giá trị ở cả ba phương án A · B · C. Cờ tắt: cột để trống.
"""
import math

import pytest

from app.config.settings import settings
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    MetricsData,
    PlacementData,
    PlanScoreData,
)
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, StopData, VehicleData
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.optimization_result_persistence_service import OptimizationResultPersistenceService
from tests.test_optimization_result_persistence import db_session, sample_job  # noqa: F401 (fixture)

FACT_COLUMNS = ("stack_pressure_kg_m2", "top_load_capacity_kg_m2", "support_ratio", "distance_to_door_cm")


def problem() -> ProblemRequest:
    """Hai kiện của fixture DB (mã 1, 2; loại BOX_M 50 × 40 × 30) cho một điểm giao, thùng 400 × 200 × 200."""
    vehicle = VehicleData(inner_l=400, inner_w=200, inner_h=200, door_w=200, door_h=200, max_payload_kg=3000,
                          max_cog_offset_ratio=0.15, front_axle_x=50, rear_axle_x=300, front_axle_limit_kg=2000,
                          rear_axle_limit_kg=3000, empty_front_axle_load_kg=1000, empty_rear_axle_load_kg=1500)
    packages = [
        PackageData(id=str(i), l=50, w=40, h=30, weight=w, stop_index=1, rotation_allowed=False,
                    allowed_rotations=[0], top_load_capacity_kg_m2=5000)
        for i, w in ((1, 15), (2, 20))
    ]
    return ProblemRequest(vehicle=vehicle, packages=packages, stops=[StopData(id="1", sequence=1)], time_limit_sec=5)


def saved_plans(db, job):
    plans = db.query(LoadPlan).filter(LoadPlan.job_id == job.id).order_by(LoadPlan.id).all()
    return plans, {
        plan.id: db.query(PackagePlacement).filter(PackagePlacement.load_plan_id == plan.id).all()
        for plan in plans
    }


def test_flag_on_stores_score_profile_duplicate_and_reasons_for_every_candidate(db_session, sample_job, monkeypatch):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", True)
    result = OptimizationClient(engine_url=None).solve_candidates(problem())

    OptimizationResultPersistenceService().save(sample_job.job_uuid, result, db_session)

    plans, placements = saved_plans(db_session, sample_job)
    assert [p.candidate_label for p in plans] == ["A", "B", "C"]
    for plan, candidate in zip(plans, result.candidates):
        assert plan.candidate_profile == candidate.label
        assert plan.duplicate_of == candidate.duplicate_of
        score = plan.plan_score
        assert score["profile"] == candidate.label
        assert score["j"] == pytest.approx(candidate.result.plan_score.j)
        assert set(score) >= {"profile_name", "assumed_weights", "u_vol", "u_n", "w_vol", "w_n", "components"}
        assert {c["code"] for c in score["components"]} >= {"S1"}
        assert placements[plan.id], "phương án xếp được kiện"
        for row in placements[plan.id]:
            assert row.reason_codes and all(isinstance(code, str) for code in row.reason_codes)
            for column in FACT_COLUMNS:
                assert getattr(row, column) is not None, column
            assert float(row.top_load_capacity_kg_m2) == 5000
    # Phương án A không trùng phương án nào trước nó.
    assert plans[0].duplicate_of is None


def test_flag_off_leaves_the_new_columns_empty(db_session, sample_job, monkeypatch):
    monkeypatch.setattr(settings, "PLAN_SCORE_J", False)
    result = OptimizationClient(engine_url=None).solve_candidates(problem())

    OptimizationResultPersistenceService().save(sample_job.job_uuid, result, db_session)

    plans, placements = saved_plans(db_session, sample_job)
    assert [p.candidate_label for p in plans] == ["A", "B", "C"]
    for plan in plans:
        assert plan.plan_score is None
        assert plan.candidate_profile is None
        assert plan.duplicate_of is None
        assert placements[plan.id]
        for row in placements[plan.id]:
            assert row.reason_codes is None
            for column in FACT_COLUMNS:
                assert getattr(row, column) is None, column


def test_non_finite_numbers_are_stored_as_empty(db_session, sample_job):
    """jsonb không nhận NaN và NUMERIC có độ chính xác không nhận vô cực: các số đó thành trống, không làm hỏng job."""
    result = EngineOptimizationResponse(
        placements=[PlacementData(package_id="1", x=0, y=0, z=0, packed_l=50, packed_w=40, packed_h=30,
                                  reason_codes=["HEAVY_LOW", " "], stack_pressure_kg_m2=math.inf,
                                  top_load_capacity_kg_m2=None, support_ratio=1.0, distance_to_door_cm=math.nan)],
        metrics=MetricsData(packed_count=1),
        plan_score=PlanScoreData(profile="A", profile_name="Chở nhiều", j=math.nan, u_vol=0.1, u_n=1.0,
                                 w_vol=2.0, w_n=0.5),
    )

    plan = OptimizationResultPersistenceService().save(sample_job.job_uuid, result, db_session)

    assert plan.plan_score["j"] is None and plan.plan_score["u_vol"] == 0.1
    row = db_session.query(PackagePlacement).filter(PackagePlacement.load_plan_id == plan.id).one()
    assert row.reason_codes == ["HEAVY_LOW"]
    assert row.stack_pressure_kg_m2 is None and row.distance_to_door_cm is None
    assert row.top_load_capacity_kg_m2 is None and float(row.support_ratio) == 1.0
