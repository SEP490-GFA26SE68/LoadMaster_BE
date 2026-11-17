"""Pure unit tests; PostgreSQL/HTTP assertions live in JobLifecycleIntegrationPostgresTest."""
from datetime import datetime, timezone, timedelta
from types import SimpleNamespace as NS
from unittest.mock import MagicMock, Mock, patch
import pytest
from fastapi import HTTPException
from app.service.optimize.job_execution_protocol import JobExecutionProtocol, OwnershipLost
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode

NOW=datetime(2026,11,15,tzinfo=timezone.utc)

def fixture_job(**kw):
    data=dict(job_uuid='41858661-1c23-372e-9329-e09660e2648b',trip_id=7,status='PENDING',execution_generation=0,
              dispatch_expires_at=NOW+timedelta(seconds=120),lease_expires_at=None,execution_started_at=None,
              failure_code=None,time_limit_sec=60)
    data.update(kw);return NS(**data)

def setup(job):
    db=MagicMock();db.execute.return_value.scalar_one.return_value=job
    protocol=JobExecutionProtocol(Mock());protocol.now=Mock(return_value=NOW)
    return protocol,db

@patch('app.service.optimize.job_execution_protocol.require_authorized_job')
def test_prepared_snapshot_is_committed_before_return(auth):
    job=fixture_job();p,db=setup(job);builder=Mock(return_value=NS(model_dump_json=lambda:'{"validated":"snapshot"}'))
    answer=p.prepare(job.job_uuid,7,builder,db)
    assert (answer.generation,answer.status,answer.problem_json)==(1,'RUNNING','{"validated":"snapshot"}')
    db.commit.assert_called_once();assert job.lease_expires_at>NOW;assert job.execution_started_at is None
    auth.assert_called();assert auth.call_count==2

@patch('app.service.optimize.job_execution_protocol.require_authorized_job')
def test_invalid_preflight_commits_terminal_without_owner(auth):
    job=fixture_job();p,db=setup(job)
    builder=Mock(side_effect=AppException(ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID))
    answer=p.prepare(job.job_uuid,7,builder,db)
    assert answer.rejected and job.status=='FAILED' and job.execution_generation==0
    assert job.lease_expires_at is None;db.commit.assert_called_once()

@patch('app.service.optimize.job_execution_protocol.require_authorized_job')
@pytest.mark.parametrize('state,generation',[('RUNNING',1),('COMPLETED',1),('FAILED',0)])
def test_duplicate_does_not_rebuild_or_schedule(auth,state,generation):
    job=fixture_job(status=state,execution_generation=generation);p,db=setup(job);builder=Mock()
    answer=p.prepare(job.job_uuid,7,builder,db)
    assert answer.problem_json is None and answer.status==state;builder.assert_not_called()

@patch('app.service.optimize.job_execution_protocol.require_authorized_job')
def test_unknown_legacy_owner_is_never_claimed(auth):
    job=fixture_job(execution_generation=None);p,db=setup(job)
    with pytest.raises(HTTPException) as e:p.prepare(job.job_uuid,7,Mock(),db)
    assert e.value.status_code==409;db.commit.assert_not_called()

@pytest.mark.parametrize('kw',[dict(execution_generation=2),dict(status='FAILED'),dict(lease_expires_at=NOW),
                              dict(execution_started_at=NOW-timedelta(seconds=361))])
def test_owner_fails_closed_for_stale_expired_or_terminal(kw):
    job=fixture_job(status='RUNNING',execution_generation=1,lease_expires_at=NOW+timedelta(seconds=30))
    for k,v in kw.items():setattr(job,k,v)
    p,db=setup(job)
    with pytest.raises(OwnershipLost):p.owner(db,job.job_uuid,1)

def test_heartbeat_is_independent_of_blocking_synchronous_solver():
    import threading
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
    from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
    pulse=threading.Event()
    protocol=Mock(heartbeat_seconds=0.01)
    protocol.begin.return_value=True
    protocol.heartbeat.side_effect=lambda *args:pulse.set()
    class BlockingSolver:
        async def solve(self,problem):
            assert pulse.wait(3), 'Heartbeat blocked behind solver event loop'
            return EngineOptimizationResponse(placements=[])
    runner=AsyncOptimizationRunner.__new__(AsyncOptimizationRunner)
    runner.optimization_client=BlockingSolver();runner.persistence_service=Mock()
    runner.engine_exception_handler=Mock();runner.engine_exception_handler.classify_result.return_value='NO_SOLUTION'
    with patch('app.service.optimize.async_optimization_runner.ProblemRequest') as model, patch('app.dto.optimization.engine.rotation_contract.validate_result_rotations'):
        model.model_validate_json.return_value=Mock()
        runner._execute_owned(protocol,'41858661-1c23-372e-9329-e09660e2648b',1,'{}')
    assert pulse.is_set();protocol.finalize.assert_called_once()
    assert protocol.finalize.call_args.args[2]=='NO_SOLUTION'
