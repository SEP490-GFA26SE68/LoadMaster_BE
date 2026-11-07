"""
Thuật toán theo gói và tên thuật toán của từng phương án.

Seams: AlgorithmTierService.plan_algorithms_for (gói → các thuật toán được chạy),
OptimizationClient.solve_candidates (mỗi cách xếp mang mã thuật toán đã sinh ra nó),
run_ga_sa (GA + SA giải mã bằng EP + DBLF, giữ kiện ghim, tất định theo seed),
run_lumo_models (ba mô hình Lumo, bỏ qua êm khi thiếu torch hoặc có kiện ghim),
OptimizationClient.evaluate_fixed_layout (kiểm lại cách xếp của AI bằng ConstraintEngine),
OptimizationResultPersistenceService (load_plans.algorithm_name). Đơn vị: cm, kg.
"""
import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.config.settings import settings
from app.constant.optimization.plan_algorithm import PlanAlgorithm
from app.dto.optimization.engine.engine_response import (
    CandidatePlanData,
    EngineOptimizationResponse,
    MetricsData,
    PlacementData,
)
from app.dto.optimization.engine.problem_request import (
    PackageData,
    PinnedData,
    ProblemRequest,
    StopData,
    VehicleData,
)
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.optimization_job import OptimizationJob
from app.ml import lumo_models
from app.service.optimize import optimization_client as client_module
from app.service.optimize.algorithm_tier_service import AlgorithmTierService
from app.service.optimize.candidate_plans import layout_strategies_for
from app.service.optimize.metaheuristic import run_ga_sa
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.optimization_result_persistence_service import (
    OptimizationResultPersistenceService,
)

EP = PlanAlgorithm.EP_DBLF.value
GA = PlanAlgorithm.GA.value
SA = PlanAlgorithm.SA.value
AI_CODES = {
    PlanAlgorithm.EP_LUMO_R1_1.value,
    PlanAlgorithm.LUMO_R1.value,
    PlanAlgorithm.LUMO_R1_1.value,
    PlanAlgorithm.LUMO_R2.value,
}

VEHICLE = VehicleData(
    inner_l=600, inner_w=200, inner_h=200, max_payload_kg=5000,
    door_w=200, door_h=200,
    front_axle_x=-100, rear_axle_x=450,
    front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
    empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
)

SIZES = [(60, 50, 40), (50, 50, 50), (80, 40, 40), (40, 40, 30), (60, 60, 50), (50, 40, 30)]


def _problem(algorithm_name=None, pinned=None, count=10, seed=7) -> ProblemRequest:
    packages = [
        PackageData(id=str(i), l=SIZES[i % len(SIZES)][0], w=SIZES[i % len(SIZES)][1],
                    h=SIZES[i % len(SIZES)][2], weight=20 + 5 * (i % 4), stop_index=1 + i % 2,
                    allowed_rotations=[0, 1])
        for i in range(1, count + 1)
    ]
    return ProblemRequest(
        vehicle=VEHICLE,
        packages=packages,
        stops=[StopData(id="1", sequence=1), StopData(id="2", sequence=2)],
        pinned=pinned or [],
        time_limit_sec=60,
        algorithm_name=algorithm_name,
        seed=seed,
    )


def _boxes_overlap(a: PlacementData, b: PlacementData) -> bool:
    eps = 1e-6
    return (
        a.x < b.x + b.packed_l - eps and b.x < a.x + a.packed_l - eps
        and a.y < b.y + b.packed_w - eps and b.y < a.y + a.packed_w - eps
        and a.z < b.z + b.packed_h - eps and b.z < a.z + a.packed_h - eps
    )


def _assert_physically_valid(layout: EngineOptimizationResponse) -> None:
    for p in layout.placements:
        assert p.x >= -1e-6 and p.y >= -1e-6 and p.z >= -1e-6
        assert p.x + p.packed_l <= VEHICLE.inner_l + 1e-6
        assert p.y + p.packed_w <= VEHICLE.inner_w + 1e-6
        assert p.z + p.packed_h <= VEHICLE.inner_h + 1e-6
    for i, a in enumerate(layout.placements):
        for b in layout.placements[i + 1:]:
            assert not _boxes_overlap(a, b), (a.package_id, b.package_id)
    ids = [str(p.package_id) for p in layout.placements]
    assert len(ids) == len(set(ids))


def _ep_runs(client: OptimizationClient, problem: ProblemRequest):
    return [(s, client.solve_in_process(problem, strategy=s, quiet=True))
            for s in layout_strategies_for(problem.vehicle)]


