"""
Ba phương án ứng viên mỗi job (A MAX_VOLUME · B AXLE_BALANCE · C MIN_REHANDLING)
và trạng thái job theo ba phương án.
"""
import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.dto.optimization.engine.engine_response import (
    CandidatePlanData,
    EngineOptimizationResponse,
    MetricsData,
    PlacementData,
    UnplacedData,
)
from app.dto.optimization.engine.problem_request import (
    PackageData,
    ProblemRequest,
    StopData,
    VehicleData,
)
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.optimization_job import OptimizationJob
from app.service.optimize.candidate_plans import (
    LayoutStrategy,
    axle_imbalance,
    job_status_of,
    layout_strategies_for,
    pick_candidates,
)
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.optimization_result_persistence_service import (
    OptimizationResultPersistenceService,
)

# Giới hạn trục 6.000 / 10.000 kg, tải rỗng 2.000 / 2.000 kg.
VEHICLE = VehicleData(
    inner_l=600, inner_w=200, inner_h=200, max_payload_kg=5000,
    door_w=200, door_h=200,
    front_axle_x=-100, rear_axle_x=450,
    front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
    empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
)


def _layout(packed, volume, front=None, rear=None, rehandling=0, unplaced=0):
    placements = [
        PlacementData(package_id=str(i + 1), x=i * 10, y=0, z=0, packed_l=10, packed_w=10, packed_h=10)
        for i in range(packed)
    ]
    return EngineOptimizationResponse(
        placements=placements,
        unplaced=[UnplacedData(package_id=f"u{i}", reason="NO_SPACE") for i in range(unplaced)],
        metrics=MetricsData(
            volume_utilization=volume,
            packed_count=packed,
            front_axle_load=front,
            rear_axle_load=rear,
            rehandling_count=rehandling,
        ),
    )


class TestPickCandidates:
    def test_three_plans_in_order_a_b_c_with_objectives(self):
        layouts = [_layout(5, 0.5, 3000, 5000)]
        candidates = pick_candidates(layouts, VEHICLE)
        assert [c.label for c in candidates] == ["A", "B", "C"]
        assert [c.objective for c in candidates] == ["MAX_VOLUME", "AXLE_BALANCE", "MIN_REHANDLING"]

    def test_same_layout_for_every_objective_gives_identical_plans(self):
        only = _layout(5, 0.5, 3000, 5000)
        candidates = pick_candidates([only], VEHICLE)
        assert all(c.result is only for c in candidates)

    def test_each_objective_picks_best_layout_by_its_own_metric(self):
        # Mức dùng trục: |front/6000 − rear/10000|.
        big = _layout(5, 0.60, front=4800, rear=4000, rehandling=3)      # lệch 0,40
        balanced = _layout(5, 0.55, front=3000, rear=5000, rehandling=2)  # lệch 0,00
        tidy = _layout(5, 0.50, front=4200, rear=4000, rehandling=0)     # lệch 0,30
        a, b, c = pick_candidates([big, balanced, tidy], VEHICLE)
        assert a.result is big
        assert b.result is balanced
        assert c.result is tidy

    def test_b_and_c_never_trade_packages_for_their_metric(self):
        # Cách xếp cân trục nhất và ít dỡ-xếp lại nhất lại xếp ít kiện hơn: không được chọn.
        more = _layout(6, 0.60, front=4800, rear=4000, rehandling=3)
        fewer = _layout(4, 0.40, front=3000, rear=5000, rehandling=0)
        a, b, c = pick_candidates([more, fewer], VEHICLE)
        assert a.result is more and b.result is more and c.result is more

    def test_tie_keeps_first_layout(self):
        first = _layout(5, 0.5, 3000, 5000, rehandling=1)
        second = _layout(5, 0.5, 3000, 5000, rehandling=1)
        assert all(c.result is first for c in pick_candidates([first, second], VEHICLE))

    def test_axle_imbalance_without_limits_uses_relative_difference(self):
        vehicle = VEHICLE.model_copy(update={"front_axle_limit_kg": None, "rear_axle_limit_kg": None})
        assert axle_imbalance(_layout(1, 0.1, front=3000, rear=1000), vehicle) == pytest.approx(0.5)
        assert axle_imbalance(_layout(0, 0.0), vehicle) is None

    def test_axle_target_strategy_only_when_vehicle_declares_axles(self):
        assert LayoutStrategy.AXLE_TARGET in layout_strategies_for(VEHICLE)
        bare = VehicleData(inner_l=600, inner_w=200, inner_h=200, max_payload_kg=5000)
        assert LayoutStrategy.AXLE_TARGET not in layout_strategies_for(bare)


