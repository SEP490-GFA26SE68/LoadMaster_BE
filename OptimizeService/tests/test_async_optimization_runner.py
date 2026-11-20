"""
Tests for AsyncOptimizationRunner — S3-08

Seams under test:
  - AsyncOptimizationRunner.run(job_uuid, problem, db)
  - AsyncOptimizationRunner.build_problem_request(job_uuid, db)
  - Integration: POST /api/v1/optimization/jobs with BackgroundTasks execution -> GET /api/v1/optimization/jobs/{id}

Acceptance Criteria:
  - POST /api/v1/optimization/jobs trả 202 Accepted + { job_id } ngay lập tức
  - Job chạy background qua BackgroundTasks của FastAPI
  - Update job status RUNNING khi bắt đầu, COMPLETED/FAILED/TIMEOUT/NO_SOLUTION/PARTIAL khi xong
  - Frontend poll GET /api/v1/optimization/jobs/{id} để kiểm tra status
"""
import uuid
import pytest
import httpx
from unittest.mock import AsyncMock, MagicMock, patch
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from starlette.testclient import TestClient

from app.main import app
from app.controller.optimization.optimization_router import get_async_runner
from tests.optimization_legacy_fixtures import (spring_reserved_job, billing_snapshot, restored_overrides, service_token, make_offline_runner)
from app.config.database import get_db, SessionLocal
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.problem_request import ProblemRequest, VehicleData, PackageData
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    PlacementData,
    UnplacedData,
    MetricsData,
)
from app.entity.trip_model import (
    Base,
    DeliveryRequirement,
    DeliveryRequirementPackage,
    Trip,
    Vehicle,
    VehicleType,
    DeliveryStop,
    TransportOrder,
    CargoPackage,
    PackageType,
)
from app.entity.optimization.optimization_job import OptimizationJob
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.optimization_job_service import OptimizationJobService


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

from sqlalchemy.pool import StaticPool

@pytest.fixture
def db_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        echo=False,
    )
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()

    # Tạo trip + vehicle + packages
    vt = VehicleType(max_cog_offset_ratio=0.15, 
        id=1,
        name="Van 1 Tan",
        inner_length=300,  # cm
        inner_width=180,   # cm
        inner_height=180,  # cm
        max_payload_kg=1000.0,
        door_width=180, door_height=180, front_axle_x=50, rear_axle_x=250,
        front_axle_limit_kg=2000, rear_axle_limit_kg=3000,
        empty_front_axle_load_kg=500, empty_rear_axle_load_kg=300,
    )
    session.add(vt)
    session.commit()

    v = Vehicle(id=1, license_plate="29A-12345", vehicle_type_id=1)
    session.add(v)
    session.commit()

    trip_id = 101
    trip = Trip(id=trip_id, company_id=1, vehicle_id=1, status="DRAFT")
    session.add(trip)
    session.commit()

    session.add(DeliveryRequirement(id=1, trip_id=trip_id))
    session.commit()

    stop = DeliveryStop(id=1, trip_id=trip_id, stop_sequence=1, delivery_requirement_id=1)
    session.add(stop)
    session.commit()

    order = TransportOrder(id=1, delivery_stop_id=1, order_code="ORD-01")
    session.add(order)
    session.commit()

    pt = PackageType(id=1, type_code="BOX_M", length=50, width=40, height=30)
    session.add(pt)
    session.commit()

    pkg = CargoPackage(id=1, order_id=1, package_type_id=1, actual_weight_kg=15.0)
    session.add(pkg)
    session.commit()

    session.add(DeliveryRequirementPackage(delivery_requirement_id=1, package_id=1))
    session.commit()

    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture
def sample_problem():
    return ProblemRequest(
        vehicle=VehicleData(inner_l=300, inner_w=180, inner_h=180, max_payload_kg=1000.0),
        packages=[
            PackageData(id="1", l=50, w=40, h=30, weight=15.0)
        ],
        objective="MAX_VOLUME_UTIL",
        time_limit_sec=30,
    )


