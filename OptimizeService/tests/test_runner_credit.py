"""Legacy V1 runner consumes Spring-billed USAGE; it must never deduct again.
Refund assertions cover the runner seam only; V2 settlement is tested separately.
"""
from unittest.mock import AsyncMock, MagicMock
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from app.config.database import Base
from app.entity.trip_model import Trip
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse,MetricsData,PlacementData,UnplacedData
from app.dto.optimization.engine.problem_request import ProblemRequest,VehicleData,PackageData
from app.service.optimize.optimization_job_service import OptimizationJobService
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from tests.optimization_legacy_fixtures import spring_reserved_job,billing_snapshot

@pytest.fixture
def reserved_db():
    engine=create_engine('sqlite:///:memory:',poolclass=StaticPool,connect_args={'check_same_thread':False})
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        db.add(Trip(id=101,company_id=7,status='PLANNED'));db.commit()
        yield db
    engine.dispose()

def _placed(unplaced=0):
    return EngineOptimizationResponse(
        placements=[PlacementData(package_id='1',x=0,y=0,z=0,packed_l=10,packed_w=10,packed_h=10)],
        unplaced=[UnplacedData(package_id='2',reason='NO_SPACE') for _ in range(unplaced)],
        metrics=MetricsData(packed_count=1,computation_ms=5))

def _nothing():
    return EngineOptimizationResponse(placements=[],unplaced=[UnplacedData(package_id='1',reason='NO_SPACE')],metrics=MetricsData(packed_count=0))

def _runner(solve,db,partial=False):
    job=spring_reserved_job(db,db.get(Trip,101))
    service=MagicMock(wraps=OptimizationJobService())
    credit=MagicMock(deduct_credit=AsyncMock(),refund_credit=AsyncMock())
    handler=EngineExceptionHandler(job_service=service,notification_service=False,credit_client=credit)
    handler.credit_client=None  # Preserve this test's original runner-only refund seam.
    runner=AsyncOptimizationRunner(job_service=service,optimization_client=MagicMock(solve=solve),
        engine_exception_handler=handler,persistence_service=MagicMock(),credit_client=credit)
    problem=ProblemRequest(vehicle=VehicleData(inner_l=100,inner_w=100,inner_h=100,max_payload_kg=1000),
        packages=[PackageData(id=str(i),l=10,w=10,h=10,weight=1) for i in range(1,3 if partial else 2)])
    return runner,credit,service,job.job_uuid,problem

def _last_status(service):
    return service.update_status.call_args.kwargs['status']

@pytest.mark.asyncio
async def test_completed_run_keeps_one_deduction(reserved_db):
    runner,credit,service,uuid,problem=_runner(AsyncMock(return_value=_placed()),reserved_db)
    before=billing_snapshot(reserved_db)
    await runner.run(uuid,problem=problem,db=reserved_db)
    credit.deduct_credit.assert_not_awaited();credit.refund_credit.assert_not_awaited()
    assert _last_status(service)==OptimizationJobStatus.COMPLETED
    assert billing_snapshot(reserved_db)==before

@pytest.mark.asyncio
async def test_partial_run_keeps_one_deduction(reserved_db):
    runner,credit,service,uuid,problem=_runner(AsyncMock(return_value=_placed(1)),reserved_db,partial=True)
    before=billing_snapshot(reserved_db)
    await runner.run(uuid,problem=problem,db=reserved_db)
    credit.deduct_credit.assert_not_awaited();credit.refund_credit.assert_not_awaited()
    assert _last_status(service)==OptimizationJobStatus.PARTIAL
    assert billing_snapshot(reserved_db)==before

@pytest.mark.asyncio
async def test_no_solution_refunds_exactly_once(reserved_db):
    runner,credit,service,uuid,problem=_runner(AsyncMock(return_value=_nothing()),reserved_db)
    await runner.run(uuid,problem=problem,db=reserved_db)
    credit.deduct_credit.assert_not_awaited();credit.refund_credit.assert_awaited_once_with('7',uuid)
    assert _last_status(service)==OptimizationJobStatus.NO_SOLUTION

@pytest.mark.asyncio
async def test_failed_run_refunds_exactly_once(reserved_db):
    runner,credit,service,uuid,problem=_runner(AsyncMock(side_effect=RuntimeError('synthetic solver error')),reserved_db)
    await runner.run(uuid,problem=problem,db=reserved_db)
    credit.deduct_credit.assert_not_awaited();credit.refund_credit.assert_awaited_once_with('7',uuid)
    assert _last_status(service)==OptimizationJobStatus.FAILED


@pytest.mark.asyncio
async def test_insufficient_credits_fails_job_without_running(reserved_db):
    """Spring refuses admission on insufficient funds: no valid USAGE can authorize Python."""
    from sqlalchemy import text
    solve=AsyncMock(return_value=_placed())
    runner,credit,service,uuid,problem=_runner(solve,reserved_db)
    reserved_db.execute(text('DELETE FROM credit_transactions WHERE reference=:ref'),{'ref':uuid})
    reserved_db.execute(text('UPDATE credit_accounts SET balance=0'))
    reserved_db.commit()  # Malformed/unbilled snapshot, only in this memory database.
    await runner.run(uuid,problem=problem,db=reserved_db)
    solve.assert_not_awaited()
    credit.deduct_credit.assert_not_awaited()
    credit.refund_credit.assert_not_awaited()
    assert _last_status(service)==OptimizationJobStatus.FAILED
