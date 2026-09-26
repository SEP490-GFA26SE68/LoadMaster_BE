"""Service contract tests for S3-03 against database schema v3.4."""

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective
from app.dto.request.optimization_request import OptimizationJobRequest
from app.entity.trip_model import Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.repository.optimization.optimization_job_repository import OptimizationJobRepository
from app.service.optimize.optimization_job_service import OptimizationJobService


@pytest.fixture
def db_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(bind=engine)
    session = sessionmaker(bind=engine, autoflush=False, autocommit=False)()
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


@pytest.fixture
def existing_trip(db_session):
    trip = Trip(
        id=3501,
        company_id=1,
        trip_code="TRIP-3501",
        vehicle_id=None,
        created_by_dispatcher_id=1,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.commit()
    return trip


def test_create_job_persists_schema_v34_fields(db_session, existing_trip):
    request = OptimizationJobRequest(
        trip_id=str(existing_trip.id),
        objective=OptimizationObjective.AXLE_BALANCE,
    )

    job = OptimizationJobService().create_job(existing_trip.id, request, db_session)

    assert job.id == 1
    assert job.trip_id == 3501
    assert job.algorithm_objective == "AXLE_BALANCE"
    assert job.execution_time_ms is None
    assert job.status == OptimizationJobStatus.PENDING.value


def test_get_job_returns_job_by_numeric_id(db_session, existing_trip):
    request = OptimizationJobRequest(
        trip_id=str(existing_trip.id),
        objective=OptimizationObjective.MAX_VOLUME,
    )
    created = OptimizationJobService().create_job(
        existing_trip.id,
        request,
        db_session,
    )

    found = OptimizationJobService().get_job(created.id, db_session)

    assert found.id == created.id
    assert found.trip_id == existing_trip.id
    assert found.algorithm_objective == "MAX_VOLUME"
    assert found.execution_time_ms is None
    assert found.status == OptimizationJobStatus.PENDING.value


@pytest.mark.parametrize(
    "terminal_status",
    [
        OptimizationJobStatus.COMPLETED,
        OptimizationJobStatus.FAILED,
        OptimizationJobStatus.TIMEOUT,
        OptimizationJobStatus.NO_SOLUTION,
    ],
)
def test_job_moves_from_pending_through_running_to_terminal_status(
    db_session,
    existing_trip,
    terminal_status,
):
    request = OptimizationJobRequest(
        trip_id=str(existing_trip.id),
        objective=OptimizationObjective.MAX_VOLUME,
    )
    service = OptimizationJobService()
    job = service.create_job(existing_trip.id, request, db_session)

    running = service.update_status(
        job.id,
        OptimizationJobStatus.RUNNING,
        db=db_session,
    )
    terminal = service.update_status(
        job.id,
        terminal_status,
        execution_time_ms=1250,
        db=db_session,
    )

    assert running.id == job.id
    assert terminal.status == terminal_status.value
    assert terminal.execution_time_ms == 1250


def test_jobs_can_be_found_by_numeric_trip_id_and_status(db_session, existing_trip):
    other_trip = Trip(
        id=3502,
        company_id=1,
        trip_code="TRIP-3502",
        vehicle_id=None,
        created_by_dispatcher_id=1,
        status="DRAFT",
    )
    db_session.add(other_trip)
    db_session.commit()

    service = OptimizationJobService()
    first = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    second = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.AXLE_BALANCE,
        ),
        db_session,
    )
    service.create_job(
        other_trip.id,
        OptimizationJobRequest(
            trip_id=str(other_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    service.update_status(second.id, OptimizationJobStatus.RUNNING, db=db_session)

    trip_jobs = service.find_by_trip_id(existing_trip.id, db_session)
    pending_jobs = service.find_by_status(OptimizationJobStatus.PENDING, db_session)

    assert [job.id for job in trip_jobs] == [first.id, second.id]
    assert [job.id for job in pending_jobs] == [first.id, 3]


def test_get_job_exposes_camel_case_properties_and_to_dict(db_session, existing_trip):
    service = OptimizationJobService()
    job = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    service.update_status(
        job.id,
        OptimizationJobStatus.COMPLETED,
        execution_time_ms=850,
        db=db_session,
    )

    found = service.get_job(job.id, db_session)
    # AC: get_job(job_id) -> trả id, tripId, algorithmObjective, executionTimeMs, status
    assert found.id == job.id
    assert found.tripId == existing_trip.id
    assert found.algorithmObjective == "MAX_VOLUME"
    assert found.executionTimeMs == 850
    assert found.status == OptimizationJobStatus.COMPLETED.value

    job_dict = found.to_dict()
    assert job_dict == {
        "id": job.id,
        "tripId": existing_trip.id,
        "algorithmObjective": "MAX_VOLUME",
        "executionTimeMs": 850,
        "status": OptimizationJobStatus.COMPLETED.value,
    }


def test_create_job_raises_when_trip_not_found(db_session):
    service = OptimizationJobService()
    request = OptimizationJobRequest(
        trip_id="99999",
        objective=OptimizationObjective.MAX_VOLUME,
    )
    with pytest.raises(AppException) as exc_info:
        service.create_job(99999, request, db_session)
    assert exc_info.value.error_code == ErrorCode.TRIP_NOT_FOUND


def test_create_job_with_invalid_trip_id_string_raises_trip_not_found(db_session):
    service = OptimizationJobService()
    request = OptimizationJobRequest(
        trip_id="not-a-number",
        objective=OptimizationObjective.MAX_VOLUME,
    )
    with pytest.raises(AppException) as exc_info:
        service.create_job("not-a-number", request, db_session)
    assert exc_info.value.error_code == ErrorCode.TRIP_NOT_FOUND


def test_create_job_with_numeric_string_trip_id(db_session, existing_trip):
    service = OptimizationJobService()
    request = OptimizationJobRequest(
        trip_id=str(existing_trip.id),
        objective=OptimizationObjective.MAX_VOLUME,
    )
    job = service.create_job(str(existing_trip.id), request, db_session)
    assert job.trip_id == existing_trip.id


def test_get_job_raises_when_job_not_found(db_session):
    service = OptimizationJobService()
    with pytest.raises(AppException) as exc_info:
        service.get_job(99999, db_session)
    assert exc_info.value.error_code == ErrorCode.OPTIMIZATION_JOB_NOT_FOUND


def test_get_job_with_string_id(db_session, existing_trip):
    service = OptimizationJobService()
    created = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    found = service.get_job(str(created.id), db_session)
    assert found.id == created.id


def test_get_job_with_invalid_string_id_raises_not_found(db_session):
    service = OptimizationJobService()
    with pytest.raises(AppException) as exc_info:
        service.get_job("invalid-id", db_session)
    assert exc_info.value.error_code == ErrorCode.OPTIMIZATION_JOB_NOT_FOUND


def test_update_status_raises_when_job_not_found(db_session):
    service = OptimizationJobService()
    with pytest.raises(AppException) as exc_info:
        service.update_status(99999, OptimizationJobStatus.RUNNING, db=db_session)
    assert exc_info.value.error_code == ErrorCode.OPTIMIZATION_JOB_NOT_FOUND


def test_update_status_with_string_job_id(db_session, existing_trip):
    service = OptimizationJobService()
    created = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    updated = service.update_status(
        str(created.id),
        OptimizationJobStatus.RUNNING,
        db=db_session,
    )
    assert updated.status == OptimizationJobStatus.RUNNING.value


def test_find_by_trip_id_returns_empty_when_no_jobs_exist(db_session):
    service = OptimizationJobService()
    jobs = service.find_by_trip_id(99999, db_session)
    assert jobs == []


def test_find_by_trip_id_with_string_trip_id(db_session, existing_trip):
    service = OptimizationJobService()
    created = service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    jobs = service.find_by_trip_id(str(existing_trip.id), db_session)
    assert len(jobs) == 1
    assert jobs[0].id == created.id


def test_find_by_trip_id_with_invalid_string_returns_empty(db_session):
    service = OptimizationJobService()
    assert service.find_by_trip_id("not-a-number", db_session) == []


def test_find_by_status_with_string_value(db_session, existing_trip):
    service = OptimizationJobService()
    service.create_job(
        existing_trip.id,
        OptimizationJobRequest(
            trip_id=str(existing_trip.id),
            objective=OptimizationObjective.MAX_VOLUME,
        ),
        db_session,
    )
    jobs = service.find_by_status("PENDING", db_session)
    assert len(jobs) == 1


def test_repository_find_by_id_invalid_id_returns_none(db_session):
    repo = OptimizationJobRepository()
    assert repo.find_by_id("not-a-number", db_session) is None
