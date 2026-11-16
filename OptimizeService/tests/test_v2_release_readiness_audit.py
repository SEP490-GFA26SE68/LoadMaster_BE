"""Regression for the absolute-deadline release audit finding."""
from datetime import datetime,timedelta,timezone
from types import SimpleNamespace as NS
from unittest.mock import MagicMock,Mock
import pytest
from app.service.optimize.job_execution_protocol import JobExecutionProtocol,OwnershipLost

def test_finalize_rechecks_absolute_deadline_after_persistence():
    start=datetime(2026,11,15,tzinfo=timezone.utc)
    job=NS(execution_generation=1,status='RUNNING',lease_expires_at=start+timedelta(seconds=120),
           execution_started_at=start,time_limit_sec=60)
    db=MagicMock();db.execute.return_value.scalar_one.return_value=job
    sessions=MagicMock();sessions.return_value.__enter__.return_value=db
    protocol=JobExecutionProtocol(sessions,lease_seconds=120,heartbeat_seconds=5,grace_seconds=0)
    # Finalization starts before first heartbeat; its row lock blocks lease renewal.
    # Logical DB clock advances across the absolute budget while result flush executes.
    protocol.now=Mock(side_effect=[start+timedelta(seconds=1),start+timedelta(seconds=61)])
    with pytest.raises(OwnershipLost):
        protocol.finalize('41858661-1c23-372e-9329-e09660e2648b',1,'COMPLETED',write_result=lambda session:None)


@pytest.mark.parametrize('change', [
    {'execution_generation': 2}, {'status': 'FAILED'},
    {'lease_expires_at': datetime(2026, 11, 15, tzinfo=timezone.utc)},
    {'execution_started_at': None},
])
def test_finalize_rechecks_canonical_ownership_after_flushing(change):
    start = datetime(2026, 11, 15, tzinfo=timezone.utc)
    job = NS(execution_generation=1, status='RUNNING', time_limit_sec=60,
             execution_started_at=start, lease_expires_at=start+timedelta(seconds=120))
    db = MagicMock()
    db.execute.return_value.scalar_one.return_value = job
    sessions = MagicMock()
    sessions.return_value.__enter__.return_value = db
    protocol = JobExecutionProtocol(sessions, grace_seconds=0)
    protocol.now = Mock(return_value=start+timedelta(seconds=1))
    db.flush.side_effect = lambda: [setattr(job, key, value) for key, value in change.items()]
    with pytest.raises(OwnershipLost):
        protocol.finalize('41858661-1c23-372e-9329-e09660e2648b', 1, 'COMPLETED')
    db.flush.assert_called_once()
    assert db.execute.call_count == 2
    assert db.begin.return_value.__exit__.call_args.args[0] is OwnershipLost


@pytest.mark.parametrize('end_seconds,allowed', [(59, True), (60, False), (61, False)])
def test_absolute_boundary_is_exclusive_after_persistence(end_seconds, allowed):
    start = datetime(2026, 11, 15, tzinfo=timezone.utc)
    job = NS(execution_generation=1, status='RUNNING', time_limit_sec=60,
             execution_started_at=start, lease_expires_at=start+timedelta(seconds=120))
    db = MagicMock()
    db.execute.return_value.scalar_one.return_value = job
    sessions = MagicMock()
    sessions.return_value.__enter__.return_value = db
    protocol = JobExecutionProtocol(sessions, grace_seconds=0)
    protocol.now = Mock(side_effect=[start+timedelta(seconds=1), start+timedelta(seconds=end_seconds)])
    if allowed:
        protocol.finalize('41858661-1c23-372e-9329-e09660e2648b', 1, 'COMPLETED')
        assert job.status == 'COMPLETED'
    else:
        with pytest.raises(OwnershipLost):
            protocol.finalize('41858661-1c23-372e-9329-e09660e2648b', 1, 'COMPLETED')
        assert job.status == 'RUNNING'


def test_worker_losing_final_fence_does_not_notify_refund_or_write_terminal_again():
    from unittest.mock import patch
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
    from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
    protocol = Mock(heartbeat_seconds=100)
    protocol.begin.return_value = True
    protocol.finalize.side_effect = OwnershipLost('Synthetic deadline fence')
    class Solver:
        async def solve(self, problem):
            return EngineOptimizationResponse(placements=[])
    runner = AsyncOptimizationRunner.__new__(AsyncOptimizationRunner)
    runner.optimization_client = Solver()
    runner.persistence_service = Mock()
    runner.credit_client = Mock()
    runner.engine_exception_handler = Mock()
    runner.engine_exception_handler.classify_result.return_value = 'NO_SOLUTION'
    with patch('app.service.optimize.async_optimization_runner.ProblemRequest'), patch('app.dto.optimization.engine.rotation_contract.validate_result_rotations'):
        runner._execute_owned(protocol, '41858661-1c23-372e-9329-e09660e2648b', 1, '{}')
    protocol.finalize.assert_called_once()
    runner.persistence_service.save.assert_not_called()
    assert runner.credit_client.mock_calls == []
    assert [call[0] for call in runner.engine_exception_handler.mock_calls] == ['classify_result']