@pytest.fixture
def fast_meta(monkeypatch):
    monkeypatch.setattr(settings, "METAHEURISTIC_TIME_BUDGET_SEC", 30.0)
    monkeypatch.setattr(settings, "METAHEURISTIC_MAX_EVALUATIONS", 8)


class TestTierRouting:
    def test_plan_algorithms_by_tier(self):
        assert AlgorithmTierService.plan_algorithms_for("EP_DBLF") == [EP]
        assert AlgorithmTierService.plan_algorithms_for(None) == [EP]
        assert AlgorithmTierService.plan_algorithms_for("EP_DBLF_GA") == [EP, GA, SA]
        ultimate = AlgorithmTierService.plan_algorithms_for("EP_DBLF_GA_AI")
        assert ultimate[:3] == [EP, GA, SA] and set(ultimate[3:]) == AI_CODES

    def test_tier_resolved_from_subscription_runs_its_family(self):
        for tier, family in (("BASIC", "BASIC"), ("PRO", "PRO"), ("ULTIMATE", "ULTIMATE")):
            code = AlgorithmTierService.resolve_algorithm(tier)
            assert AlgorithmTierService.family_of(code) == family

    def test_basic_only_ever_runs_ep_dblf(self, fast_meta):
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF"))
        assert result.algorithms_tried == [EP]
        assert {c.result.algorithm_name for c in result.candidates} == {EP}

    def test_pro_runs_ga_and_sa_never_ai(self, fast_meta):
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF_GA"))
        assert result.algorithms_tried[0] == EP
        assert GA in result.algorithms_tried
        assert set(result.algorithms_tried) <= {EP, GA, SA}
        assert {c.result.algorithm_name for c in result.candidates} <= {EP, GA, SA}

    def test_ultimate_without_torch_skips_ai_and_still_finishes(self, fast_meta, monkeypatch):
        monkeypatch.setattr(lumo_models, "torch_available", lambda: False)
        monkeypatch.setattr(client_module, "get_lumo_scorer", lambda: None)
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF_GA_AI"))
        assert not AI_CODES & set(result.algorithms_tried)
        assert GA in result.algorithms_tried
        assert [c.label for c in result.candidates] == ["A", "B", "C"]

    def test_ultimate_survives_ai_crash(self, fast_meta, monkeypatch):
        def boom(*args, **kwargs):
            raise RuntimeError("model exploded")

        monkeypatch.setattr(lumo_models, "run_lumo_models", boom)
        monkeypatch.setattr(client_module, "get_lumo_scorer", lambda: None)
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF_GA_AI"))
        assert len(result.candidates) == 3
        assert not AI_CODES & {c.result.algorithm_name for c in result.candidates}

    def test_ultimate_with_torch_runs_lumo_models(self, fast_meta):
        pytest.importorskip("torch")
        for code in ("LUMO_R1", "LUMO_R1_1", "LUMO_R2"):
            if not lumo_models.model_path(code).is_file():
                pytest.skip(f"thiếu trọng số {code}")
        result = OptimizationClient(engine_url="").solve_candidates(_problem("EP_DBLF_GA_AI"))
        assert {"LUMO_R1", "LUMO_R1_1", "LUMO_R2"} <= set(result.algorithms_tried)
        for candidate in result.candidates:
            assert candidate.result.algorithm_name in {EP, GA, SA} | AI_CODES
            _assert_physically_valid(candidate.result)

    def test_lumo_models_skip_pinned_problem_without_touching_pins(self):
        pins = [PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=0)]
        run = lumo_models.run_lumo_models(
            _problem("EP_DBLF_GA_AI", pinned=pins), ["LUMO_R1", "LUMO_R1_1", "LUMO_R2"],
            OptimizationClient(engine_url=""),
        )
        assert run.layouts == [] and run.tried == []
        assert set(run.skipped.values()) == {"PINNED"}