# ---------------------------------------------------------------------------
# Unit Tests for AsyncOptimizationRunner
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestAsyncOptimizationRunnerUnit:
    async def test_run_success_transitions_running_then_completed(self, db_session, sample_problem):
        """Runner cập nhật RUNNING khi bắt đầu và COMPLETED khi giải xong"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = spring_reserved_job(db_session, trip, objective="MAX_VOLUME_UTIL", time_limit_sec=30)

        # Mock client solve
        mock_client = MagicMock(spec=OptimizationClient)
        mock_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id="1", x=0, y=0, z=0,
                    packed_l=50, packed_w=40, packed_h=30, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.5, weight_utilization=0.5, packed_count=1, computation_ms=120),
        )
        mock_client.solve = AsyncMock(return_value=mock_response)

        runner = make_offline_runner(
            job_service=job_service,
            optimization_client=mock_client,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        db_session.refresh(job)
        assert job.status == OptimizationJobStatus.COMPLETED.value
        assert job.computation_ms == 120
        mock_client.solve.assert_awaited_once_with(sample_problem)

    async def test_run_calls_persistence_service_if_provided(self, db_session, sample_problem):
        """Runner gọi persistence_service.save nếu có"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = spring_reserved_job(db_session, trip)

        mock_client = MagicMock(spec=OptimizationClient)
        mock_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id="1", x=0, y=0, z=0,
                    packed_l=50, packed_w=40, packed_h=30, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.5, weight_utilization=0.5, packed_count=1, computation_ms=80),
        )
        mock_client.solve = AsyncMock(return_value=mock_response)
        mock_persistence = MagicMock()

        runner = make_offline_runner(
            job_service=job_service,
            optimization_client=mock_client,
            persistence_service=mock_persistence,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        mock_persistence.save.assert_called_once_with(job.job_uuid, mock_response, db_session)
        mock_client.solve.assert_awaited_once_with(sample_problem)

    async def test_run_catches_timeout_and_updates_status_timeout(self, db_session, sample_problem):
        """Khi client ném TimeoutException, runner bắt lỗi và cập nhật TIMEOUT"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = spring_reserved_job(db_session, trip)

        mock_client = MagicMock(spec=OptimizationClient)
        mock_client.solve = AsyncMock(side_effect=httpx.TimeoutException("Timeout"))

        runner = make_offline_runner(
            job_service=job_service,
            optimization_client=mock_client,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        db_session.refresh(job)
        assert job.status == OptimizationJobStatus.TIMEOUT.value
        mock_client.solve.assert_awaited_once_with(sample_problem)

    async def test_run_catches_generic_error_and_updates_status_failed(self, db_session, sample_problem):
        """Khi có lỗi bất ngờ, runner bắt lỗi và cập nhật FAILED không làm crash"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = spring_reserved_job(db_session, trip)

        mock_client = MagicMock(spec=OptimizationClient)
        mock_client.solve = AsyncMock(side_effect=RuntimeError("Algorithm crash"))

        runner = make_offline_runner(
            job_service=job_service,
            optimization_client=mock_client,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        db_session.refresh(job)
        assert job.status == OptimizationJobStatus.FAILED.value
        mock_client.solve.assert_awaited_once_with(sample_problem)

    async def test_build_problem_request_from_db(self, db_session):
        """Runner tự động xây dựng ProblemRequest từ Trip, Vehicle, Package trong DB"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = spring_reserved_job(db_session, trip, objective="MAX_VOLUME_UTIL", time_limit_sec=45)

        runner = make_offline_runner(job_service=job_service)
        problem = runner.build_problem_request(job.job_uuid, db=db_session)

        # Runner chuyển nguyên số đo của loại xe, không quy đổi đơn vị
        assert problem.vehicle.inner_l == 300.0
        assert problem.vehicle.inner_w == 180.0
        assert problem.vehicle.inner_h == 180.0
        assert problem.vehicle.max_payload_kg == 1000.0
        assert len(problem.packages) == 1
        assert problem.packages[0].l == 50.0
        assert problem.packages[0].w == 40.0
        assert problem.packages[0].h == 30.0
        assert problem.packages[0].weight == 15.0
        assert problem.time_limit_sec == 45


# ---------------------------------------------------------------------------
# Integration Tests with FastAPI TestClient & BackgroundTasks
# ---------------------------------------------------------------------------

@pytest.mark.integration  # In-process HTTP/SQLite integration; no external Keycloak.
class TestAsyncJobExecutionApi:
    def test_submit_job_returns_202_and_executes_in_background(self, db_session, service_token):
        """Local RS256 service JWT + real guards/claim/background EP; SQLite only."""
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
            response = client.post('/api/v1/optimization/jobs', json={
                'trip_id': trip.id, 'job_uuid': job.job_uuid}, headers=headers)
            assert response.status_code == 202
            res_data = response.json()
            assert res_data['success'] is True
            job_id = res_data['data']['job_id']
            assert job_id == job.job_uuid
            retry = client.post('/api/v1/optimization/jobs', json={'trip_id': trip.id, 'job_uuid': job_id}, headers=headers)
            assert retry.status_code == 202
            assert retry.json()['data']['job_id'] == job_id
            assert retry.json()['data']['dispatch_state'] == 'ALREADY_TERMINAL'
            poll_resp = client.get(f'/api/v1/optimization/jobs/{job_id}', headers=headers)
            assert poll_resp.status_code == 200
            poll_data = poll_resp.json()
            assert poll_data['success'] is True
            assert poll_data['data']['job_uuid'] == job_id
            assert poll_data['data']['status'] == OptimizationJobStatus.COMPLETED.value
            assert poll_data['data']['computation_ms'] is not None
        db_session.expire_all()
        assert db_session.query(OptimizationJob).count() == 1
        assert billing_snapshot(db_session) == before
        runner.credit_client.deduct_credit.assert_not_awaited()
        runner.credit_client.refund_credit.assert_not_awaited()


class TestLegacyFixtureIsolationAndGuards:
    def test_overrides_restore_after_failed_assertion_preserving_prior_entries(self):
        from app.service.auth.auth_dependency import require_optimization_service
        original = dict(app.dependency_overrides)
        def unrelated_dependency():
            pass
        def original_override():
            pass
        with restored_overrides(app, {unrelated_dependency: original_override}):
            before = dict(app.dependency_overrides)
            with pytest.raises(AssertionError, match='Synthetic assertion'):
                with restored_overrides(app, {get_db: lambda: None, require_optimization_service: lambda: {}}):
                    assert app.dependency_overrides != before
                    raise AssertionError('Synthetic assertion')
            assert app.dependency_overrides == before
        assert app.dependency_overrides == original

    @pytest.mark.parametrize('case,http_status', [
        ('missing_token', 401), ('wrong_audience', 401), ('missing_role', 403), ('wrong_service', 403),
    ])
    def test_fixture_jwt_does_not_bypass_actual_security(self, db_session, service_token, case, http_status):
        from app.service.auth.auth_dependency import require_optimization_service
        trip = db_session.query(Trip).first()
        job = spring_reserved_job(db_session, trip)
        before = billing_snapshot(db_session)
        changes = {'wrong_audience': {'aud': 'other-service'},
                   'missing_role': {'resource_access': {}},
                   'wrong_service': {'azp': 'other-client'}}
        headers = {} if case == 'missing_token' else {'Authorization': 'Bearer '+service_token(**changes[case])}
        runner = MagicMock()
        with restored_overrides(app, {get_db: lambda: db_session, get_async_runner: lambda: runner}), TestClient(app) as client:
            assert require_optimization_service not in app.dependency_overrides
            response = client.post('/api/v1/optimization/jobs', json={'trip_id': trip.id, 'job_uuid': job.job_uuid}, headers=headers)
            assert response.status_code == http_status
        runner.run.assert_not_called()
        runner.validate_trip_profile_orientations.assert_not_called()
        db_session.refresh(job)
        assert job.status == 'PENDING'
        assert billing_snapshot(db_session) == before

    def test_reserved_job_without_usage_is_not_authorized(self, db_session, service_token):
        from sqlalchemy import text
        trip = db_session.query(Trip).first()
        job = spring_reserved_job(db_session, trip)
        db_session.execute(text('DELETE FROM credit_transactions WHERE reference=:reference'), {'reference': job.job_uuid})
        db_session.commit()  # SQLite fixture only: reproduce an unbilled reservation.
        before = billing_snapshot(db_session)
        runner = MagicMock()
        with restored_overrides(app, {get_db: lambda: db_session, get_async_runner: lambda: runner}), TestClient(app) as client:
            response = client.post('/api/v1/optimization/jobs', json={'trip_id': trip.id, 'job_uuid': job.job_uuid},
                                   headers={'Authorization': 'Bearer '+service_token()})
            assert response.status_code == 403
        runner.run.assert_not_called()
        db_session.refresh(job)
        assert job.status == 'PENDING'
        assert billing_snapshot(db_session) == before
