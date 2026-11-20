"""
Tests for S3-03 · OptimizationJob Service

Seams under test:
  - OptimizationJobService.create_job(trip_id, config, db) -> OptimizationJob
  - OptimizationJobService.get_job(job_uuid, db) -> OptimizationJob
  - OptimizationJobService.find_by_trip_id(trip_id, db) -> list[OptimizationJob]
  - OptimizationJobService.find_by_status(status, db) -> list[OptimizationJob]
  - OptimizationJobService.update_status(job_uuid, status, computation_ms, db) -> OptimizationJob
  - OptimizationJobRepository queries

Acceptance criteria (FR-OPT-02):
  1. Python create_job is retired: reject UNAUTHORIZED, never reserve or bill
  2. Status flow: PENDING -> RUNNING -> COMPLETED / FAILED / TIMEOUT / NO_SOLUTION
  3. get_job(job_id) -> trả status + metadata
  4. SQLAlchemy queries: find_by_trip_id(), find_by_status()
"""
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective
from app.dto.request.optimization_request import OptimizationJobRequest
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.repository.optimization.optimization_job_repository import OptimizationJobRepository
from app.service.optimize.optimization_job_service import OptimizationJobService
from tests.optimization_legacy_fixtures import spring_reserved_job, seed_admission_catalog, billing_snapshot


@pytest.fixture
def db_session():
    """Tạo in-memory SQLite database với schema đầy đủ cho tests."""
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(bind=engine)
    TestingSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    session = TestingSession()
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture
def service():
    return OptimizationJobService(repository=OptimizationJobRepository())


@pytest.fixture
def existing_trip(db_session):
    trip_id = 101
    trip = Trip(id=trip_id, trip_code="TRIP-TEST", company_id=1, status="PLANNED")
    db_session.add(trip)
    db_session.commit()
    return trip


class TestCreateJob:
    def test_create_job_existing_trip_is_unauthorized_without_reserve_or_debit(self, service, db_session, existing_trip):
        """Valid trip still cannot authorize Python to reserve or bill a new job."""
        req = OptimizationJobRequest(
            trip_id=existing_trip.id,
            objective=OptimizationObjective.MAX_VOLUME,
            time_limit_sec=60,
        )

        seed_admission_catalog(db_session, existing_trip)
        before = billing_snapshot(db_session)
        with pytest.raises(AppException) as exc_info:
            service.create_job(trip_id=existing_trip.id, config=req, db=db_session)
        assert exc_info.value.error_code == ErrorCode.UNAUTHORIZED
        assert db_session.query(OptimizationJob).count() == 0
        assert billing_snapshot(db_session) == before

    def test_create_job_with_non_existent_trip_raises_error(self, service, db_session, existing_trip):
        """The retired factory rejects before attempting a trip lookup."""
        non_existent_trip_id = 999_999
        req = OptimizationJobRequest(
            trip_id=non_existent_trip_id,
            objective=OptimizationObjective.MAX_VOLUME,
        )

        seed_admission_catalog(db_session, existing_trip)
        before = billing_snapshot(db_session)
        with pytest.raises(AppException) as exc_info:
            service.create_job(trip_id=non_existent_trip_id, config=req, db=db_session)

        assert exc_info.value.error_code == ErrorCode.UNAUTHORIZED
        assert db_session.query(OptimizationJob).count() == 0
        assert billing_snapshot(db_session) == before


class TestGetJob:
    def test_get_job_by_uuid_returns_job_and_metadata(self, service, db_session, existing_trip):
        """get_job(job_uuid) trả về thông tin job đúng"""
        req = OptimizationJobRequest(trip_id=existing_trip.id, objective=OptimizationObjective.AXLE_BALANCE)
        created = spring_reserved_job(db_session, existing_trip, objective=req.objective)

        found = service.get_job(job_uuid=created.job_uuid, db=db_session)

        assert found is not None
        assert found.job_uuid == created.job_uuid
        assert found.trip_id == existing_trip.id
        assert found.objective == OptimizationObjective.AXLE_BALANCE

    def test_get_job_not_found_raises_error(self, service, db_session):
        """get_job với uuid không tồn tại -> raise AppException(OPTIMIZATION_JOB_NOT_FOUND)"""
        with pytest.raises(AppException) as exc_info:
            service.get_job(job_uuid="non-existent-uuid", db=db_session)

        assert exc_info.value.error_code == ErrorCode.OPTIMIZATION_JOB_NOT_FOUND


class TestStatusFlow:
    @pytest.mark.parametrize(
        "target_status",
        [
            OptimizationJobStatus.COMPLETED,
            OptimizationJobStatus.FAILED,
            OptimizationJobStatus.TIMEOUT,
            OptimizationJobStatus.NO_SOLUTION,
        ],
    )
    def test_status_transition_pending_to_running_to_terminal(
        self, service, db_session, existing_trip, target_status
    ):
        """Kiểm tra status flow: PENDING -> RUNNING -> COMPLETED / FAILED / TIMEOUT / NO_SOLUTION"""
        req = OptimizationJobRequest(trip_id=existing_trip.id, objective=OptimizationObjective.MAX_VOLUME)
        job = spring_reserved_job(db_session, existing_trip, objective=req.objective)
        assert job.status == OptimizationJobStatus.PENDING

        # Chuyển sang RUNNING
        running_job = service.update_status(
            job_uuid=job.job_uuid,
            status=OptimizationJobStatus.RUNNING,
            db=db_session,
        )
        assert running_job.status == OptimizationJobStatus.RUNNING

        # Chuyển sang terminal status
        terminal_job = service.update_status(
            job_uuid=job.job_uuid,
            status=target_status,
            computation_ms=1250,
            db=db_session,
        )
        assert terminal_job.status == target_status
        assert terminal_job.computation_ms == 1250


class TestQueries:
    def test_find_by_trip_id(self, service, db_session, existing_trip):
        """Query danh sách jobs theo trip_id"""
        req1 = OptimizationJobRequest(trip_id=existing_trip.id, objective=OptimizationObjective.MAX_VOLUME)
        req2 = OptimizationJobRequest(trip_id=existing_trip.id, objective=OptimizationObjective.AXLE_BALANCE)

        spring_reserved_job(db_session, existing_trip, objective=req1.objective)
        spring_reserved_job(db_session, existing_trip, objective=req2.objective)

        jobs = service.find_by_trip_id(trip_id=existing_trip.id, db=db_session)
        assert len(jobs) == 2
        assert all(j.trip_id == existing_trip.id for j in jobs)

    def test_find_by_status(self, service, db_session, existing_trip):
        """Query danh sách jobs theo status"""
        req = OptimizationJobRequest(trip_id=existing_trip.id, objective=OptimizationObjective.MAX_VOLUME)
        job1 = spring_reserved_job(db_session, existing_trip, objective=req.objective)
        job2 = spring_reserved_job(db_session, existing_trip, objective=req.objective)

        service.update_status(job_uuid=job1.job_uuid, status=OptimizationJobStatus.RUNNING, db=db_session)

        pending_jobs = service.find_by_status(status=OptimizationJobStatus.PENDING, db=db_session)
        running_jobs = service.find_by_status(status=OptimizationJobStatus.RUNNING, db=db_session)

        assert any(j.job_uuid == job2.job_uuid for j in pending_jobs)
        assert any(j.job_uuid == job1.job_uuid for j in running_jobs)
