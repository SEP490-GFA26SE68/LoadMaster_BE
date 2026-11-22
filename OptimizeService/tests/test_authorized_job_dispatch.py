import asyncio
from unittest.mock import AsyncMock, Mock
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool
from sqlalchemy.orm import Session
from app.config.database import Base
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from app.dto.request.optimization_request import OptimizationJobRequest
from datetime import timedelta
from app.config.clock import vietnam_now
from app.service.optimize.authorized_job_dispatch import claim_for_execution, require_authorized_job
from app.service.optimize.pending_job_recovery import stale_pending_v1_uuids
from app.service.optimize.optimization_job_service import OptimizationJobService
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.exception.app_exception import AppException
from app.dto.optimization.engine.problem_request import PackageData, VehicleData, ProblemRequest
from app.dto.optimization.engine.engine_response import PlacementData, EngineOptimizationResponse


def rotation_fixture():
    package = PackageData(id="1",l=40,w=30,h=25,weight=10,allowed_rotations=[0])
    problem = ProblemRequest(vehicle=VehicleData(inner_l=620,inner_w=240,inner_h=250,max_payload_kg=5000),packages=[package])
    result = EngineOptimizationResponse(placements=[PlacementData(package_id="1",x=290,y=100,z=0,packed_l=40,packed_w=30,packed_h=25,rotation_type=0)])
    return problem, result


@pytest.fixture
def db():
    engine=create_engine("sqlite:///:memory:",connect_args={"check_same_thread":False},poolclass=StaticPool)
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.execute(text("CREATE TABLE credit_accounts(id INTEGER PRIMARY KEY,company_id INTEGER)"))
        db.execute(text("CREATE TABLE credit_transactions(reference TEXT,type TEXT,credit_account_id INTEGER,refunded BOOLEAN)"))
        db.add(Trip(id=7,company_id=1))
        db.add(OptimizationJob(trip_id=7,job_uuid="authorized",company_id="1",subscription_tier="BASIC",
                               algorithm_tier="BASIC",algorithm_name="EP_DBLF",status="PENDING"))
        db.execute(text("INSERT INTO credit_accounts VALUES(1,1)"))
        db.execute(text("INSERT INTO credit_transactions VALUES('authorized','USAGE',1,false)"))
        db.commit()
        yield db
    engine.dispose()

def test_stale_pending_recovery_skips_fresh_and_unbilled_jobs(db):
    fresh = db.query(OptimizationJob).filter_by(job_uuid="authorized").one()
    fresh.created_at = vietnam_now()
    db.commit()
    assert stale_pending_v1_uuids(db) == []
    fresh.created_at = vietnam_now() - timedelta(seconds=20)
    db.commit()
    assert stale_pending_v1_uuids(db) == ["authorized"]
    db.execute(text("DELETE FROM credit_transactions"))
    db.commit()
    assert stale_pending_v1_uuids(db) == []


def test_retry_claims_existing_job_once_without_creating_another(db):
    assert claim_for_execution("authorized",7,db)
    assert not claim_for_execution("authorized",7,db)
    assert db.query(OptimizationJob).count()==1
    assert db.execute(text("select count(*) from credit_transactions")).scalar()==1

@pytest.mark.parametrize("change",[
    "UPDATE optimization_jobs SET company_id=NULL",
    "UPDATE optimization_jobs SET company_id='2'",
    "UPDATE trips SET company_id=NULL",
    "UPDATE optimization_jobs SET parent_plan_id=47",
    "UPDATE optimization_jobs SET subscription_tier='HACK'",
    "UPDATE credit_transactions SET refunded=true",
    "DELETE FROM credit_transactions",
])
def test_missing_or_inconsistent_authorization_fails_closed(db,change):
    db.execute(text(change));db.commit()
    with pytest.raises(AppException):claim_for_execution("authorized",7,db)
    assert db.query(OptimizationJob).first().status=="PENDING"

def test_unreserved_request_cannot_create_a_job(db):
    for ref in [None,"missing"]:
        with pytest.raises(AppException):claim_for_execution(ref,7,db)
    with pytest.raises(AppException):
        OptimizationJobService().create_job(7,OptimizationJobRequest(trip_id=7),db)
    assert db.query(OptimizationJob).count()==1

def test_runner_never_deducts_again_and_preserves_final_validation_pipeline(db):
    problem, result = rotation_fixture()
    solver=Mock(solve=AsyncMock(return_value=result))
    persistence=Mock();handler=Mock();credit=Mock(deduct_credit=AsyncMock(),refund_credit=AsyncMock())
    runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=persistence,
                                   engine_exception_handler=handler,credit_client=credit)
    asyncio.run(runner.run("authorized",problem=problem,db=db))
    credit.deduct_credit.assert_not_awaited()
    solver.solve.assert_awaited_once_with(problem)
    persistence.save.assert_called_once()
    handler.handle_result.assert_called_once()

