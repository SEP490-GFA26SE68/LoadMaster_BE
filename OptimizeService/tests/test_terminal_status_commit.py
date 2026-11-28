"""V1 notification follows confirmed commit; no solver or credit side effects."""
import asyncio
from types import SimpleNamespace
from unittest.mock import Mock, AsyncMock
import httpx
import pytest
from app.constant.optimization.job_status import OptimizationJobStatus as Status
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_job_service import OptimizationJobService, JobAlreadyFinishedError
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from tests.test_remote_candidate_partition import problem, layout

UUID = '00000000-0000-0000-0000-000000000001'


def setup_handler(phase=None):
    db, repository, notifier, credit = Mock(), Mock(), Mock(), Mock()
    job = SimpleNamespace(execution_generation=None, company_id='1', trip_id=12)
    repository.find_by_job_uuid.return_value = job
    repository.transition_unfinished.return_value = True
    service = OptimizationJobService(repository=repository)
    if phase:
        getattr(db, phase).side_effect = RuntimeError('synthetic persistence failure')
    handler = EngineExceptionHandler(job_service=service, notification_service=notifier, credit_client=credit)
    return db, service, notifier, credit, handler


@pytest.mark.parametrize('phase', ['commit', 'refresh'])
def test_failed_or_unconfirmed_commit_never_notifies_success(phase):
    db, service, notifier, credit, handler = setup_handler(phase)
    with pytest.raises(RuntimeError, match='Terminal status persistence was not confirmed'):
        handler.handle_result(layout('complete'), UUID, db)
    notifier.notify_status.assert_not_called()
    assert credit.mock_calls == []
    db.commit.assert_called_once()


@pytest.mark.parametrize('kind,status', [
    ('complete', Status.COMPLETED), ('partial', Status.PARTIAL), ('none', Status.NO_SOLUTION)])
def test_result_notification_happens_once_after_commit(kind, status):
    db, service, notifier, credit, handler = setup_handler()
    events = []
    db.commit.side_effect = lambda: events.append('commit')
    db.refresh.side_effect = lambda job: events.append('refresh')
    notifier.notify_status.side_effect = lambda *a, **k: events.append('notify')
    assert handler.handle_result(layout(kind), UUID, db) == status
    assert events == ['commit', 'refresh', 'notify']
    notifier.notify_status.assert_called_once_with(UUID, status, computation_ms=0)
    assert credit.mock_calls == []


@pytest.mark.parametrize('phase', ['commit', 'refresh'])
@pytest.mark.parametrize('rollback_fails', [False, True])
def test_runner_does_not_refund_reclassify_or_retry_unconfirmed_terminal(monkeypatch, phase, rollback_fails):
    db, service, notifier, credit, handler = setup_handler(phase)
    handler.handle = Mock(side_effect=AssertionError('must not reclassify persistence uncertainty'))
    if rollback_fails:
        db.rollback.side_effect = RuntimeError('synthetic rollback failure')
    client, persistence = Mock(), Mock()
    client.solve = AsyncMock(return_value=layout('complete'))
    runner = AsyncOptimizationRunner(job_service=service, optimization_client=client,
        engine_exception_handler=handler, persistence_service=persistence,
        session_factory=Mock(return_value=db), credit_client=credit)
    monkeypatch.setattr('app.service.optimize.authorized_job_dispatch.claim_for_execution',
                        lambda uuid, trip, session: True)
    asyncio.run(runner.run(UUID, problem=problem()))
    client.solve.assert_awaited_once()
    persistence.save.assert_called_once()
    handler.handle.assert_not_called()
    notifier.notify_status.assert_not_called()
    assert credit.mock_calls == []
    db.rollback.assert_called_once()


def test_late_terminal_guard_still_propagates_without_notify():
    db, service, notifier, credit, handler = setup_handler()
    service.update_status = Mock(side_effect=JobAlreadyFinishedError('FAILED'))
    with pytest.raises(JobAlreadyFinishedError):
        handler.handle_result(layout('complete'), UUID, db)
    notifier.notify_status.assert_not_called()
    assert credit.mock_calls == []


@pytest.mark.parametrize('exc,status', [
    (httpx.TimeoutException('synthetic timeout'), Status.TIMEOUT),
    (RuntimeError('synthetic engine failure'), Status.FAILED)])
def test_existing_error_statuses_unchanged(exc, status):
    db, service, notifier, credit, handler = setup_handler()
    assert handler.handle(exc, UUID, db) == status
    notifier.notify_status.assert_called_once_with(UUID, status, computation_ms=None)
    credit.refund_credit.assert_called_once_with(UUID)


def test_notification_failure_does_not_retry_committed_terminal():
    db, service, notifier, credit, handler = setup_handler()
    notifier.notify_status.side_effect = RuntimeError('synthetic notification failure')
    assert handler.handle_result(layout('complete'), UUID, db) == Status.COMPLETED
    notifier.notify_status.assert_called_once()
    db.commit.assert_called_once()
    assert credit.mock_calls == []
