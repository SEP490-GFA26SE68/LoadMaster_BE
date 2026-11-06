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

from tests.optimization_fixtures import bill_job, reserve_job, isolated_credit, token, install_jwks
from app.main import app
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
    vt = VehicleType(
        id=1,
        name="Van 1 Tan",
        inner_length=300,  # cm
        inner_width=180,   # cm
        inner_height=180,  # cm
        max_payload_kg=1000.0,
     max_cog_offset_ratio=0.15, door_width=180, door_height=180, front_axle_x=0, rear_axle_x=300, front_axle_limit_kg=2200, rear_axle_limit_kg=3300, empty_front_axle_load_kg=100, empty_rear_axle_load_kg=100)
    session.add(vt)
    session.commit()

    v = Vehicle(id=1, license_plate="29A-12345", vehicle_type_id=1)
    session.add(v)
    session.commit()

    trip_id = 1
    trip = Trip(id=trip_id, vehicle_id=1, status="DRAFT")
    session.add(trip)
    session.commit()

    stop = DeliveryStop(id=1, trip_id=trip_id, stop_sequence=1)
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

    from app.entity.trip_model import DeliveryRequirement,DeliveryRequirementPackage
    session.add(DeliveryRequirement(id=1,trip_id=trip_id))
    stop.delivery_requirement_id=1
    session.add(DeliveryRequirementPackage(delivery_requirement_id=1,package_id=1))
    session.commit()
    yield session
    session.close()


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
        job = OptimizationJob(
            job_uuid=str(uuid.uuid4()),
            trip_id=trip.id,
            status=OptimizationJobStatus.PENDING.value,
            objective="MAX_VOLUME_UTIL",
            time_limit_sec=30,
        )
        db_session.add(job)
        db_session.commit()
        bill_job(db_session,job)

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

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            credit_client=isolated_credit(),
            optimization_client=mock_client,
            engine_exception_handler=EngineExceptionHandler(job_service=job_service),
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
        job = OptimizationJob(
            job_uuid=str(uuid.uuid4()),
            trip_id=trip.id,
            status=OptimizationJobStatus.PENDING.value,
        )
        db_session.add(job)
        db_session.commit()
        bill_job(db_session,job)

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

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            credit_client=isolated_credit(),
            optimization_client=mock_client,
            persistence_service=mock_persistence,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        mock_persistence.save.assert_called_once_with(job.job_uuid, mock_response, db_session)

    async def test_run_catches_timeout_and_updates_status_timeout(self, db_session, sample_problem):
        """Khi client ném TimeoutException, runner bắt lỗi và cập nhật TIMEOUT"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = OptimizationJob(
            job_uuid=str(uuid.uuid4()),
            trip_id=trip.id,
            status=OptimizationJobStatus.PENDING.value,
        )
        db_session.add(job)
        db_session.commit()
        bill_job(db_session,job)

        mock_client = MagicMock(spec=OptimizationClient)
        mock_client.solve = AsyncMock(side_effect=httpx.TimeoutException("Timeout"))

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            credit_client=isolated_credit(),
            optimization_client=mock_client,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        db_session.refresh(job)
        assert job.status == OptimizationJobStatus.TIMEOUT.value

    async def test_run_catches_generic_error_and_updates_status_failed(self, db_session, sample_problem):
        """Khi có lỗi bất ngờ, runner bắt lỗi và cập nhật FAILED không làm crash"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = OptimizationJob(
            job_uuid=str(uuid.uuid4()),
            trip_id=trip.id,
            status=OptimizationJobStatus.PENDING.value,
        )
        db_session.add(job)
        db_session.commit()
        bill_job(db_session,job)

        mock_client = MagicMock(spec=OptimizationClient)
        mock_client.solve = AsyncMock(side_effect=RuntimeError("Algorithm crash"))

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            credit_client=isolated_credit(),
            optimization_client=mock_client,
        )

        await runner.run(job.job_uuid, problem=sample_problem, db=db_session)

        db_session.refresh(job)
        assert job.status == OptimizationJobStatus.FAILED.value

    async def test_build_problem_request_from_db(self, db_session):
        """Runner tự động xây dựng ProblemRequest từ Trip, Vehicle, Package trong DB"""
        trip = db_session.query(Trip).first()
        job_service = OptimizationJobService()
        job = OptimizationJob(
            job_uuid=str(uuid.uuid4()),
            trip_id=trip.id,
            status=OptimizationJobStatus.PENDING.value,
            objective="MAX_VOLUME_UTIL",
            time_limit_sec=45,
        )
        db_session.add(job)
        db_session.commit()

        runner = AsyncOptimizationRunner(job_service=job_service)
        problem = runner.build_problem_request(job.job_uuid, db=db_session)

        assert problem.vehicle.inner_l == 300
        assert problem.vehicle.inner_w == 180
        assert problem.vehicle.inner_h == 180
        assert problem.vehicle.max_payload_kg == 1000.0
        assert len(problem.packages) == 1
        assert problem.packages[0].l == 50
        assert problem.packages[0].w == 40
        assert problem.packages[0].h == 30
        assert problem.packages[0].weight == 15.0
        assert problem.time_limit_sec == 45


# ---------------------------------------------------------------------------
# Integration Tests with FastAPI TestClient & BackgroundTasks
# ---------------------------------------------------------------------------

class TestAsyncJobExecutionApi:
    def test_submit_job_returns_202_and_executes_in_background(self, db_session, monkeypatch):
        """
        Integration test:
          1. POST /api/v1/optimization/jobs trả về 202 Accepted và job_id
          2. Background task chạy và cập nhật status sang COMPLETED
          3. GET /api/v1/optimization/jobs/{job_id} trả về status COMPLETED
        """
        trip = db_session.query(Trip).first()
        from app.config.settings import settings
        install_jwks(monkeypatch)
        job=reserve_job(db_session,trip.id)
        headers={"Authorization":"Bearer "+token([settings.KEYCLOAK_REQUIRED_ROLE],service=True)}


        app.dependency_overrides[get_db] = lambda: db_session

        from app.controller.optimization.optimization_router import get_async_runner
        app.dependency_overrides[get_async_runner] = lambda: AsyncOptimizationRunner(
            session_factory=lambda:db_session,credit_client=isolated_credit())
        client = TestClient(app)

        submit_payload = {
            "trip_id": trip.id,
            "job_uuid": job.job_uuid,
            "objective": "MAX_VOLUME",
            "time_limit_sec": 30,
            "algorithm_name": "DEFAULT_GREEDY",
        }

        # POST /jobs -> 202
        response = client.post("/api/v1/optimization/jobs", json=submit_payload, headers=headers)
        assert response.status_code == 202
        res_data = response.json()
        assert res_data["success"] is True
        job_id = res_data["data"]["job_id"]
        assert job_id == job.job_uuid

        # Kiểm tra qua GET /jobs/{job_id} (TestClient thực thi BackgroundTasks đồng bộ trước khi trả về)
        poll_resp = client.get(f"/api/v1/optimization/jobs/{job_id}", headers=headers)
        assert poll_resp.status_code == 200
        poll_data = poll_resp.json()
        assert poll_data["success"] is True
        assert poll_data["data"]["job_uuid"] == job_id
        # Background task đã chạy xong và cập nhật COMPLETED (vì solve_in_process xếp kiện hàng vừa thùng xe)
        assert poll_data["data"]["status"] == OptimizationJobStatus.COMPLETED.value
        assert poll_data["data"]["computation_ms"] is not None

        app.dependency_overrides.clear()