def test_runner_with_missing_company_never_calls_solver_or_refund(db):
    db.execute(text("UPDATE optimization_jobs SET company_id=NULL"));db.commit()
    solver=Mock(solve=AsyncMock());handler=Mock();credit=Mock(deduct_credit=AsyncMock(),refund_credit=AsyncMock())
    runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=Mock(),
                                   engine_exception_handler=handler,credit_client=credit)
    asyncio.run(runner.run("authorized",problem=object(),db=db))
    solver.solve.assert_not_awaited();credit.deduct_credit.assert_not_awaited();credit.refund_credit.assert_not_awaited()
    handler.handle.assert_called_once()

def test_completed_retry_does_not_schedule_execution(db):
    db.execute(text("UPDATE optimization_jobs SET status='COMPLETED'"));db.commit()
    assert not claim_for_execution("authorized",7,db)

def test_duplicate_background_tasks_execute_solver_once(db):
    problem, result = rotation_fixture()
    solver=Mock(solve=AsyncMock(return_value=result))
    handler=Mock();credit=Mock(deduct_credit=AsyncMock(),refund_credit=AsyncMock())
    runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=Mock(),
                                   engine_exception_handler=handler,credit_client=credit)
    asyncio.run(runner.run("authorized",problem=problem,db=db))
    asyncio.run(runner.run("authorized",problem=problem,db=db))
    solver.solve.assert_awaited_once()
    credit.deduct_credit.assert_not_awaited()

def test_submit_endpoint_uses_server_job_and_ignores_request_tier(db):
    from fastapi import BackgroundTasks
    from app.controller.optimization.optimization_router import submit_job
    runner=Mock()
    tasks=BackgroundTasks()
    request=OptimizationJobRequest(trip_id=7,job_uuid="authorized",company_id="attacker",
                                   subscription_tier="ULTIMATE",algorithm_name="AI")
    response=asyncio.run(submit_job(request,tasks,db,Mock(),runner,{}))
    assert response.data.model_dump(mode="json")=={
        "job_id":"authorized", "status":"PENDING", "dispatch_state":"ACCEPTED"}
    assert len(tasks.tasks)==1
    job=db.query(OptimizationJob).first()
    assert job.company_id=="1" and job.subscription_tier=="BASIC" and job.algorithm_name=="EP_DBLF"
    assert job.status=="PENDING"  # Scheduling alone must not claim execution.

def test_failed_task_scheduling_can_retry_pending_job(db):
    from app.controller.optimization.optimization_router import submit_job
    tasks=Mock();tasks.add_task.side_effect=RuntimeError("scheduling failed")
    req=OptimizationJobRequest(trip_id=7,job_uuid="authorized")
    with pytest.raises(RuntimeError):asyncio.run(submit_job(req,tasks,db,Mock(),Mock(),{}))
    assert db.query(OptimizationJob).first().status=="PENDING"
    assert claim_for_execution("authorized",7,db)


@pytest.mark.parametrize("state,ack,count", [
    ("PENDING","ACCEPTED",1),("RUNNING","ALREADY_RUNNING",0),
    *[(s,"ALREADY_TERMINAL",0) for s in
      ["COMPLETED","SUCCESS","FAILED","TIMEOUT","NO_SOLUTION","PARTIAL"]],
])
def test_dispatch_ack_preserves_identity_and_current_state(db,state,ack,count):
    from fastapi import BackgroundTasks
    from app.controller.optimization.optimization_router import submit_job
    db.execute(text("UPDATE optimization_jobs SET status=:state"),{"state":state});db.commit()
    tasks=BackgroundTasks()
    result=asyncio.run(submit_job(OptimizationJobRequest(trip_id=7,job_uuid="authorized"),tasks,db,Mock(),Mock(),{}))
    assert result.model_dump(mode="json")["data"] == {
        "job_id":"authorized","status":state,"dispatch_state":ack}
    assert result.success is True
    assert len(tasks.tasks)==count
    assert db.query(OptimizationJob).first().status==state
    assert db.query(OptimizationJob).count()==1


