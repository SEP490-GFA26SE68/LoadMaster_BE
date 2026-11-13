"""
Trạng thái cuối của job là cuối: khi LoadMasterService đã đánh dấu job FAILED JOB_INTERRUPTED (job quá hạn, credit đã
hoàn), kết quả đến muộn từ bộ giải bị bỏ — trạng thái giữ FAILED, không lưu phương án nào, credit chỉ hoàn một lần.
"""
import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest
from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import sessionmaker

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    MetricsData,
    PlacementData,
)
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Base, Trip
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_job_service import (
    JobAlreadyFinishedError,
    OptimizationJobService,
)
from app.service.optimize.optimization_result_persistence_service import (
    OptimizationResultPersistenceService,
)

COMPANY = "7"


@pytest.fixture
def session_factory(tmp_path):
    # File SQLite: mỗi phiên một kết nối riêng, như Java và Python trên PostgreSQL. pysqlite tự quản giao dịch sai
    # với SAVEPOINT (RELEASE thành COMMIT), nên để SQLAlchemy tự phát BEGIN như hướng dẫn của SQLAlchemy.
    engine = create_engine(f"sqlite:///{tmp_path / 'jobs.db'}", connect_args={"check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _no_driver_transactions(dbapi_connection, _record):
        dbapi_connection.isolation_level = None

    @event.listens_for(engine, "begin")
    def _begin(connection):
        connection.exec_driver_sql("BEGIN")

    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine)
    with factory() as s:
        s.add(Trip(id=1, status="PLANNED"))
        s.commit()
    return factory


def _job(session, status=OptimizationJobStatus.RUNNING.value) -> str:
    job_uuid = str(uuid.uuid4())
    session.add(OptimizationJob(job_uuid=job_uuid, trip_id=1, status=status, company_id=COMPANY))
    session.commit()
    return job_uuid


def _interrupt(session_factory, job_uuid):
    """Lượt của LoadMasterService (InterruptedOptimizationJobService): UPDATE có điều kiện từ một kết nối khác."""
    with session_factory() as other:
        changed = other.execute(
            text(
                "UPDATE optimization_jobs SET status = 'FAILED' "
                "WHERE job_uuid = :u AND status IN ('PENDING', 'RUNNING')"
            ),
            {"u": job_uuid},
        ).rowcount
        other.commit()
    return changed


def _placed():
    return EngineOptimizationResponse(
        placements=[PlacementData(package_id="1", x=0, y=0, z=0, packed_l=10, packed_w=10, packed_h=10)],
        unplaced=[],
        metrics=MetricsData(packed_count=1, computation_ms=42),
    )


class Ledger:
    """Credit như LoadMasterService giữ: trừ theo mã job, hoàn mỗi mã job đúng một lần."""

    def __init__(self, balance=10):
        self.balance = balance
        self.used: dict[str, int] = {}
        self.refunded: set[str] = set()

    async def deduct_credit(self, company_id, reference):
        self.balance -= 2
        self.used[reference] = 2
        return True

    async def refund_credit(self, company_id, reference):
        self.refund(reference)
        return True

    def refund(self, reference):
        if reference in self.used and reference not in self.refunded:
            self.refunded.add(reference)
            self.balance += self.used[reference]


def test_update_status_does_not_overwrite_a_finished_job(session_factory):
    service = OptimizationJobService()
    with session_factory() as db:
        job_uuid = _job(db)
        assert _interrupt(session_factory, job_uuid) == 1

        with pytest.raises(JobAlreadyFinishedError) as raised:
            service.update_status(job_uuid, OptimizationJobStatus.COMPLETED, computation_ms=99, db=db)

        assert raised.value.current_status == "FAILED"
        job = service.get_job(job_uuid, db)
        assert job.status == "FAILED"
        assert job.computation_ms is None


def test_update_status_moves_unfinished_job(session_factory):
    service = OptimizationJobService()
    with session_factory() as db:
        job_uuid = _job(db, status=OptimizationJobStatus.PENDING.value)
        assert service.update_status(job_uuid, OptimizationJobStatus.RUNNING, db=db).status == "RUNNING"
        done = service.update_status(job_uuid, OptimizationJobStatus.COMPLETED, computation_ms=5, db=db)
        assert (done.status, done.computation_ms) == ("COMPLETED", 5)
        # Đã COMPLETED thì lượt báo lỗi đến sau cũng không đổi được.
        with pytest.raises(JobAlreadyFinishedError):
            service.update_status(job_uuid, OptimizationJobStatus.FAILED, db=db)


def _runner(session_factory, solve, ledger):
    job_service = OptimizationJobService()
    client = MagicMock()
    client.solve = solve
    handler = EngineExceptionHandler(job_service=job_service, notification_service=False, credit_client=None)
    handler.credit_client = None
    return AsyncOptimizationRunner(
        job_service=job_service,
        optimization_client=client,
        engine_exception_handler=handler,
        persistence_service=OptimizationResultPersistenceService(job_service=job_service),
        session_factory=session_factory,
        credit_client=ledger,
    )


@pytest.mark.asyncio
async def test_late_result_after_interruption_is_discarded(session_factory):
    with session_factory() as db:
        job_uuid = _job(db, status=OptimizationJobStatus.PENDING.value)
    ledger = Ledger()

    async def solve_slowly(problem):
        # Trong lúc bộ giải còn chạy, LoadMasterService thấy job quá hạn: FAILED và hoàn credit đã trừ.
        assert _interrupt(session_factory, job_uuid) == 1
        ledger.refund(job_uuid)
        return _placed()

    runner = _runner(session_factory, AsyncMock(side_effect=solve_slowly), ledger)
    await runner.run(job_uuid, problem=MagicMock())

    with session_factory() as db:
        job = db.query(OptimizationJob).filter_by(job_uuid=job_uuid).one()
        assert job.status == "FAILED"
        assert job.computation_ms is None
        assert db.query(LoadPlan).filter_by(job_id=job.id).count() == 0
    # Trừ một lần, hoàn đúng một lần: số dư về như trước lần chạy.
    assert ledger.balance == 10
    assert ledger.refunded == {job_uuid}


@pytest.mark.asyncio
async def test_job_interrupted_before_it_starts_does_not_run(session_factory):
    with session_factory() as db:
        job_uuid = _job(db, status=OptimizationJobStatus.PENDING.value)
    _interrupt(session_factory, job_uuid)
    ledger = Ledger()
    solve = AsyncMock(return_value=_placed())

    await _runner(session_factory, solve, ledger).run(job_uuid, problem=MagicMock())

    solve.assert_not_awaited()
    with session_factory() as db:
        assert db.query(OptimizationJob).filter_by(job_uuid=job_uuid).one().status == "FAILED"
    # Lượt trừ của runner (trước khi biết job đã kết thúc) được hoàn lại.
    assert ledger.balance == 10


@pytest.mark.asyncio
async def test_result_in_time_is_saved_with_its_plans(session_factory):
    with session_factory() as db:
        job_uuid = _job(db, status=OptimizationJobStatus.PENDING.value)
    ledger = Ledger()

    await _runner(session_factory, AsyncMock(return_value=_placed()), ledger).run(job_uuid, problem=MagicMock())

    with session_factory() as db:
        job = db.query(OptimizationJob).filter_by(job_uuid=job_uuid).one()
        assert (job.status, job.computation_ms) == ("COMPLETED", 42)
        assert db.query(LoadPlan).filter_by(job_id=job.id).count() == 1
    assert ledger.balance == 8
    # Kết thúc rồi thì lượt đánh dấu quá hạn của LoadMasterService không đổi được dòng nào.
    assert _interrupt(session_factory, job_uuid) == 0
