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
