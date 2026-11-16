"""
Tests for OptimizationResultPersistenceService — S3-09

Seams under test:
  - OptimizationResultPersistenceService.save(job_uuid, result, db) -> LoadPlan
  - Transaction atomicity: rollback on failure preserves DB consistency
  - LoadPlanService.get_plans_for_job(job_uuid, db) -> List[LoadPlanResponse]
  - Integration: POST /api/v1/optimization/jobs -> poll GET /jobs/{id}/plans

Acceptance Criteria:
  - INSERT load_plans (plan_name, packed_items_count, volume_utilization, weight_utilization)
  - INSERT package_placements cho mỗi placed package (pos_x/y/z, packed_l/w/h, rotation_type, step_sequence)
  - INSERT unplaced_packages cho mỗi unplaced (package_id, reason)
  - Transaction: tất cả insert trong 1 transaction
"""
import uuid
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient
from unittest.mock import patch
from app.controller.optimization.optimization_router import get_async_runner
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.main import app
from tests.optimization_legacy_fixtures import (spring_reserved_job, billing_snapshot, restored_overrides, service_token, make_offline_runner)
from app.config.database import Base, get_db
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    PlacementData,
    UnplacedData,
    MetricsData,
)
from app.service.auth.auth_dependency import require_optimization_service
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.optimization.unplaced_package import UnplacedPackage
from app.entity.trip_model import (
    Trip, Vehicle, VehicleType, DeliveryStop, TransportOrder, CargoPackage, PackageType,
    DeliveryRequirement, DeliveryRequirementPackage,
)
from app.service.optimize.load_plan_service import LoadPlanService
from app.service.optimize.optimization_result_persistence_service import OptimizationResultPersistenceService


@pytest.fixture
def db_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        echo=False,
    )
    Base.metadata.create_all(engine)
    TestingSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    session = TestingSession()

    # Tạo trip + vehicle + packages
    vt = VehicleType(max_cog_offset_ratio=0.15, id=1, name="Van 1 Tan", inner_length=300, inner_width=180, inner_height=180, door_width=180, door_height=180, max_payload_kg=1000.0,
                     front_axle_x=50, rear_axle_x=250, front_axle_limit_kg=2000, rear_axle_limit_kg=3000,
                     empty_front_axle_load_kg=500, empty_rear_axle_load_kg=300)
    session.add(vt)
    v = Vehicle(id=1, license_plate="29A-12345", vehicle_type_id=1)
    session.add(v)
    session.commit()


    # Trip.id là BIGINT trong PostgreSQL
    # và INTEGER PRIMARY KEY trong SQLite test.
    trip = Trip(
        vehicle_id=1,
        company_id=1,
        status="DRAFT",
    )

    session.add(trip)
    session.flush()

    # Lấy ID số nguyên do database sinh ra.
    trip_id = trip.id
    session.commit()

    session.add(DeliveryRequirement(id=1, trip_id=trip_id))
    session.commit()

    stop = DeliveryStop(
        id=1,
        trip_id=trip_id,
        stop_sequence=1,
        delivery_requirement_id=1,
    )

    session.add(stop)
    session.commit()

    order = TransportOrder(id=1, delivery_stop_id=1, order_code="ORD-01")
    session.add(order)
    session.commit()

    pt = PackageType(id=1, type_code="BOX_M", length=50, width=40, height=30)
    session.add(pt)
    pkg1 = CargoPackage(id=1, order_id=1, package_type_id=1, actual_weight_kg=15.0)
    pkg2 = CargoPackage(id=2, order_id=1, package_type_id=1, actual_weight_kg=20.0)
    session.add_all([pkg1, pkg2])
    session.commit()

    session.add_all([
        DeliveryRequirementPackage(delivery_requirement_id=1, package_id=1),
        DeliveryRequirementPackage(delivery_requirement_id=1, package_id=2),
    ])
    session.commit()

    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture
def sample_job(db_session):
    trip = db_session.query(Trip).first()
    job = OptimizationJob(
        job_uuid=str(uuid.uuid4()),
        trip_id=trip.id,
        status=OptimizationJobStatus.RUNNING.value,
        objective="MAX_VOLUME",
        time_limit_sec=60,
    )
    db_session.add(job)
    db_session.commit()
    db_session.refresh(job)
    return job