def test_response_lost_then_dispatch_retry_cannot_execute_a_second_worker(db):
    from fastapi import BackgroundTasks
    from app.controller.optimization.optimization_router import submit_job
    problem, result = rotation_fixture()
    solver=Mock(solve=AsyncMock(return_value=result))
    runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=Mock(),
                                   engine_exception_handler=Mock(),credit_client=Mock())
    tasks=BackgroundTasks()
    req=OptimizationJobRequest(trip_id=7,job_uuid="authorized")
    asyncio.run(submit_job(req,tasks,db,Mock(),runner,{})) # ACK lost at transport boundary.
    asyncio.run(runner.run("authorized",problem=object(),db=db))
    retry_tasks=BackgroundTasks()
    retry=asyncio.run(submit_job(req,retry_tasks,db,Mock(),runner,{}))
    assert retry.data.dispatch_state=="ALREADY_RUNNING"
    assert not retry_tasks.tasks
    asyncio.run(runner.run("authorized",problem=object(),db=db))
    solver.solve.assert_awaited_once()
    assert db.execute(text("SELECT count(*) FROM credit_transactions")).scalar()==1


def test_duplicate_pending_dispatches_only_one_task_reaches_solver(db):
    from fastapi import BackgroundTasks
    from app.controller.optimization.optimization_router import submit_job
    problem, result = rotation_fixture()
    solver=Mock(solve=AsyncMock(return_value=result))
    runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=Mock(),
                                   engine_exception_handler=Mock(),credit_client=Mock())
    # Both requests can see PENDING and schedule. The DB CAS, not in-memory scheduling, owns admission.
    tasks=BackgroundTasks()
    for _ in range(2):
        asyncio.run(submit_job(OptimizationJobRequest(trip_id=7,job_uuid="authorized"),tasks,db,Mock(),runner,{}))
    assert len(tasks.tasks)==2
    for _ in tasks.tasks: asyncio.run(runner.run("authorized",problem=object(),db=db))
    solver.solve.assert_awaited_once()


def test_unknown_status_is_not_acknowledged_or_reset(db):
    db.execute(text("UPDATE optimization_jobs SET status='UNRECOGNIZED'"));db.commit()
    with pytest.raises(AppException):require_authorized_job("authorized",7,db)
    assert db.query(OptimizationJob).first().status=="UNRECOGNIZED"


@pytest.fixture
def http_client(db):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.controller.optimization.optimization_router import router, get_async_runner
    from app.config.database import get_db
    from app.service.auth.auth_dependency import require_optimization_service
    app=FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_db]=lambda: db
    app.dependency_overrides[require_optimization_service]=lambda: {"service":"offline-test"}
    app.dependency_overrides[get_async_runner]=lambda: Mock(run=AsyncMock())
    with TestClient(app) as client: yield client


@pytest.mark.parametrize("state",["PENDING","RUNNING","COMPLETED","SUCCESS","PARTIAL","FAILED","NO_SOLUTION","TIMEOUT"])
def test_http_dispatch_and_get_preserve_exact_status_and_identity(db,http_client,state):
    db.execute(text("UPDATE optimization_jobs SET status=:state"),{"state":state});db.commit()
    response=http_client.post("/api/v1/optimization/jobs",json={"trip_id":7,"job_uuid":"authorized"})
    assert response.status_code==202
    assert response.json()["success"] is True
    assert response.json()["data"]["job_id"]=="authorized"
    assert response.json()["data"]["status"]==state
    result=http_client.get("/api/v1/optimization/jobs/authorized")
    assert result.status_code==200
    assert result.json()["data"]["status"]==state
    assert result.json()["data"]["job_uuid"]=="authorized"


@pytest.mark.parametrize("payload",[{"trip_id":7},{"trip_id":7,"job_uuid":""},{"job_uuid":"authorized"}])
def test_http_dispatch_requires_both_reserved_job_and_trip(http_client,payload):
    assert http_client.post("/api/v1/optimization/jobs",json=payload).status_code==422


@pytest.mark.parametrize("change", [dict(rotation_type=1,packed_l=30,packed_w=40),dict(packed_l=41)])
def test_runner_blocks_invalid_rotation_before_persistence(db, change):
    problem, result = rotation_fixture()
    result.placements[0] = result.placements[0].model_copy(update=change)
    persistence=Mock(); handler=Mock()
    runner=AsyncOptimizationRunner(optimization_client=Mock(solve=AsyncMock(return_value=result)),
                                  persistence_service=persistence,engine_exception_handler=handler,
                                  credit_client=Mock(refund_credit=AsyncMock()))
    asyncio.run(runner.run("authorized",problem=problem,db=db))
    persistence.save.assert_not_called()
    handler.handle_result.assert_not_called()
    assert isinstance(handler.handle.call_args.args[0],ValueError)