class TestJobStatus:
    def _candidates(self, *layouts):
        return [
            CandidatePlanData(label=label, objective="MAX_VOLUME", result=layout)
            for label, layout in zip("ABC", layouts)
        ]

    def test_no_solution_when_all_three_place_nothing(self):
        empty = _layout(0, 0.0, unplaced=3)
        assert job_status_of(self._candidates(empty, empty, empty)) == "NO_SOLUTION"

    def test_partial_when_no_plan_places_everything(self):
        part = _layout(2, 0.2, unplaced=1)
        assert job_status_of(self._candidates(part, part, part)) == "PARTIAL"

    def test_completed_when_some_plan_places_everything(self):
        part = _layout(2, 0.2, unplaced=1)
        full = _layout(3, 0.3)
        assert job_status_of(self._candidates(part, full, part)) == "COMPLETED"

    def test_handler_classifies_by_candidates(self):
        handler = EngineExceptionHandler(job_service=object(), notification_service=False, credit_client=False)
        empty = _layout(0, 0.0, unplaced=1)
        result = empty.model_copy(update={"candidates": self._candidates(empty, empty, empty)})
        assert handler.classify_result(result).value == "NO_SOLUTION"


def _problem(time_limit_sec=60):
    packages = [
        PackageData(id=str(i), l=50, w=50, h=50, weight=80, stop_index=1 + i % 2,
                    allowed_rotations=[0], rotation_allowed=False)
        for i in range(1, 9)
    ]
    return ProblemRequest(
        vehicle=VEHICLE,
        packages=packages,
        stops=[StopData(id="1", sequence=1), StopData(id="2", sequence=2)],
        time_limit_sec=time_limit_sec,
    )


class TestSolveCandidates:
    @pytest.mark.asyncio
    async def test_in_process_solve_returns_plan_a_with_three_candidates(self):
        result = await OptimizationClient(engine_url="").solve(_problem())
        assert [c.label for c in result.candidates] == ["A", "B", "C"]
        assert result.placements == result.candidates[0].result.placements
        most = max(len(c.result.placements) for c in result.candidates)
        # B và C xếp đủ số kiện nhiều nhất trong các cách đã thử.
        assert len(result.candidates[1].result.placements) == most
        assert len(result.candidates[2].result.placements) == most

    def test_candidates_are_not_serialized(self):
        result = OptimizationClient(engine_url="").solve_candidates(_problem())
        assert "candidates" not in result.model_dump()

    def test_time_limit_covers_whole_run_first_layout_always_runs(self, monkeypatch):
        client = OptimizationClient(engine_url="")
        calls = []
        original = client.solve_in_process

        def counting(problem, strategy=LayoutStrategy.CENTER_FIRST):
            calls.append(strategy)
            return original(problem, strategy=strategy)

        monkeypatch.setattr(client, "solve_in_process", counting)
        ticks = iter([0.0] + [999.0] * 10)  # lúc bắt đầu, rồi đã quá giờ
        monkeypatch.setattr(
            "app.service.optimize.optimization_client.time.monotonic", lambda: next(ticks)
        )
        result = client.solve_candidates(_problem(time_limit_sec=1))
        assert calls == [LayoutStrategy.CENTER_FIRST]
        assert len(result.candidates) == 3


@pytest.fixture
def session():
    engine = create_engine(
        "sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool
    )
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    job = OptimizationJob(job_uuid=str(uuid.uuid4()), trip_id=1, status="RUNNING")
    db.add(job)
    db.commit()
    yield db, job
    db.close()


class TestPersistCandidates:
    def test_saves_three_plans_in_order_with_label_and_objective(self, session):
        db, job = session
        a = _layout(3, 0.3)
        b = _layout(2, 0.2, unplaced=1)
        result = a.model_copy(update={"candidates": [
            CandidatePlanData(label="A", objective="MAX_VOLUME", result=a),
            CandidatePlanData(label="B", objective="AXLE_BALANCE", result=b),
            CandidatePlanData(label="C", objective="MIN_REHANDLING", result=a),
        ]})

        OptimizationResultPersistenceService().save(job.job_uuid, result, db)

        plans = db.query(LoadPlan).filter(LoadPlan.job_id == job.id).order_by(LoadPlan.id).all()
        assert [(p.candidate_label, p.objective, p.packed_items_count) for p in plans] == [
            ("A", "MAX_VOLUME", 3), ("B", "AXLE_BALANCE", 2), ("C", "MIN_REHANDLING", 3),
        ]
        assert len(plans[0].placements) == 3 and len(plans[1].unplaced) == 1

    def test_no_solution_saves_one_plan_without_label(self, session):
        db, job = session
        empty = _layout(0, 0.0, unplaced=2)
        result = empty.model_copy(update={"candidates": [
            CandidatePlanData(label=label, objective="MAX_VOLUME", result=empty) for label in "ABC"
        ]})

        OptimizationResultPersistenceService().save(job.job_uuid, result, db)

        plans = db.query(LoadPlan).filter(LoadPlan.job_id == job.id).all()
        assert len(plans) == 1
        assert plans[0].candidate_label is None and plans[0].objective is None
        assert len(plans[0].unplaced) == 2