class TestOptimizationResultPersistenceService:
    def test_save_inserts_load_plan_placements_and_unplaced(self, db_session, sample_job):
        """Lưu đầy đủ LoadPlan, PackagePlacement và UnplacedPackage vào database"""
        service = OptimizationResultPersistenceService()

        pkg1 = db_session.query(CargoPackage).filter(CargoPackage.id == 1).first()
        pkg2 = db_session.query(CargoPackage).filter(CargoPackage.id == 2).first()

        engine_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=str(pkg1.id),
                    x=0.0,
                    y=0.0,
                    z=0.0,
                    packed_l=0.5,
                    packed_w=0.4,
                    packed_h=0.3,
                    rotation_type=0,
                    step_sequence=1,
                )
            ],
            unplaced=[
                UnplacedData(
                    package_id=str(pkg2.id),
                    reason="WEIGHT_LIMIT_EXCEEDED",
                )
            ],
            metrics=MetricsData(
                volume_utilization=0.65,
                weight_utilization=0.45,
                packed_count=1,
                computation_ms=250,
            ),
        )

        plan = service.save(sample_job.job_uuid, engine_response, db_session)

        assert plan is not None
        assert plan.id is not None
        assert plan.job_id == sample_job.id
        assert plan.packed_items_count == 1
        assert float(plan.volume_utilization) == 0.65
        assert float(plan.weight_utilization) == 0.45
        assert plan.approved is False

        # Verify placements in DB
        placements = db_session.query(PackagePlacement).filter(PackagePlacement.load_plan_id == plan.id).all()
        assert len(placements) == 1
        assert placements[0].package_id == pkg1.id
        assert float(placements[0].pos_x) == 0.0
        assert float(placements[0].pos_y) == 0.0
        assert float(placements[0].pos_z) == 0.0
        assert float(placements[0].packed_length) == 0.5
        assert float(placements[0].packed_width) == 0.4
        assert float(placements[0].packed_height) == 0.3
        assert placements[0].step_sequence == 1

        # Verify unplaced in DB
        unplaced = db_session.query(UnplacedPackage).filter(UnplacedPackage.load_plan_id == plan.id).all()
        assert len(unplaced) == 1
        assert unplaced[0].package_id == pkg2.id
        assert unplaced[0].rejection_reason in ("OVER_WEIGHT", "WEIGHT_LIMIT_EXCEEDED")

    def test_save_atomic_transaction_rolls_back_on_error(self, db_session, sample_job):
        """Nếu có lỗi giữa chừng, toàn bộ transaction bị rollback, không lưu rác trong DB"""
        service = OptimizationResultPersistenceService()

        engine_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id="invalid-pkg-uuid-that-causes-error",
                    x=0, y=0, z=0, packed_l=1, packed_w=1, packed_h=1,
                    rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.5, weight_utilization=0.5, packed_count=1, computation_ms=100),
        )

        # Giả lập lỗi khi insert placement
        from unittest.mock import patch
        with patch.object(service.package_placement_repository, "create_all", side_effect=RuntimeError("DB Error")):
            with pytest.raises(RuntimeError):
                service.save(sample_job.job_uuid, engine_response, db_session)

        # Kiểm tra không có LoadPlan nào bị lưu mồ côi
        plans = db_session.query(LoadPlan).filter(LoadPlan.job_id == sample_job.id).all()
        assert len(plans) == 0


class TestLoadPlanServiceQuery:
    def test_get_plans_for_job_returns_persisted_plans_and_placements(self, db_session, sample_job):
        """LoadPlanService.get_plans_for_job trả về danh sách LoadPlanResponse kèm placements thật từ DB"""
        persistence = OptimizationResultPersistenceService()
        engine_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id="1",
                    x=10, y=20, z=30,
                    packed_l=500, packed_w=400, packed_h=300,
                    rotation_type=1, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.8, weight_utilization=0.7, packed_count=1, computation_ms=150),
        )
        persistence.save(sample_job.job_uuid, engine_response, db_session)

        load_plan_service = LoadPlanService()
        plans = load_plan_service.get_plans_for_job(sample_job.job_uuid, db_session)

        assert len(plans) == 1
        assert plans[0].packed_items_count == 1
        assert plans[0].volume_utilization == 0.8
        assert plans[0].weight_utilization == 0.7
        assert len(plans[0].placements) == 1
        assert plans[0].placements[0].package_id == "1"
        assert plans[0].placements[0].pos_x == 10
        assert plans[0].placements[0].pos_y == 20
        assert plans[0].placements[0].pos_z == 30
        assert plans[0].placements[0].dim_x == 500
        assert plans[0].placements[0].rotation_type == 1


class TestIntegrationEndToEndPlansApi:
    def test_post_job_then_get_job_plans_returns_persisted_load_plan(self, db_session, service_token):
        """In-process HTTP/background/persistence integration, not a deployed E2E test."""
        trip = db_session.query(Trip).first()
        job = spring_reserved_job(db_session, trip)
        before = billing_snapshot(db_session)
        Sessions = sessionmaker(bind=db_session.get_bind())
        def test_db():
            with Sessions() as session:
                yield session
        runner = make_offline_runner(session_factory=Sessions)
        headers = {'Authorization': 'Bearer '+service_token()}
        with restored_overrides(app, {get_db: test_db, get_async_runner: lambda: runner}), TestClient(app) as client:
            post_resp = client.post('/api/v1/optimization/jobs', json={
                'trip_id': trip.id, 'job_uuid': job.job_uuid}, headers=headers)
            assert post_resp.status_code == 202
            job_id = post_resp.json()['data']['job_id']
            assert job_id == job.job_uuid
            plans_resp = client.get(f'/api/v1/optimization/jobs/{job_id}/plans', headers=headers)
            assert plans_resp.status_code == 200
            plans_data = plans_resp.json()['data']
            assert len(plans_data) >= 1
            assert plans_data[0]['packed_items_count'] >= 1
            assert len(plans_data[0]['placements']) >= 1
        db_session.expire_all()
        assert db_session.query(OptimizationJob).count() == 1
        assert billing_snapshot(db_session) == before
        runner.credit_client.deduct_credit.assert_not_awaited()
        runner.credit_client.refund_credit.assert_not_awaited()