class TestGaSa:
    def test_layouts_respect_constraints_and_carry_their_code(self):
        client = OptimizationClient(engine_url="")
        problem = _problem("EP_DBLF_GA")
        meta = run_ga_sa(client, problem, _ep_runs(client, problem), budget_sec=30, max_evaluations=10)
        assert meta.evaluations.get(GA, 0) > 0
        assert meta.layouts
        for layout in meta.layouts:
            assert layout.algorithm_name in (GA, SA)
            _assert_physically_valid(layout)
            placed = {str(p.package_id) for p in layout.placements}
            missing = {str(u.package_id) for u in layout.unplaced}
            assert placed | missing == {str(p.id) for p in problem.packages}

    def test_pinned_packages_stay_fixed(self):
        pins = [PinnedData(package_id="3", pos_x=0, pos_y=0, pos_z=0, rotation_type=0)]
        client = OptimizationClient(engine_url="")
        problem = _problem("EP_DBLF_GA", pinned=pins)
        meta = run_ga_sa(client, problem, _ep_runs(client, problem), budget_sec=30, max_evaluations=10)
        assert meta.layouts
        for layout in meta.layouts:
            pinned = [p for p in layout.placements if str(p.package_id) == "3"]
            assert len(pinned) == 1 and pinned[0].pinned is True
            assert (pinned[0].x, pinned[0].y, pinned[0].z) == (0, 0, 0)
            assert layout.placements[0].package_id == pinned[0].package_id
            _assert_physically_valid(layout)

    def test_same_seed_same_result(self):
        client = OptimizationClient(engine_url="")
        problem = _problem("EP_DBLF_GA")
        runs = _ep_runs(client, problem)

        def signature(meta):
            return [
                (layout.algorithm_name,
                 [(str(p.package_id), p.x, p.y, p.z, p.rotation_type) for p in layout.placements])
                for layout in meta.layouts
            ]

        first = run_ga_sa(client, problem, runs, budget_sec=60, max_evaluations=8, seed=11)
        second = run_ga_sa(client, problem, runs, budget_sec=60, max_evaluations=8, seed=11)
        assert signature(first) == signature(second)
        assert first.evaluations == second.evaluations


class TestEvaluateFixedLayout:
    def test_accepts_valid_layout_and_tags_it(self):
        problem = ProblemRequest(
            vehicle=VEHICLE,
            packages=[
                PackageData(id="1", l=50, w=50, h=50, weight=30, stop_index=1, allowed_rotations=[0]),
                PackageData(id="2", l=50, w=50, h=50, weight=30, stop_index=1, allowed_rotations=[0]),
            ],
            stops=[StopData(id="1", sequence=1)],
        )
        # Hai kiện giữa thùng: trọng tâm hàng nằm gần tâm, không vượt trục nào.
        placements = [
            PlacementData(package_id="1", x=250, y=75, z=0, packed_l=50, packed_w=50, packed_h=50),
            PlacementData(package_id="2", x=300, y=75, z=0, packed_l=50, packed_w=50, packed_h=50),
        ]
        layout, reason = OptimizationClient(engine_url="").evaluate_fixed_layout(problem, placements, "LUMO_R1")
        assert reason is None
        assert layout.algorithm_name == "LUMO_R1"
        assert len(layout.placements) == 2 and layout.metrics.packed_count == 2

    def test_rejects_overlap(self):
        problem = _problem(count=2)
        placements = [
            PlacementData(package_id="1", x=0, y=0, z=0, packed_l=60, packed_w=50, packed_h=40),
            PlacementData(package_id="2", x=10, y=0, z=0, packed_l=50, packed_w=40, packed_h=30),
        ]
        layout, reason = OptimizationClient(engine_url="").evaluate_fixed_layout(problem, placements, "LUMO_R2")
        assert layout is None and reason


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


def _layout(packed: int, code: str) -> EngineOptimizationResponse:
    return EngineOptimizationResponse(
        placements=[
            PlacementData(package_id=str(i + 1), x=i * 10, y=0, z=0, packed_l=10, packed_w=10, packed_h=10)
            for i in range(packed)
        ],
        metrics=MetricsData(volume_utilization=0.1 * packed, packed_count=packed),
        algorithm_name=code,
    )


class TestPersistAlgorithmName:
    def test_each_plan_stores_the_algorithm_that_built_it(self, session):
        db, job = session
        a, b, c = _layout(3, GA), _layout(3, EP), _layout(2, "LUMO_R1_1")
        result = a.model_copy(update={"candidates": [
            CandidatePlanData(label="A", objective="MAX_VOLUME", result=a),
            CandidatePlanData(label="B", objective="AXLE_BALANCE", result=b),
            CandidatePlanData(label="C", objective="MIN_REHANDLING", result=c),
        ]})

        OptimizationResultPersistenceService().save(job.job_uuid, result, db)

        plans = db.query(LoadPlan).filter(LoadPlan.job_id == job.id).order_by(LoadPlan.id).all()
        assert [(p.candidate_label, p.algorithm_name) for p in plans] == [
            ("A", GA), ("B", EP), ("C", "LUMO_R1_1"),
        ]

    def test_algorithm_name_is_in_plan_dump_but_tried_list_is_not(self):
        layout = _layout(1, SA).model_copy(update={"algorithms_tried": [EP, GA, SA]})
        dumped = layout.model_dump()
        assert dumped["algorithm_name"] == SA
        assert "algorithms_tried" not in dumped
